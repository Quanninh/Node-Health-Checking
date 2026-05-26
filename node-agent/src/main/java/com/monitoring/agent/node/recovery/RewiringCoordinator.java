package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.node.transport.UdpCoordinator;
import com.monitoring.agent.node.transport.UdpPacketType;
import com.monitoring.agent.util.Console;

public final class RewiringCoordinator {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(800);
    private static final int SEND_ATTEMPTS = 3;

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final NetworkTopologyCache topologyCache;
    private final UdpCoordinator udpCoordinator;

    private final ReentrantLock roleLock = new ReentrantLock();

    private volatile HealthState healthState = HealthState.SUFFICIENT;
    private volatile RecoveryRole recoveryRole = RecoveryRole.FREE;
    private volatile String activeDeficientRecoveryId;

    /**
     * Used while this node is sending REWIRE_REQ and waiting for reply.
     *
     * Requirement:
     * While defA is waiting for REWIRE_ACK, if it receives another REWIRE_REQ,
     * it must deny immediately.
     */
    private volatile boolean initiatingDeficientRequest = false;

    /**
     * One node may be REWIRING_NODE for multiple recovery sessions,
     * but the exact same edge C-D cannot be reserved by two recoveryIds.
     */
    private final ConcurrentHashMap<String, String> reservedEdgeByRecoveryId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> recoveryIdByReservedEdge = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CompletableFuture<RewireMessage>> pendingReplies = new ConcurrentHashMap<>();

    /**
     * Constructor for the rewiring coordinator. Also refresh the health state for
     * the first time.
     * 
     * @param localAddress
     * @param connectionManager
     * @param topologyCache
     * @param udpCoordinator
     * @see RewiringCoordinator#refreshHealthState()
     */
    public RewiringCoordinator(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            NetworkTopologyCache topologyCache,
            UdpCoordinator udpCoordinator) {

        this.localAddress = Objects.requireNonNull(localAddress);
        this.connectionManager = Objects.requireNonNull(connectionManager);
        this.topologyCache = Objects.requireNonNull(topologyCache);
        this.udpCoordinator = Objects.requireNonNull(udpCoordinator);

        refreshHealthState();
    }

    /**
     * Starts the rewiring coordinator. Registers with the UDP coordinator, when
     * receiving a RECOVERY message, will handle it, see if it is a Rewire message.
     */
    public void start() {
        udpCoordinator.registerRecoveryConsumer(envelope -> {
            try {
                RewireMessage message = RewireMessage.decode(envelope.payload());
                handle(message);
            } catch (Exception ignored) {
                /*
                 * Not every RECOVERY packet is a RewireMessage.
                 * DEFICIENT messages can still be handled elsewhere.
                 */
            }
        });
    }

    public boolean attemptRewiring(NodeAddress defA, NodeAddress defB) {
        if (!localAddress.nodeId().equals(defA.nodeId())) {
            return false;
        }

        if (defA.nodeId().equals(defB.nodeId())) {
            Console.log("[REWIRE] Refused: defA and defB are the same node.");
            return false;
        }

        if (!canStartDeficientRecovery()) {
            Console.log("[REWIRE] Refused: local node is not eligible to start deficient recovery.");
            return false;
        }

        try {
            if (!connectionManager.containsNode(defB.nodeId())) {
                boolean directOk = tryDirectRepair(defA, defB);

                if (directOk) {
                    Console.log("[REWIRE] Direct repair succeeded: "
                            + defA.nodeId()
                            + " <-> "
                            + defB.nodeId());
                    return true;
                }
            }

            return runFullRewiring(defA, defB);
        } finally {
            clearDeficientRecoveryRoleAfterAttempt();
        }
    }

