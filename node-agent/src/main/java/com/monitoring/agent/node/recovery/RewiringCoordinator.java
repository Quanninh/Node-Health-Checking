package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.node.transport.UdpCoordinator;
import com.monitoring.agent.node.transport.UdpPacketType;
import com.monitoring.agent.util.Console;

public final class RewiringCoordinator {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(2_500);
    private static final Duration SCHEME_COMMIT_TIMEOUT = Duration.ofMillis(3_000);
    private static final int SEND_ATTEMPTS = 3;

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final NetworkTopologyCache topologyCache;
    private final UdpCoordinator udpCoordinator;

    private final ReentrantLock roleLock = new ReentrantLock();

    private volatile RecoveryRole recoveryRole = RecoveryRole.FREE;
    private volatile String activeDeficientRecoveryId;

    /**
     * Used while this node is sending REWIRE_REQ and waiting for reply.
     * <p>
     * Requirement:
     * <p>
     * While defA is waiting for REWIRE_ACK, if it receives another REWIRE_REQ,
     * it must deny immediately.
     */
    private volatile boolean isInitiatingDeficientRequest = false;

    /**
     * One node may be REWIRING_NODE for multiple recovery sessions,
     * but the exact same edge C-D cannot be reserved by two recoveryIds.
     */
    // private final ConcurrentHashMap<String, String> reservedEdgeByRecoveryId =
    // new ConcurrentHashMap<>();
    /**
     * Maps a reserved edge to the recovery ID that reserves it. Since one recovery
     * ID can reserves multiple edges (shouldn't happen though), but one edge can
     * only be reserved by one recovery ID, the edge is the key.
     */
    private final ConcurrentHashMap<String, String> recoveryIdByReservedEdge = new ConcurrentHashMap<>();