    private boolean runFullRewiring(NodeAddress defA, NodeAddress defB) {
        String requestId = UUID.randomUUID().toString();

        List<NodeAddress> defANeighborsAtRequestTime = connectionManager.neighborAddresses();

        RewireMessage req = RewireMessage.of(RecoveryMessageType.REWIRE_SESSION_REQ, requestId, localAddress, defA,
                defB, null, null, null, null, defANeighborsAtRequestTime, List.of(), null);

        RewireMessage ack;

        markInitiatingDeficientRequest(true);
        try {
            ack = sendAndWait(defB, req, REQUEST_TIMEOUT);
        } finally {
            /*
             * During timeout/backoff, this node may accept incoming requests again.
             */
            markInitiatingDeficientRequest(false);
        }

        if (ack == null || ack.type() == RecoveryMessageType.REWIRE_DENY) {
            randomBackoff();
            return false;
        }

        if (ack.type() != RecoveryMessageType.REWIRE_SESSION_ACK) {
            return false;
        }

        // Not the leader -> skip
        if (!localAddress.nodeId().equals(electLeader(defA, defB).nodeId())) {
            return false;
        }

        String recoveryId = UUID.randomUUID().toString();

        // Tries to become the leader. If can't -> skip
        if (!becomeDeficientLeader(recoveryId)) {
            return false;
        }

        List<NodeAddress> defANeighbors = connectionManager.neighborAddresses();
        List<NodeAddress> defBNeighbors = ack.defBNeighbors();

        RewireMessage commit = RewireMessage.of(
                RecoveryMessageType.REWIRE_SESSION_COMMIT,
                recoveryId,
                localAddress,
                defA, defB, null, null,
                null, null,
                defANeighbors, defBNeighbors,
                RewireStatus.ACCEPTED);

        sendOnly(defB, commit);

        List<NodeAddress> candidateCs = selectCandidateCs(defANeighbors, defBNeighbors);

        if (candidateCs.isEmpty()) {
            Console.log("[REWIRE] No valid C candidate. "
                    + "Every neighbor of defA may already be neighbor of defB.");
            return false;
        }

        for (NodeAddress c : candidateCs) {
            RewireMessage query = RewireMessage.of(
                    RecoveryMessageType.NEIGHBORS_QUERY,
                    recoveryId,
                    localAddress,
                    defA, defB, c, null,
                    null, null,
                    defANeighbors, defBNeighbors,
                    null);

            RewireMessage response = sendAndWait(c, query, REQUEST_TIMEOUT);

            if (response == null || response.status() != RewireStatus.ACCEPTED || response.nodeD() == null) {
                continue;
            }

            NodeAddress d = response.nodeD();

            boolean committed = executeScheme(recoveryId, defA, defB, c, d);

            if (committed) {
                Console.log("[REWIRE] Node "
                        + defA.nodeId()
                        + " successfully rewired with "
                        + d.nodeId()
                        + " while breaking edge "
                        + c.nodeId()
                        + "-"
                        + d.nodeId());
                return true;
            }
        }

        return false;
    }

    private boolean tryDirectRepair(NodeAddress defA, NodeAddress defB) {
        String recoveryId = UUID.randomUUID().toString();

        RewireMessage request = RewireMessage.of(
                RecoveryMessageType.REWIRE_REQ_DIRECT,
                recoveryId,
                localAddress,
                defA,
                defB,
                null,
                null,
                null,
                null,
                connectionManager.neighborAddresses(),
                List.of(),
                null);

        RewireMessage response = sendAndWait(defB, request, REQUEST_TIMEOUT);

        if (response == null || response.status() != RewireStatus.ACCEPTED) {
            return false;
        }

        boolean localApplied = connectionManager.addIfSpace(defB, "direct rewiring recovery commit");

        if (!localApplied) {
            return false;
        }

        RewireMessage commit = RewireMessage.of(
                RecoveryMessageType.REWIRE_DIRECT_COMMIT,
                recoveryId,
                localAddress,
                defA,
                defB,
                null,
                null,
                defB,
                null,
                connectionManager.neighborAddresses(),
                List.of(),
                RewireStatus.ACCEPTED);

        sendOnly(defB, commit);
        refreshHealthState();
        return true;
    }

    /**
     * Executes the rewiring scheme.
     *
     * <p>
     * Upon receiving a successful status from {@code C}, {@code defA}
     * performs the following atomic topology handoff:
     *
     * <pre>
     * Before: C <-> D
     * After : defA <-> D, defB <-> C
     * </pre>
     *
     * <p>
     * {@code defA} concurrently dispatches three
     * {@code REWIRE_SCHEME} packets:
     *
     * <ul>
     * <li>To {@code C}: {@code connectsTo=defB},
     * {@code disconnectsFrom=D}</li>
     * <li>To {@code D}: {@code connectsTo=defA},
     * {@code disconnectsFrom=C}</li>
     * <li>To {@code defB}: {@code connectsTo=C}</li>
     * </ul>
     *
     * <p>
     * {@code defA} updates its local neighbor list to include
     * {@code D}. All participating nodes update their topology state and
     * return to {@code FREE} unless concurrently acting as a
     * {@code REWIRING_NODE} in another independent recovery session.
     *
     * @param recoveryId the recovery session identifier
     * @param defA       the initiating deficient node
     * @param defB       the partner deficient node
     * @param c          the rewiring node currently connected to {@code d}
     * @param d          the rewiring node currently connected to {@code c}
     * @return {@code true} if the rewiring completes successfully;
     *         otherwise {@code false}
     */
    private boolean executeScheme(String recoveryId, NodeAddress defA, NodeAddress defB, NodeAddress c, NodeAddress d) {

        RewireMessage toC = RewireMessage.of(RecoveryMessageType.REWIRE_SCHEME_REQ, recoveryId, localAddress, defA,
                defB,
                c, d, defB, d, List.of(), List.of(), null);

        RewireMessage toD = RewireMessage.of(RecoveryMessageType.REWIRE_SCHEME_REQ, recoveryId, localAddress, defA,
                defB,
                c, d, defA, c, List.of(), List.of(), null);

        RewireMessage toB = RewireMessage.of(RecoveryMessageType.REWIRE_SCHEME_REQ, recoveryId, localAddress, defA,
                defB,
                c, d, c, null, List.of(), List.of(), null);

        CompletableFuture<RewireMessage> ackC = sendAsync(c, toC);
        CompletableFuture<RewireMessage> ackD = sendAsync(d, toD);
        CompletableFuture<RewireMessage> ackB = sendAsync(defB, toB);

        try {
            RewireMessage cAck = ackC.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RewireMessage dAck = ackD.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RewireMessage bAck = ackB.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            boolean allRemoteAccepted = accepted(cAck) && accepted(dAck) && accepted(bAck);

            if (!allRemoteAccepted) {
                Console.log("[REWIRE] Scheme aborted. At least one participant rejected.");

                // TODO: Then what? Do we handle this? Maybe some connections has been made but
                // not all?

                return false;
            }

            boolean localApplied = connectionManager.addIfSpace(d, "rewiring scheme commit: defA connects to D");

            if (!localApplied) {
                Console.log("[REWIRE] Scheme failed locally. defA could not add D.");
                return false;
            }

            sendOnly(c, schemeCommit(recoveryId, defA, defB, c, d, defB, d));
            sendOnly(d, schemeCommit(recoveryId, defA, defB, c, d, defA, c));
            sendOnly(defB, schemeCommit(recoveryId, defA, defB, c, d, c, null));

            releaseDeficientRoleIfMatching(recoveryId);
            refreshHealthState();

            Console.log(
                    "[REWIRE] Scheme committed. " + defA.nodeId() + " connected to " + d.nodeId() + ", " + defB.nodeId()
                            + " connected to " + c.nodeId() + ", edge " + c.nodeId() + "-" + d.nodeId() + " removed.");

            return true;
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            Console.log("[REWIRE] Scheme failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Handles the received rewire message.
     * <p>
     * Flow: Removes the reply from the pending replies. Then, depending on the
     * reply type, pass the message to the corresponding handle functions.
     * 
     * @param message the rewire message
     */
    private void handle(RewireMessage message) {
        if (message == null || message.type() == null) {
            return;
        }

        if (isReply(message.type())) {
            CompletableFuture<RewireMessage> pending = pendingReplies
                    .remove(replyKey(message.recoveryId(), message.sender()));

            if (pending != null) {
                pending.complete(message);
                return;
            }
        }

        switch (message.type()) {
            case REWIRE_REQ_DIRECT -> handleDirectRequest(message);
            case REWIRE_DIRECT_COMMIT -> handleDirectCommit(message);
            case REWIRE_SESSION_REQ -> handleSessionRequest(message);
            case REWIRE_SESSION_COMMIT -> handleSessionCommit(message);
            case REWIRE_REQ -> handleSessionRequest(message);
            case COMMIT_ACK -> handleSessionCommit(message);
            case NEIGHBORS_QUERY -> handleNeighborsQuery(message);
            case REWIRING_PROPOSE -> handleRewiringPropose(message);
            case REWIRE_SCHEME_REQ -> handleRewireSchemeRequest(message);
            case REWIRE_SCHEME_COMMIT -> handleRewireSchemeCommit(message);
            case REWIRE_SCHEME -> handleRewireSchemeCommit(message);
            default -> {
            }
        }
    }

    /**
     * Handles when the node receives a REWIRE_REQ_DIRECT. If the node can accept
     * the sender as a neighbor, sends a REWIRE_ACK back to the sender.
     * 
     * @param message the REWIRE_REQ_DIRECT message
     */
    private void handleDirectRequest(RewireMessage message) {
        boolean accepted = canAcceptDeficientRequest()
                && !connectionManager.containsNode(message.sender().nodeId())
                && connectionManager.size() < connectionManager.getMaxNeighbors();

        refreshHealthState();

        sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_REQ_DIRECT_ACK, accepted, null));
    }

    private void handleDirectCommit(RewireMessage message) {
        if (message.status() != RewireStatus.ACCEPTED) {
            return;
        }

        boolean accepted = connectionManager.addIfSpace(message.sender(), "direct rewiring recovery commit");

        refreshHealthState();

        Console.log("[REWIRE][COMMIT] Direct repair commit from "
                + message.sender().nodeId()
                + " accepted="
                + accepted
                + ", recoveryId="
                + message.recoveryId());
    }

    /**
     * Handles when the node receives a REWIRE_REQ. If the node can accept the
     * deficient node as a neighbor, sends back REWIRE_ACK, otherwise, REWIRE_DENY.
     * 
     * @param message the REWIRE_REQ message
     */
    private void handleSessionRequest(RewireMessage message) {
        if (!canAcceptSessionRequest(message)) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_DENY, false, null));
            return;
        }

        NodeAddress leader = electLeader(message.defA(), message.defB());

        if (!leader.nodeId().equals(message.sender().nodeId())) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_DENY, false, null));
            return;
        }

        List<NodeAddress> defANeighbors = message.defANeighbors();
        List<NodeAddress> defBNeighbors = connectionManager.neighborAddresses();

        /*
         * Rare edge case:
         * Phase 3 needs a C such that:
         *
         * C is neighbor of defA
         * C is NOT neighbor of defB
         *
         * If every neighbor of A is already also neighbor of B,
         * then no valid C exists, so B rejects early.
         */
        if (allNeighborsOfAExistInNeighborsOfB(defANeighbors, defBNeighbors)) {
            Console.log("[REWIRE] Denied REWIRE_REQ from "
                    + message.sender().nodeId()
                    + " because every neighbor of defA is already also neighbor of defB. "
                    + "No valid C exists.");

            sendOnly(message.sender(), RewireMessage.of(
                    RecoveryMessageType.REWIRE_DENY,
                    message.recoveryId(),
                    localAddress,
                    message.defA(),
                    message.defB(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    defBNeighbors,
                    RewireStatus.REFUSED));

            return;
        }

        RewireMessage response = RewireMessage.of(
                RecoveryMessageType.REWIRE_SESSION_ACK,
                message.recoveryId(),
                localAddress,
                message.defA(),
                message.defB(),
                null,
                null,
                null,
                null,
                List.of(),
                defBNeighbors,
                RewireStatus.ACCEPTED);

        sendOnly(message.sender(), response);
    }

    private void handleSessionCommit(RewireMessage message) {
        if (message.status() != RewireStatus.ACCEPTED) {
            return;
        }

        boolean accepted = becomeDeficientFellow(message.recoveryId());

        if (!accepted) {
            Console.log("[REWIRE] Cannot become DEFICIENT_FELLOW for recoveryId="
                    + message.recoveryId());
        }
    }

    private void handleNeighborsQuery(RewireMessage message) {
        NodeAddress d = findCandidateD(
                message.nodeC(),
                message.defANeighbors(),
                message.defBNeighbors());

        if (d == null) {
            sendOnly(
                    message.sender(),
                    reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            return;
        }

        boolean reservedByC = tryBecomeRewiringNode(
                message.recoveryId(),
                message.nodeC(),
                d,
                message.defB(),
                d);

        if (!reservedByC) {
            sendOnly(
                    message.sender(),
                    reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            return;
        }

        RewireMessage proposal = RewireMessage.of(
                RecoveryMessageType.REWIRING_PROPOSE,
                message.recoveryId(),
                localAddress,
                message.defA(),
                message.defB(),
                message.nodeC(),
                d,
                message.defA(),
                message.nodeC(),
                message.defANeighbors(),
                message.defBNeighbors(),
                null);

        RewireMessage dAck = sendAndWait(d, proposal, REQUEST_TIMEOUT);

        if (dAck == null || dAck.status() != RewireStatus.ACCEPTED) {
            releaseRewiringReservation(message.recoveryId());

            sendOnly(
                    message.sender(),
                    reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            return;
        }

        sendOnly(
                message.sender(),
                reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, true, d));
    }

    private void handleRewiringPropose(RewireMessage message) {
        boolean valid = message.nodeC() != null
                && message.nodeD() != null
                && connectionManager.containsNode(message.nodeC().nodeId())
                && tryBecomeRewiringNode(
                        message.recoveryId(),
                        message.nodeC(),
                        message.nodeD(),
                        message.connectsTo(),
                        message.disconnectsFrom());

        sendOnly(
                message.sender(),
                reply(message, RecoveryMessageType.REWIRING_PROPOSE_ACK, valid, message.nodeD()));
    }

    private void handleRewireSchemeRequest(RewireMessage message) {
        boolean accepted = canAcceptSchemeRequest(message);

        sendOnly(
                message.sender(),
                reply(message, RecoveryMessageType.REWIRE_SCHEME_ACK, accepted, null));
    }

    private void handleRewireSchemeCommit(RewireMessage message) {
        boolean accepted = connectionManager.applyRewireScheme(
                message.recoveryId(),
                message.connectsTo(),
                message.disconnectsFrom(),
                "rewiring scheme commit from " + message.sender().nodeId());

        releaseRewiringReservation(message.recoveryId());
        releaseDeficientRoleIfMatching(message.recoveryId());
        refreshHealthState();

        Console.log("[REWIRE][COMMIT] Scheme commit from "
                + message.sender().nodeId()
                + " accepted="
                + accepted
                + ", connectsTo="
                + message.connectsTo()
                + ", disconnectsFrom="
                + message.disconnectsFrom()
                + ", recoveryId="
                + message.recoveryId());
    }

    /**
     * For a candidate node C, finds a candidate node D. Requirements: D is a
     * neighbor of C and B. D is NOT a neighbor of A.
     * 
     * @param c             the C node
     * @param defANeighbors neighbors of A
     * @param defBNeighbors neighbors of B
     * @return
     */
    private NodeAddress findCandidateD(NodeAddress c, List<NodeAddress> defANeighbors,
            List<NodeAddress> defBNeighbors) {

        if (c == null) {
            return null;
        }

        // BUG: This doesn't seem to be the list for neighbors of C? It seems to be
        // neighbors of A (the node calling this method(?))
        List<NodeAddress> localNeighborsOfC = new ArrayList<>(connectionManager.neighborAddresses());
        Collections.shuffle(localNeighborsOfC);

        for (NodeAddress d : localNeighborsOfC) {
            if (d == null) {
                continue;
            }

            if (d.nodeId().equals(c.nodeId())) {
                continue;
            }

            boolean dNotInDefA = !contains(defANeighbors, d.nodeId());
            boolean cAndDAdjacent = connectionManager.containsNode(d.nodeId());

            if (dNotInDefA && cAndDAdjacent) {
                return d;
            }
        }

        return null;
    }

    /**
     * Selects neighbors of A that are candidates for C node. Requirements: C is not
     * a neighbor of defA.
     * 
     * @param defANeighbors neighbors of A
     * @param defBNeighbors neighbors of B
     * @return a list of candidates for node C
     */
    private List<NodeAddress> selectCandidateCs(List<NodeAddress> defANeighbors, List<NodeAddress> defBNeighbors) {

        List<NodeAddress> result = defANeighbors.stream()
                .filter(c -> !contains(defBNeighbors, c.nodeId()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        Collections.shuffle(result);
        return result;
    }

    /**
     * If a node is DEFICIENT and FREE, then it can start deficient recovery.
     * 
     * @return whether the node pass the conditions
     */
    private boolean canStartDeficientRecovery() {
        roleLock.lock();
        try {
            refreshHealthState();

            if (healthState != HealthState.DEFICIENT) {
                return false;
            }

            return recoveryRole == RecoveryRole.FREE;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Attempts to set the local node as the DEFICIENT_LEADER.
     * 
     * @param recoveryId the recovery ID
     * @return success or not
     */
    private boolean becomeDeficientLeader(String recoveryId) {
        roleLock.lock();
        try {
            refreshHealthState();
            if (healthState != HealthState.DEFICIENT) {
                return false;
            }

            if (recoveryRole != RecoveryRole.FREE) {
                return false;
            }

            recoveryRole = RecoveryRole.DEFICIENT_LEADER;
            activeDeficientRecoveryId = recoveryId;

            return true;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Attempts to set the local node as the DEFICIENT_FELLOW.
     * 
     * @param recoveryId the recovery ID
     * @return success or not
     */
    private boolean becomeDeficientFellow(String recoveryId) {
        roleLock.lock();
        try {
            refreshHealthState();

            if (healthState != HealthState.DEFICIENT) {
                return false;
            }

            if (recoveryRole != RecoveryRole.FREE) {
                return false;
            }

            recoveryRole = RecoveryRole.DEFICIENT_FELLOW;
            activeDeficientRecoveryId = recoveryId;

            return true;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * If a node is DEFICIENT and FREE and is NOT initiating any deficient request,
     * then it can accept deficient requests.
     * 
     * @return whether the node pass the conditions
     */
    private boolean canAcceptDeficientRequest() {
        roleLock.lock();
        try {
            refreshHealthState();

            if (initiatingDeficientRequest) {
                return false;
            }

            if (healthState != HealthState.DEFICIENT) {
                return false;
            }

            return recoveryRole == RecoveryRole.FREE;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean canAcceptSessionRequest(RewireMessage message) {
        roleLock.lock();
        try {
            refreshHealthState();

            if (healthState != HealthState.DEFICIENT) {
                return false;
            }

            if (recoveryRole != RecoveryRole.FREE) {
                return false;
            }

            NodeAddress leader = electLeader(message.defA(), message.defB());

            if (!leader.nodeId().equals(message.sender().nodeId())) {
                return false;
            }

            if (initiatingDeficientRequest && leader.nodeId().equals(localAddress.nodeId())) {
                return false;
            }

            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean canAcceptSchemeRequest(RewireMessage message) {
        roleLock.lock();
        try {
            if (recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {
                return Objects.equals(activeDeficientRecoveryId, message.recoveryId())
                        && message.connectsTo() != null
                        && message.disconnectsFrom() == null;
            }

            if (recoveryRole != RecoveryRole.REWIRING_NODE) {
                return false;
            }

            String reservedEdge = reservedEdgeByRecoveryId.get(message.recoveryId());

            if (reservedEdge == null) {
                return false;
            }

            return isDegreeNeutralSwap(message.connectsTo(), message.disconnectsFrom());
        } finally {
            roleLock.unlock();
        }
    }

    private boolean tryBecomeRewiringNode(String recoveryId, NodeAddress c, NodeAddress d, NodeAddress connectsTo,
            NodeAddress disconnectsFrom) {

        roleLock.lock();
        try {
            refreshHealthState();

            /*
             * DEFICIENT_LEADER and DEFICIENT_FELLOW are exclusive.
             * They cannot become REWIRING_NODE in another session.
             */
            if (recoveryRole == RecoveryRole.DEFICIENT_LEADER
                    || recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {
                return false;
            }

            /*
             * Both DEFICIENT and SUFFICIENT nodes may become REWIRING_NODE,
             * but only if the operation is degree-neutral.
             */
            if (!isDegreeNeutralSwap(connectsTo, disconnectsFrom)) {
                return false;
            }

            String edgeKey = edgeKey(c, d);
            String existingRecoveryId = recoveryIdByReservedEdge.putIfAbsent(edgeKey, recoveryId);

            if (existingRecoveryId != null && !existingRecoveryId.equals(recoveryId)) {
                return false;
            }

            reservedEdgeByRecoveryId.put(recoveryId, edgeKey);
            recoveryRole = RecoveryRole.REWIRING_NODE;

            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private void releaseRewiringReservation(String recoveryId) {
        roleLock.lock();
        try {
            String edgeKey = reservedEdgeByRecoveryId.remove(recoveryId);

            if (edgeKey != null) {
                recoveryIdByReservedEdge.remove(edgeKey, recoveryId);
            }

            if (reservedEdgeByRecoveryId.isEmpty()
                    && recoveryRole == RecoveryRole.REWIRING_NODE) {
                recoveryRole = RecoveryRole.FREE;
            }

            refreshHealthState();
        } finally {
            roleLock.unlock();
        }
    }

    private void clearDeficientRecoveryRoleAfterAttempt() {
        roleLock.lock();
        try {
            refreshHealthState();

            if (recoveryRole == RecoveryRole.DEFICIENT_LEADER
                    || recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {

                recoveryRole = RecoveryRole.FREE;
                activeDeficientRecoveryId = null;
            }

            Console.log("[REWIRE] Recovery attempt ended. healthState="
                    + healthState
                    + ", recoveryRole="
                    + recoveryRole
                    + ", neighbors="
                    + connectionManager.size()
                    + "/"
                    + connectionManager.getMaxNeighbors());
        } finally {
            roleLock.unlock();
        }
    }

    // TODO: Not yet understand
    private void releaseDeficientRoleIfMatching(String recoveryId) {
        roleLock.lock();
        try {
            if (Objects.equals(activeDeficientRecoveryId, recoveryId)) {
                recoveryRole = RecoveryRole.FREE;
                activeDeficientRecoveryId = null;
            }

            refreshHealthState();
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Gets the current health state: deficient or sufficient.
     * 
     * @return the health state
     */
    private HealthState currentHealthState() {
        return connectionManager.size() < connectionManager.getMaxNeighbors()
                ? HealthState.DEFICIENT
                : HealthState.SUFFICIENT;
    }

    /**
     * Sets the current health state.
     */
    private void refreshHealthState() {
        healthState = currentHealthState();
    }

    /**
     * If the rewiring requires both connecting to another node and disconnecting
     * from another node then it is considered neutral.
     * 
     * @param connectsTo      the connecting-to node
     * @param disconnectsFrom the disconnect-from node
     * @return true if both the connecting-to and the disconnecting-from node are
     *         both not null
     */
    private boolean isDegreeNeutralSwap(NodeAddress connectsTo, NodeAddress disconnectsFrom) {
        return connectsTo != null && disconnectsFrom != null;
    }

    /**
     * Sets whether the node is initiating a DEFICIENT request.
     * 
     * @param value boolean to set
     */
    private void markInitiatingDeficientRequest(boolean value) {
        roleLock.lock();
        try {
            initiatingDeficientRequest = value;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Checks if all neighbors of A exists in neighbors of B.
     * 
     * @param defANeighbors
     * @param defBNeighbors
     * @return
     */
    private boolean allNeighborsOfAExistInNeighborsOfB(
            List<NodeAddress> defANeighbors,
            List<NodeAddress> defBNeighbors) {

        if (defANeighbors == null || defANeighbors.isEmpty()) {
            return true;
        }

        for (NodeAddress neighborOfA : defANeighbors) {
            if (!contains(defBNeighbors, neighborOfA.nodeId())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates an edge key between node A and ndoe B.
     * 
     * @param a
     * @param b
     * @return A--B if A < B, or B--A if B < A
     */
    private String edgeKey(NodeAddress a, NodeAddress b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Cannot create edge key from null node.");
        }

        List<String> ids = new ArrayList<>();
        ids.add(a.nodeId());
        ids.add(b.nodeId());
        ids.sort(Comparator.naturalOrder());

        return ids.get(0) + "--" + ids.get(1);
    }

    /**
     * Simple leader election: the larger node ID (lexical order) is the leader.
     * 
     * @param a node A
     * @param b node B
     * @return the leader
     */
    private NodeAddress electLeader(NodeAddress a, NodeAddress b) {
        return a.nodeId().compareTo(b.nodeId()) >= 0 ? a : b;
    }

    /**
     * Returns a reply message.
     * 
     * @param request    the original message
     * @param replyType  the message reply type
     * @param accepted   whether the original request is accepted
     * @param candidateD candidate D?
     * @return
     *         the reply message
     */
    private RewireMessage reply(RewireMessage request, RecoveryMessageType replyType, boolean accepted,
            NodeAddress candidateD) {

        return RewireMessage.of(
                replyType,
                request.recoveryId(),
                localAddress,
                request.defA(),
                request.defB(),
                request.nodeC(),
                candidateD == null ? request.nodeD() : candidateD,
                null,
                null,
                List.of(),
                connectionManager.neighborAddresses(),
                accepted ? RewireStatus.ACCEPTED : RewireStatus.REFUSED);
    }

    private RewireMessage schemeCommit(
            String recoveryId,
            NodeAddress defA,
            NodeAddress defB,
            NodeAddress c,
            NodeAddress d,
            NodeAddress connectsTo,
            NodeAddress disconnectsFrom) {

        return RewireMessage.of(
                RecoveryMessageType.REWIRE_SCHEME_COMMIT,
                recoveryId,
                localAddress,
                defA,
                defB,
                c,
                d,
                connectsTo,
                disconnectsFrom,
                List.of(),
                List.of(),
                RewireStatus.ACCEPTED);
    }

    /**
     * Sends a message after putting it into the pending replies list. Then waits
     * until receives a response.
     * 
     * @param target  the target node
     * @param message the rewire message
     * @param timeout timeout
     * @return the response rewire message
     */
    private RewireMessage sendAndWait(NodeAddress target, RewireMessage message, Duration timeout) {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            CompletableFuture<RewireMessage> future = new CompletableFuture<>();
            String key = replyKey(message.recoveryId(), target);

            pendingReplies.put(key, future);

            try {
                sendOnly(target, message);
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException exception) {
                pendingReplies.remove(key);
            }
        }

        return null;
    }

    /**
     * Sends async message and waits for response. (I think this means you don't
     * have to send the message right now, it will be sent later.)
     * 
     * @param target  the target node
     * @param message the rewire message
     * @return the response rewire message
     */
    private CompletableFuture<RewireMessage> sendAsync(NodeAddress target, RewireMessage message) {
        return CompletableFuture.supplyAsync(() -> sendAndWait(target, message, REQUEST_TIMEOUT));
    }

    /**
     * Sends a message without waiting for any response.
     * 
     * @param target  the target node
     * @param message the message
     */
    private void sendOnly(NodeAddress target, RewireMessage message) {
        try {
            Console.log("[REWIRE]" + stageLabel(message.type())
                    + " "
                    + message.type()
                    + " recoveryId="
                    + message.recoveryId()
                    + " to "
                    + target.nodeId());
            udpCoordinator.send(target.host(), target.port(), UdpPacketType.RECOVERY, message.encode());
        } catch (IOException exception) {
            Console.log("[REWIRE] Failed to send " + message.type() + " to " + target + ": " + exception.getMessage());
        }
    }

    /**
     * Checks if the message is an ACCEPTED rewire message.
     * 
     * @param message the require message
     * @return whether the request is ACCEPTED
     */
    private boolean accepted(RewireMessage message) {
        return message != null && message.status() == RewireStatus.ACCEPTED;
    }

    /**
     * Checks if the list of addresses contains a node.
     * 
     * @param addresses the list of addresses
     * @param nodeId    the node
     * @return whether the list contains the node
     */
    private boolean contains(List<NodeAddress> addresses, String nodeId) {
        if (addresses == null || nodeId == null) {
            return false;
        }

        return addresses.stream()
                .anyMatch(address -> address != null && address.nodeId().equals(nodeId));
    }

    /**
     * Checks if the message type is a reply message.
     * 
     * @param type the recovery message type
     * @return whether the message type is a reply message
     */
    private boolean isReply(RecoveryMessageType type) {
        return type == RecoveryMessageType.REWIRE_REQ_DIRECT_ACK
                || type == RecoveryMessageType.REWIRE_SESSION_ACK
                || type == RecoveryMessageType.REWIRE_ACK
                || type == RecoveryMessageType.REWIRE_DENY
                || type == RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE
                || type == RecoveryMessageType.REWIRING_PROPOSE_ACK
                || type == RecoveryMessageType.REWIRE_SCHEME_ACK;
    }

    private String replyKey(String recoveryId, NodeAddress sender) {
        return recoveryId + ":" + (sender == null ? "" : sender.nodeId());
    }

    private String stageLabel(RecoveryMessageType type) {
        if (type == null) {
            return "";
        }

        return switch (type) {
            case REWIRE_REQ_DIRECT, REWIRE_SESSION_REQ, REWIRE_SCHEME_REQ, NEIGHBORS_QUERY, REWIRING_PROPOSE,
                    REWIRE_REQ, REWIRE_SCHEME ->
                "[REQ]";
            case REWIRE_REQ_DIRECT_ACK, REWIRE_SESSION_ACK, REWIRE_SCHEME_ACK, NEIGHBORS_QUERY_RESPONSE,
                    REWIRING_PROPOSE_ACK, REWIRE_ACK, REWIRE_DENY ->
                "[ACK]";
            case REWIRE_DIRECT_COMMIT, REWIRE_SESSION_COMMIT, REWIRE_SCHEME_COMMIT, COMMIT_ACK ->
                "[COMMIT]";
            default -> "";
        };
    }

    /**
     * Random backoff time.
     */
    private void randomBackoff() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(250, 1000));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