    /** Maps a RecoveryID with a RewireMessage CompletableFuture. */
    private final ConcurrentHashMap<String, CompletableFuture<RewireMessage>> pendingReplies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingSchemeCommits = new ConcurrentHashMap<>();
    private final Set<String> expiredSchemeCommits = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService schemeCommitTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "Rewire-Scheme-Commit-Timeout");
                thread.setDaemon(false);
                return thread;
            });

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
        udpCoordinator.registerRewiringConsumer(envelope -> {
            RewireMessage message = RewireMessage.decode(envelope.payload());
            handle(message);
        });
    }

    /**
     * This function is called when the node receives a deficient record from
     * another node. The local node will marks the other node as deficient, and
     * attempts rewiring with that node.
     * 
     * @param record the deficient node record
     */
    public void onDeficientNodeDiscovered(DeficientNodeRecord record) {
        if (record == null || record.node() == null) {
            Console.log("[REWIRE] Record is invalid");
            return;
        }

        // topologyCache.markDeficient(record);
        refreshHealthState();

        NodeAddress deficientNode = record.node();

        if (deficientNode.nodeId().equals(localAddress.nodeId())
                || connectionManager.getHealthState() != HealthState.DEFICIENT) {
            Console.log("[REWIRE] Deficient node is self or I am healthy -> skip");
            topologyCache.clearDeficient(deficientNode);
            return;
        }

        // boolean repaired =
        attemptRewiring(localAddress, deficientNode);

        // if (repaired) {
        // topologyCache.clearDeficient(localAddress);
        topologyCache.clearDeficient(deficientNode);
        // }
    }

    /**
     * Attempts to rewires the two deficient nodes.
     * <p>
     * If the two nodes are not neighbors, attempts a direct repair.
     * <p>
     * Else, attempts a full rewiring.
     * 
     * @param defA the deficient node A
     * @param defB the deficient node B
     * @return rewiring status
     * @see #tryDirectRepair(NodeAddress, NodeAddress)
     * @see #runFullRewiring(NodeAddress, NodeAddress)
     */
    public boolean attemptRewiring(NodeAddress defA, NodeAddress defB) {
        if (!localAddress.nodeId().equals(defA.nodeId())) {
            Console.log("[REWIRE] Self is not defA -> skip");
            return false;
        }

        if (defA.nodeId().equals(defB.nodeId())) {
            Console.log("[REWIRE] Refused: defA and defB are the same node.", Constant.ORANGE);
            return false;
        }

        if (!canStartDeficientRecovery()) {
            Console.log("[REWIRE] Refused: local node is not eligible to start deficient recovery.",
                    Constant.ORANGE);
            return false;
        }

        try {
            if (!connectionManager.containsNode(defB.nodeId())) {
                boolean directOk = tryDirectRepair(defA, defB);

                if (directOk) {
                    Console.log("[REWIRE] Direct repair succeeded: " + defA.nodeId() + " <-> " + defB.nodeId(),
                            Constant.GREEN);
                    return true;
                }
            }
            Console.log("[REWIRE] Direct repair with " + defA.nodeId() + " and " + defB.nodeId()
                    + " failed. Now run full rewiring", Constant.RED);

            return runFullRewiring(defA, defB);
        } finally {
            clearDeficientRecoveryRoleAfterAttempt();
        }
    }

    /**
     * Attempts a full rewiring between two deficient nodes. They will first
     * initiate the rewiring session by sending REWIRE_SESSION_REQ. This node will
     * try to get the LEADER role, because only the LEADER can continue the process.
     * 
     * @param defA
     * @param defB
     * @return
     */
    private boolean runFullRewiring(NodeAddress defA, NodeAddress defB) {
        String requestId = UUID.randomUUID().toString();

        List<NodeAddress> defANeighborsAtRequestTime = connectionManager.neighborAddresses();

        RewireMessage req = RewireMessage.of(RecoveryMessageType.REWIRE_SESSION_REQ, requestId, localAddress, defA,
                defB, null, null, null, null, defANeighborsAtRequestTime, List.of(), null);

        RewireMessage ack = null;

        markInitiatingDeficientRequest(true);
        try {
            ack = sendAsync(defB, req).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
        } finally {
            /*
             * During timeout/backoff, this node may accept incoming requests again.
             */
            markInitiatingDeficientRequest(false);
        }

        if (ack == null || ack.type() == RecoveryMessageType.REWIRE_DENY) {
            Console.log("[REWIRE] Denied, will now backoff random");
            randomBackoff();
            return false;
        }

        if (ack.type() != RecoveryMessageType.REWIRE_SESSION_ACK) {
            Console.log("[REWIRE] " + ack.type() + " is not REWIRE_SESSION_ACK");
            return false;
        }

        // Not the leader -> skip
        if (!localAddress.nodeId().equals(electLeader(defA, defB).nodeId())) {
            Console.log("[REWIRE] I'm not a leader -> skip");
            return false;
        }

        String recoveryId = UUID.randomUUID().toString();

        // Tries to become the leader. If can't -> skip
        if (!becomeDeficientLeader(recoveryId)) {
            Console.log("[REWIRE_DEBUG] Can't become a leader", Constant.ORANGE);
            return false;
        }

        Console.log("[REWIRE] I'm the leader - " + localAddress.nodeId(), Constant.BG_GREEN);

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

        Console.log("Candidate Cs: " + candidateCs);

        if (candidateCs.isEmpty()) {
            Console.log("[REWIRE] No valid C candidate. All neighbors of defA are neighbors of defB.");
            // B might still be DEFICIENT_FELLOW, expecting timeout to do its job
            releaseDeficientRoleIfMatching(recoveryId);
            refreshHealthState();
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

            // NEIGHBOR_QUERY_RESPONSE handled here
            RewireMessage response = null;
            try {
                response = sendAsync(c, query).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                // TODO Auto-generated catch block
                // e.printStackTrace();
            }

            if (response == null || response.status() != RewireStatus.ACCEPTED || response.nodeD() == null) {
                continue;
            }

            NodeAddress d = response.nodeD();

            boolean committed = executeScheme(recoveryId, defA, defB, c, d);

            if (committed) {
                Console.log("[REWIRE] " + defA.nodeId() + " rewired with " + d.nodeId()
                        + " while breaking edge " + c.nodeId() + "-x-" + d.nodeId(), Constant.GREEN);
                return true;
            }
        }

        Console.log("[REWIRE] None of the candidate C worked", Constant.RED);
        return false;
    }

    /**
     * Requests a direct rewire between the two deficient nodes. If they are not
     * already neighbors (not checked in this function), the two nodes will become
     * neighbors.
     * 
     * @param defA the deficient node A
     * @param defB the deficient node B
     * @return success?
     */
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

        RewireMessage response = null;
        try {
            response = sendAsync(defB, request).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            // TODO Auto-generated catch block
            // e.printStackTrace();
        }

        if (response == null || response.status() != RewireStatus.ACCEPTED) {
            Console.log(
                    "[REWIRE] response is null or not ACCEPTED != " + (response == null ? "null" : response.status()));
            return false;
        }

        boolean localApplied = connectionManager.addIfSpace(defB, "direct rewiring recovery commit");

        if (!localApplied) {
            Console.log("[REWIRE] Local applied failed");
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
        Console.log("Direct Repait completed successfully", Constant.GREEN);
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
        RewireMessage toC = RewireMessage.of(RecoveryMessageType.REWIRE_SCHEME_REQ, recoveryId, localAddress,
                defA, defB, c, d, defB, d, List.of(), List.of(), null);

        RewireMessage toD = RewireMessage.of(RecoveryMessageType.REWIRE_SCHEME_REQ, recoveryId, localAddress,
                defA, defB, c, d, defA, c, List.of(), List.of(), null);

        RewireMessage toB = RewireMessage.of(RecoveryMessageType.REWIRE_SCHEME_REQ, recoveryId, localAddress,
                defA, defB, c, d, c, null, List.of(), List.of(), null);

        CompletableFuture<RewireMessage> ackC = sendAsync(c, toC);
        CompletableFuture<RewireMessage> ackD = sendAsync(d, toD);
        CompletableFuture<RewireMessage> ackB = sendAsync(defB, toB);

        try {
            RewireMessage cAck = ackC.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RewireMessage dAck = ackD.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RewireMessage bAck = ackB.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            boolean allRemoteAccepted = accepted(cAck) && accepted(dAck) && accepted(bAck);

            if (!allRemoteAccepted) {
                Console.log("[REWIRE] Scheme aborted. At least one participant rejected.", Constant.BG_RED);
                // Rejected prematurely, no connections were made.
                // TODO: Send abort message to B, C and D. (I hope timeout deals with this)
                return false;
            }

            boolean localApplied = connectionManager.addIfSpace(d,
                    "rewiring scheme commit: defA connects to D");

            if (!localApplied) {
                Console.log("[REWIRE] Scheme failed locally. defA could not add D.", Constant.BG_RED);
                // TODO: Send abort message to B, C and D. (I hope timeout deals with this)
                releaseDeficientRoleIfMatching(recoveryId);
                refreshHealthState();
                return false;
            }

            // In the other nodes we trust.
            sendOnly(c, schemeCommit(recoveryId, defA, defB, c, d, defB, d));
            sendOnly(d, schemeCommit(recoveryId, defA, defB, c, d, defA, c));
            sendOnly(defB, schemeCommit(recoveryId, defA, defB, c, d, c, null));

            releaseDeficientRoleIfMatching(recoveryId);
            refreshHealthState();

            Console.log(
                    "[REWIRE] Scheme committed. " + defA.nodeId() + " connected to " + d.nodeId() + ", " + defB.nodeId()
                            + " connected to " + c.nodeId() + ", edge " + c.nodeId() + "-x-" + d.nodeId()
                            + " removed.",
                    Constant.GREEN);

            return true;
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            Console.log("[REWIRE] Scheme failed: " + exception.getMessage(), Constant.RED);
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
            Console.log("Message is invalid");
            return;
        }

        if (isReply(message.type())) {
            CompletableFuture<RewireMessage> pending = pendingReplies
                    .remove(replyKey(message.recoveryId(), message.sender()));

            if (pending != null) {
                pending.complete(message);
                Console.log("Idk what's going on here, but pending != null and pending.complete");
                return;
            }
        }

        switch (message.type()) {
            case REWIRE_REQ_DIRECT -> handleDirectRequest(message);
            case REWIRE_DIRECT_COMMIT -> handleDirectCommit(message);
            case REWIRE_SESSION_REQ -> handleSessionRequest(message);
            case REWIRE_SESSION_COMMIT -> handleSessionCommit(message);
            case REWIRE_REQ -> handleSessionRequest(message);
            // case COMMIT_ACK -> handleSessionCommit(message);
            case NEIGHBORS_QUERY -> handleNeighborsQuery(message);
            case REWIRING_PROPOSE -> handleRewiringPropose(message);
            case REWIRE_SCHEME_REQ -> handleRewireSchemeRequest(message);
            case REWIRE_SCHEME_COMMIT -> handleRewireSchemeCommit(message);
            // case REWIRE_SCHEME -> handleRewireSchemeCommit(message);
            default -> {
                Console.log("Unhandled " + message.type());
            }
        }
    }

    /**
     * Handles when the node receives a REWIRE_REQ_DIRECT. If the node can accept
     * the sender as a neighbor, sends a REWIRE_REQ_DIRECT_ACK back to the sender.
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
            Console.log("Message denied");
            return;
        }

        boolean accepted = connectionManager.addIfSpace(message.sender(), "direct rewiring recovery commit");

        refreshHealthState();

        Console.log("[REWIRE][COMMIT] Direct repair commit from " + message.sender().nodeId()
                + " accepted=" + accepted + ", recoveryId=" + message.recoveryId(), Constant.PINK);
    }

    /**
     * Handles when the node receives a REWIRE_REQ or REWIRE_SESSION_REQ. If the
     * node can accept the
     * deficient node as a neighbor, sends back REWIRE_ACK, otherwise, REWIRE_DENY.
     * 
     * @param message the REWIRE_REQ message
     */
    private void handleSessionRequest(RewireMessage message) {
        // defB
        if (!canAcceptSessionRequest(message)) {
            Console.log("REASON: !canAcceptSessionRequest(message)", Constant.RED);
            sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_DENY, false, null));
            return;
        }

        NodeAddress leader = electLeader(message.defA(), message.defB());

        // If i am not the leader
        if (!leader.nodeId().equals(message.sender().nodeId())) {
            Console.log("REASON:!leader.nodeId().equals(message.sender().nodeId())", Constant.RED);
            // TODO:
            sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_SESSION_ACK, true, null));
            return;
        }

        // I am the leader

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
                    + " because all neighbors of defA are neighbors of defB.", Constant.ORANGE);

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

    /**
     * Handles when the node receives a REWIRE_SESSION_COMMIT.
     * <p>
     * If the node returns an ACCEPTED response, that no
     * 
     * @param message the REWIRE_SESSION_COMMIT message
     */
    private void handleSessionCommit(RewireMessage message) {
        if (message.status() != RewireStatus.ACCEPTED) {
            Console.log("Wrong message");
            return;
        }

        boolean accepted = becomeDeficientFellow(message.recoveryId());

        if (!accepted) {
            Console.log("[REWIRE] Cannot become DEFICIENT_FELLOW for recoveryId=" + message.recoveryId(), Constant.RED);
        }

        scheduleSchemeCommitTimeout(message);
    }

    /**
     * When received a NEIGHBOR_QUERY message. Assume this is node C.
     * <p>
     * Node C finds a candidate node D which is a neighbor of C but not of A. Then,
     * C tries to become the rewiring node, which will connects to defB and
     * disconnects from D. If possible, C will sends a REWIRING_PROPOSE to D, and if
     * D accepts, C returns a NEIGHBOR_QUERY_ACK back to defA with D's address.
     * 
     * @param message the rewire message
     */
    private void handleNeighborsQuery(RewireMessage message) {
        Console.log("[REWIRE] Just received NEIGHBORS_QUERY", Constant.BG_ORANGE);
        NodeAddress d = findCandidateD(message.nodeC(), message.defANeighbors());

        if (d == null) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            Console.log("Sent NEIGHBORS_QUERY_RESPONSE false because can't find D");
            return;
        }

        boolean reservedByC = canBecomeRewiringNode(
                message.recoveryId(),
                message.nodeC(),
                d,
                message.defB(),
                d);

        if (!reservedByC) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            Console.log("Send NEIGHBORS_QUERY_RESPONSE false because C can't become wiring node");
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

        // REWIRING_PROPOSE_ACK received here
        RewireMessage dAck = null;
        try {
            dAck = sendAsync(d, proposal).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            // TODO Auto-generated catch block
            // e.printStackTrace();
        }

        if (dAck == null || dAck.status() != RewireStatus.ACCEPTED) {
            releaseRewiringReservation(message.recoveryId());

            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            Console.log("Send NEIGHBORS_QUERY_RESPONSE false because dAck is false");
            return;
        }

        Console.log("Send NEIGHBORS_QUERY_RESPONSE true");
        sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, true, d));
    }

    private void handleRewiringPropose(RewireMessage message) {
        Console.log("[REWIRE] Just received REWIRING_PROPOSE", Constant.BG_ORANGE);
        boolean valid = message.nodeC() != null && message.nodeD() != null
                && connectionManager.containsNode(message.nodeC().nodeId())
                && canBecomeRewiringNode(
                        message.recoveryId(),
                        message.nodeC(),
                        message.nodeD(),
                        message.connectsTo(),
                        message.disconnectsFrom());

        Console.log("message.nodeC() != null = " + (message.nodeC() != null)
                + ", message.nodeD() != null = " + (message.nodeD() != null)
                + ", contains nodeC = "
                + (message.nodeC() != null && connectionManager.containsNode(message.nodeC().nodeId()))
                + ", canBecomeRewiringNode = "
                + (message.nodeC() != null && message.nodeD() != null && canBecomeRewiringNode(
                        message.recoveryId(),
                        message.nodeC(),
                        message.nodeD(),
                        message.connectsTo(),
                        message.disconnectsFrom())),
                Constant.BG_PINK);

        sendOnly(
                message.sender(),
                reply(message, RecoveryMessageType.REWIRING_PROPOSE_ACK, valid, message.nodeD()));
        Console.log("[REWIRE] REWIRING_PROPOSE_ACK sent " + valid + " " + message.recoveryId());
    }

    private void handleRewireSchemeRequest(RewireMessage message) {
        boolean accepted = canAcceptSchemeRequest(message);

        sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_SCHEME_ACK, accepted, null));

        if (accepted) {
            scheduleSchemeCommitTimeout(message);
        }
    }

    private void handleRewireSchemeCommit(RewireMessage message) {
        if (expiredSchemeCommits.remove(message.recoveryId())) {
            Console.log("[REWIRE] Ignored late scheme commit for expired recoveryId="
                    + message.recoveryId(), Constant.BG_RED);
            return;
        }

        clearSchemeCommitTimeout(message.recoveryId());

        boolean accepted = connectionManager.applyRewireScheme(
                message.recoveryId(),
                message.connectsTo(),
                message.disconnectsFrom(),
                "rewiring scheme commit from " + message.sender().nodeId());

        releaseRewiringReservation(message.recoveryId());
        releaseDeficientRoleIfMatching(message.recoveryId());
        refreshHealthState();

        Console.log("[REWIRE][COMMIT] Scheme commit from " + message.sender().nodeId()
                + " accepted=" + accepted
                + ", connectsTo=" + message.connectsTo()
                + ", disconnectsFrom=" + message.disconnectsFrom()
                + ", recoveryId=" + message.recoveryId(), Constant.GREEN);
    }

    private void scheduleSchemeCommitTimeout(RewireMessage message) {
        clearSchemeCommitTimeout(message.recoveryId());
        expiredSchemeCommits.remove(message.recoveryId());

        ScheduledFuture<?> timeout = schemeCommitTimeoutExecutor.schedule(
                () -> handleSchemeCommitTimeout(message.recoveryId()),
                SCHEME_COMMIT_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);

        pendingSchemeCommits.put(message.recoveryId(), timeout);
    }

    private void clearSchemeCommitTimeout(String recoveryId) {
        ScheduledFuture<?> timeout = pendingSchemeCommits.remove(recoveryId);

        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private void handleSchemeCommitTimeout(String recoveryId) {
        ScheduledFuture<?> timeout = pendingSchemeCommits.remove(recoveryId);

        if (timeout == null) {
            Console.log("timeout == null");
            return;
        }

        releaseRewiringReservation(recoveryId);
        releaseDeficientRoleIfMatching(recoveryId);
        refreshHealthState();
        expiredSchemeCommits.add(recoveryId);

        Console.log("[REWIRE] Scheme commit timed out. Released local recovery role for recoveryId="
                + recoveryId + ", recoveryRole=" + recoveryRole, Constant.BG_RED);
    }

    /**
     * For a candidate node C, finds a candidate node D. Requirements: D is a
     * neighbor of C. D is NOT a neighbor of A.
     * 
     * @param c             the C node
     * @param defANeighbors neighbors of A
     * @return
     */
    private NodeAddress findCandidateD(NodeAddress c, List<NodeAddress> defANeighbors) {
        if (c == null) {
            Console.log("C is invalid");
            return null;
        }

        // BUG: This doesn't seem to be the list for neighbors of C? It seems to be
        // neighbors of A (the node calling this method(?))
        // UPDATE: this is C, since its the one handling handleNeighborsQuery, not A
        List<NodeAddress> localNeighborsOfC = new ArrayList<>(connectionManager.neighborAddresses());
        Collections.shuffle(localNeighborsOfC);

        for (NodeAddress d : localNeighborsOfC) {
            if (d == null || d.nodeId().equals(c.nodeId())) {
                Console.log("d is null or d == c -> skip");
                continue;
            }

            boolean dNotInDefA = !contains(defANeighbors, d.nodeId());
            boolean cAndDAdjacent = connectionManager.containsNode(d.nodeId());

            if (dNotInDefA && cAndDAdjacent) {
                Console.log("D found");
                return d;
            }
        }

        Console.log("No D found");
        return null;
    }

    /**
     * Selects neighbors of A that are candidates for C node. Requirements: C is not
     * a neighbor of defB.
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

            if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
                Console.log("[REWIRE_DEBUG] I am not DEFICIENT", Constant.ORANGE);
                return false;
            }

            Console.log("[REWIRE_DEBUG] Am I free OR DEFICIENT_LEADER/FELLOW? "
                    + (recoveryRole == RecoveryRole.FREE || recoveryRole == RecoveryRole.DEFICIENT_LEADER
                            || recoveryRole == RecoveryRole.DEFICIENT_FELLOW)
                    + " currently: " + recoveryRole, Constant.ORANGE);
            return recoveryRole == RecoveryRole.FREE || recoveryRole == RecoveryRole.DEFICIENT_LEADER
                    || recoveryRole == RecoveryRole.DEFICIENT_FELLOW;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Attempts to set the local node as the DEFICIENT_LEADER. If the node is
     * DEFICIENT and FREE, it can become a LEADER.
     * 
     * @param recoveryId the recovery ID
     * @return success or not
     */
    private boolean becomeDeficientLeader(String recoveryId) {
        roleLock.lock();
        try {
            refreshHealthState();
            if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
                Console.log("Node is not deficient");
                return false;
            }

            if (recoveryRole != RecoveryRole.FREE) {
                Console.log("Node is not free but " + recoveryRole);
                return false;
            }

            recoveryRole = RecoveryRole.DEFICIENT_LEADER;
            activeDeficientRecoveryId = recoveryId;

            Console.log("Node is now DEFICIENT_LEADER", Constant.GREEN);
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

            if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
                Console.log("Node is not deficient");
                return false;
            }

            if (recoveryRole != RecoveryRole.FREE) {
                Console.log("Node is not free but " + recoveryRole);
                return false;
            }

            recoveryRole = RecoveryRole.DEFICIENT_FELLOW;
            activeDeficientRecoveryId = recoveryId;

            Console.log("Node is now DEFICIENT_FELLOW", Constant.GREEN);
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

            if (isInitiatingDeficientRequest) {
                Console.log("Already initiating deficient request with another node");
                return false;
            }

            if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
                Console.log("Node is not deficient");
                return false;
            }

            Console.log("Node is " + recoveryRole + " == FREE?");
            return recoveryRole == RecoveryRole.FREE;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * If node is SUFFICIENT -> {@code false}
     * <p>
     * If node if not FREE or REWIRING_NODE -> {@code false}
     * <p>
     * If node is not LEADER -> {@code false}
     * <p>
     * If node is already initiating a request and node is a LEADER -> {@code false}
     * <p>
     * {@code TRUE} if node if DEFICIENT, FREE, now a LEADER and NOT initiating any
     * requests.
     * 
     * @param message
     * @return
     */
    private boolean canAcceptSessionRequest(RewireMessage message) {
        roleLock.lock();
        try {
            refreshHealthState();

            if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
                Console.log("[REWIRE_DEBUG] I'm not deficient", Constant.ORANGE);
                return false;
            }

            if (recoveryRole != RecoveryRole.FREE && recoveryRole != RecoveryRole.REWIRING_NODE) {
                Console.log("[REWIRE_DEBUG] I'm not free or rewiring_node, currently=" + recoveryRole, Constant.ORANGE);
                return false;
            }

            NodeAddress leader = electLeader(message.defA(), message.defB());

            if (!leader.nodeId().equals(message.sender().nodeId())) {
                Console.log("[REWIRE_DEBUG] I'm not a leader", Constant.ORANGE);
                return true;
            }

            if (isInitiatingDeficientRequest && leader.nodeId().equals(localAddress.nodeId())) {
                Console.log("[REWIRE_DEBUG] I'm already a leader for another request", Constant.ORANGE);
                return false;
            }

            Console.log("Can accept session request!", Constant.GREEN);
            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean canAcceptSchemeRequest(RewireMessage message) {
        roleLock.lock();
        try {
            if (recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {
                Console.log("I'm a deficient fellow of an active recovery? "
                        + Objects.equals(activeDeficientRecoveryId, message.recoveryId())
                        + (message.connectsTo() != null) + " " + (message.disconnectsFrom() == null));
                return Objects.equals(activeDeficientRecoveryId, message.recoveryId())
                        && message.connectsTo() != null
                        && message.disconnectsFrom() == null;
            }

            if (recoveryRole != RecoveryRole.REWIRING_NODE) {
                // TODO: a rewiring_node can still be part of multiple rewiring scheme
                Console.log("Can't accept scheme request becasue " + recoveryRole + " == REWIRING_NODE", Constant.RED);
                return false;
            }

            tryBecomeRewiringNode(message.recoveryId(), message.nodeC(), message.nodeD(), message.connectsTo(),
                    message.disconnectsFrom());

            boolean edgeReserved = recoveryIdByReservedEdge.containsValue(message.recoveryId());

            if (!edgeReserved) {
                Console.log("Recovery ID has no reserved edge");
                return false;
            }

            Console.log("Is degree neutral? " + isDegreeNeutralSwap(message.connectsTo(), message.disconnectsFrom()));
            return isDegreeNeutralSwap(message.connectsTo(), message.disconnectsFrom());
        } finally {
            roleLock.unlock();
        }
    }

    private boolean canBecomeRewiringNode(String recoveryId, NodeAddress c, NodeAddress d, NodeAddress connectsTo,
            NodeAddress disconnectsFrom) {
        Console.log(
                "[REWIRE ] CHECK Trying to become the REWIRING_NODE. c=" + c + ", d=" + d + ", connectsTo=" + connectsTo
                        + ", disconnectsFrom=" + disconnectsFrom,
                Constant.BG_GREEN);

        roleLock.lock();
        try {
            refreshHealthState();

            // DEFICIENT_LEADER and DEFICIENT_FELLOW are exclusive.
            // They cannot become REWIRING_NODE in another session.
            if (recoveryRole == RecoveryRole.DEFICIENT_LEADER
                    || recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {
                Console.log("Already LEADER or FELLOW");
                return false;
            }

            // Both DEFICIENT and SUFFICIENT nodes may become REWIRING_NODE,
            // but only if the operation is degree-neutral. (?)
            if (!isDegreeNeutralSwap(connectsTo, disconnectsFrom)) {
                Console.log("Not degree neutral");
                return false;
            }

            String edgeKey = edgeKey(c, d);
            String existingRecoveryId = recoveryIdByReservedEdge.get(edgeKey);

            if (existingRecoveryId != null && !existingRecoveryId.equals(recoveryId)) {
                Console.log("Incorrect recoveryID: " + existingRecoveryId + " is the one in the map, the current one: "
                        + recoveryId);
                return false;
            }

            // reservedEdgeByRecoveryId.put(recoveryId, edgeKey);
            // recoveryRole = RecoveryRole.REWIRING_NODE;

            Console.log("Can become REWIRING_NODE, NOT reserving edge");
            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean tryBecomeRewiringNode(String recoveryId, NodeAddress c, NodeAddress d, NodeAddress connectsTo,
            NodeAddress disconnectsFrom) {
        Console.log("[REWIRE] Trying to become the REWIRING_NODE. c=" + c + ", d=" + d + ", connectsTo=" + connectsTo
                + ", disconnectsFrom=" + disconnectsFrom, Constant.BG_GREEN);

        roleLock.lock();
        try {
            refreshHealthState();

            // DEFICIENT_LEADER and DEFICIENT_FELLOW are exclusive.
            // They don't need to become REWIRING_NODE in another session.
            if (recoveryRole == RecoveryRole.DEFICIENT_LEADER
                    || recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {
                Console.log("Already LEADER or FELLOW");
                return false;
            }

            // Both DEFICIENT and SUFFICIENT nodes may become REWIRING_NODE,
            // but only if the operation is degree-neutral.
            if (!isDegreeNeutralSwap(connectsTo, disconnectsFrom)) {
                Console.log("Not degree neutral");
                return false;
            }

            String edgeKey = edgeKey(c, d);
            String existingRecoveryId = recoveryIdByReservedEdge.putIfAbsent(edgeKey, recoveryId);

            // TODO: It's returning false here, no one can become rewiring node because
            // existingRecoveryId didn't add anything
            if (existingRecoveryId != null && !existingRecoveryId.equals(recoveryId)) {
                Console.log("Edge already reserved by recoveryId="
                        + existingRecoveryId
                        + ", current recoveryId="
                        + recoveryId);
                return false;
            }

            // Either:
            // 1. we successfully reserved it
            // OR
            // 2. this is an idempotent retry of same recoveryId
            Console.log("Reserved edge " + edgeKey
                    + " for recoveryId=" + recoveryId);
            recoveryRole = RecoveryRole.REWIRING_NODE;

            Console.log("Has become REWIRING_NODE, reserving edge");
            return true;
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Releases any edges from the reservation list for the recovery scheme.
     * 
     * @param recoveryId the recovery ID
     */
    private void releaseRewiringReservation(String recoveryId) {
        roleLock.lock();
        Console.log("[REWIRE] RELEASE reservation recoveryId=" + recoveryId, Constant.ORANGE);
        try {
            recoveryIdByReservedEdge.entrySet()
                    .removeIf(entry -> entry.getValue().equals(recoveryId));

            Console.log("CURRENT reservations=" + recoveryIdByReservedEdge);

            if (recoveryIdByReservedEdge.isEmpty()
                    && recoveryRole == RecoveryRole.REWIRING_NODE) {

                Console.log("[REWIRE] FREE NOW", Constant.GREEN);

                recoveryRole = RecoveryRole.FREE;
            }

            Console.log("Current role: " + recoveryRole);

            refreshHealthState();
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Clears the node of the deficient recovery role after the recovery attempt.
     */
    private void clearDeficientRecoveryRoleAfterAttempt() {
        roleLock.lock();
        try {
            refreshHealthState();

            if (recoveryRole == RecoveryRole.DEFICIENT_LEADER
                    || recoveryRole == RecoveryRole.DEFICIENT_FELLOW) {

                recoveryRole = RecoveryRole.FREE;
                activeDeficientRecoveryId = null;
            }

            Console.log("[REWIRE] Recovery attempt ended. healthState=" + connectionManager.getHealthState()
                    + ", recoveryRole=" + recoveryRole
                    + ", neighbors=" + connectionManager.size() + "/" + connectionManager.getMaxNeighbors(),
                    connectionManager.size() < connectionManager.getMaxNeighbors() ? Constant.RED : Constant.GREEN);
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Change the node role back to FREE after the recovery scheme.
     * 
     * @param recoveryId the recovery ID
     */
    private void releaseDeficientRoleIfMatching(String recoveryId) {
        roleLock.lock();
        try {
            // TODO: This seems problematic! (but fixed with the comments)
            // Recovery Role should be reversed even if the node is still deficient after
            // the process.
            if (Objects.equals(activeDeficientRecoveryId, recoveryId)
            // && connectionManager.size() == connectionManager.getMaxNeighbors()) {
            ) {
                recoveryRole = RecoveryRole.FREE;
                activeDeficientRecoveryId = null;
            }

            refreshHealthState();
        } finally {
            roleLock.unlock();
        }
    }

    /**
     * Sets the current health state.
     */
    private void refreshHealthState() {
        connectionManager.refreshHealthState();
        connectionManager.getHealthState();
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
            isInitiatingDeficientRequest = value;
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
     * @return the reply message
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

            Console.log("[REWIRE][SEND_WAIT] START" + " attempt=" + attempt
                    + " type=" + message.type()
                    + " recoveryId=" + message.recoveryId()
                    + " target=" + target.nodeId()
                    + " key=" + key, Constant.CYAN);

            pendingReplies.put(key, future);

            try {
                sendOnly(target, message);

                RewireMessage reply = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

                Console.log("[REWIRE][SEND_WAIT] SUCCESS" + " attempt=" + attempt
                        + " type=" + message.type()
                        + " recoveryId=" + message.recoveryId()
                        + " replyType=" + reply.type()
                        + " replySender=" + reply.sender().nodeId(), Constant.GREEN);

                return reply;
            } catch (InterruptedException | ExecutionException | TimeoutException exception) {
                Console.log("[REWIRE][SEND_WAIT] FAILED" + " attempt=" + attempt
                        + " type=" + message.type()
                        + " recoveryId=" + message.recoveryId()
                        + " key=" + key
                        + " reason=" + exception.getClass().getSimpleName(), Constant.BG_RED);

                pendingReplies.remove(key);
            }
        }

        Console.log("[REWIRE][SEND_WAIT] GAVE UP" + " type=" + message.type()
                + " recoveryId=" + message.recoveryId(), Constant.BOLD + Constant.BG_RED);

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
            Console.log("[REWIRE]" + stageLabel(message.type()) + " " + message.type()
                    + " recoveryId=" + message.recoveryId()
                    + " to " + target.nodeId(), Constant.CYAN);
            udpCoordinator.send(localAddress, target, UdpPacketType.REWIRING,
                    message.encode());
        } catch (IOException exception) {
            Console.log("[REWIRE] Failed to send " + message.type() + " to " + target + ": " + exception.getMessage(),
                    Constant.RED);
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

        return addresses.stream().anyMatch(address -> address != null && address.nodeId().equals(nodeId));
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
                // || type == RecoveryMessageType.REWIRE_ACK
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
                    REWIRE_REQ ->
                "[REQ]";
            case REWIRE_REQ_DIRECT_ACK, REWIRE_SESSION_ACK, REWIRE_SCHEME_ACK, NEIGHBORS_QUERY_RESPONSE,
                    REWIRING_PROPOSE_ACK, REWIRE_DENY ->
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
