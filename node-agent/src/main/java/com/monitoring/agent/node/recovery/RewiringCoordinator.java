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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.node.transport.UdpCoordinator;
import com.monitoring.agent.node.transport.UdpPacketType;
import com.monitoring.agent.util.Console;

public final class RewiringCoordinator {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(800);
    private static final int SEND_ATTEMPTS = 3; // put into Constant.java later

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final NetworkTopologyCache topologyCache;
    private final UdpCoordinator udpCoordinator;

    private final ReentrantLock roleLock = new ReentrantLock();
    private volatile RecoveryRole role = RecoveryRole.FREE;
    private volatile String activeDeficientRecoveryId;

    private final ConcurrentHashMap<String, RecoveryRole> rewiringSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lockedPairs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<RewireMessage>> pendingReplies = new ConcurrentHashMap<>();

    public RewiringCoordinator(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            NetworkTopologyCache topologyCache,
            UdpCoordinator udpCoordinator) {

        this.localAddress = Objects.requireNonNull(localAddress);
        this.connectionManager = Objects.requireNonNull(connectionManager);
        this.topologyCache = Objects.requireNonNull(topologyCache);
        this.udpCoordinator = Objects.requireNonNull(udpCoordinator);
    }

    public void start() {
        udpCoordinator.registerRecoveryConsumer(envelope -> {
            try {
                RewireMessage message = RewireMessage.decode(envelope.payload());
                handle(message);
            } catch (Exception ignored) {
                // Not every RECOVERY packet is a RewireMessage.
                // DEFICIENT messages can still be handled by RecoveryUDPService.
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

        if (!tryBecomeDeficient()) {
            Console.log("[REWIRE] Refused: local node is already in another deficient recovery session.");
            return false;
        }

        try {
            if (!connectionManager.containsNode(defB.nodeId())) {
                boolean directOk = tryDirectRepair(defA, defB);
                if (directOk) {
                    clearDeficientRoleAfterAttempt();
                    Console.log("[REWIRE] Direct repair succeeded: " + defA + " <-> " + defB);
                    return true;
                }
            }

            return runFullRewiring(defA, defB);
        } finally {
            clearDeficientRoleAfterAttempt();
        }
    }

    private boolean runFullRewiring(NodeAddress defA, NodeAddress defB) {
        String requestId = UUID.randomUUID().toString();

        RewireMessage req = RewireMessage.of(
                RecoveryMessageType.REWIRE_REQ,
                requestId,
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

        RewireMessage ack = sendAndWait(defB, req, REQUEST_TIMEOUT);

        if (ack == null || ack.type() == RecoveryMessageType.REWIRE_DENY) {
            randomBackoff();
            return false;
        }

        if (ack.type() != RecoveryMessageType.REWIRE_ACK) {
            return false;
        }

        if (!localAddress.nodeId().equals(electLeader(defA, defB).nodeId())) {
            return false;
        }

        String recoveryId = UUID.randomUUID().toString();

        if (!becomeDeficientLeader(recoveryId)) {
            return false;
        }

        // should also keep track of its own neighbor list to see when it's DEFICIENT
        RewireMessage commit = RewireMessage.of(
                RecoveryMessageType.COMMIT_ACK,
                recoveryId,
                localAddress,
                defA,
                defB,
                null,
                null,
                null,
                null,
                connectionManager.neighborAddresses(),
                ack.defBNeighbors(),
                RewireStatus.ACCEPTED);

        sendOnly(defB, commit);

        List<NodeAddress> defANeighbors = connectionManager.neighborAddresses();
        List<NodeAddress> defBNeighbors = ack.defBNeighbors();

        List<NodeAddress> candidateCs = selectCandidateCs(defANeighbors, defBNeighbors);

        for (NodeAddress c : candidateCs) {
            RewireMessage query = RewireMessage.of(
                    RecoveryMessageType.NEIGHBORS_QUERY,
                    recoveryId,
                    localAddress,
                    defA,
                    defB,
                    c,
                    null,
                    null,
                    null,
                    defANeighbors,
                    defBNeighbors,
                    null);

            RewireMessage response = sendAndWait(c, query, REQUEST_TIMEOUT);

            if (response == null || response.status() != RewireStatus.ACCEPTED || response.nodeD() == null) {
                continue;
            }

            NodeAddress d = response.nodeD();

            boolean committed = executeScheme(recoveryId, defA, defB, c, d);

            if (committed) {
                Console.log("[REWIRE] Node " + defA.nodeId()
                        + " successfully rewired with " + d.nodeId()
                        + " while breaking edge "
                        + c.nodeId() + "-" + d.nodeId());
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

        return connectionManager.addIfSpace(defB, "direct rewiring recovery");
    }

    // private boolean executeScheme(
    //         String recoveryId,
    //         NodeAddress defA,
    //         NodeAddress defB,
    //         NodeAddress c,
    //         NodeAddress d) {

    //     String txId = recoveryId + ":scheme";

    //     RewireMessage toC = RewireMessage.of(
    //             RecoveryMessageType.REWIRE_SCHEME,
    //             txId + ":C",
    //             localAddress,
    //             defA,
    //             defB,
    //             c,
    //             d,
    //             defB,
    //             d,
    //             List.of(),
    //             List.of(),
    //             null);

    //     RewireMessage toD = RewireMessage.of(
    //             RecoveryMessageType.REWIRE_SCHEME,
    //             txId + ":D",
    //             localAddress,
    //             defA,
    //             defB,
    //             c,
    //             d,
    //             defA,
    //             c,
    //             List.of(),
    //             List.of(),
    //             null);

    //     RewireMessage toB = RewireMessage.of(
    //             RecoveryMessageType.REWIRE_SCHEME,
    //             txId + ":B",
    //             localAddress,
    //             defA,
    //             defB,
    //             c,
    //             d,
    //             c,
    //             null,
    //             List.of(),
    //             List.of(),
    //             null);

    //     CompletableFuture<RewireMessage> ackC = sendAsync(c, toC);
    //     CompletableFuture<RewireMessage> ackD = sendAsync(d, toD);
    //     CompletableFuture<RewireMessage> ackB = sendAsync(defB, toB);
        
    //     // will this add regardless of the ack results?
    //     boolean localApplied = connectionManager.addIfSpace(d, "rewiring scheme: defA connects to D");

    //     try {
    //         RewireMessage cAck = ackC.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    //         RewireMessage dAck = ackD.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    //         RewireMessage bAck = ackB.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    //         return localApplied
    //                 && accepted(cAck)
    //                 && accepted(dAck)
    //                 && accepted(bAck);
    //     } catch (Exception exception) {
    //         Console.log("[REWIRE] Scheme failed: " + exception.getMessage());
    //         return false;
    //     } finally {
    //         releasePair(c, d, recoveryId);
    //         releaseRewiringSession(recoveryId);
    //     }
    // }

    // this is a revised version to wait for acks before adding D to A
    private boolean executeScheme(
        String recoveryId,
        NodeAddress defA,
        NodeAddress defB,
        NodeAddress c,
        NodeAddress d) {

        String txId = recoveryId + ":scheme";

        RewireMessage toC = RewireMessage.of(
                RecoveryMessageType.REWIRE_SCHEME,
                txId + ":C",
                localAddress,
                defA,
                defB,
                c,
                d,
                defB,
                d,
                List.of(),
                List.of(),
                null);

        RewireMessage toD = RewireMessage.of(
                RecoveryMessageType.REWIRE_SCHEME,
                txId + ":D",
                localAddress,
                defA,
                defB,
                c,
                d,
                defA,
                c,
                List.of(),
                List.of(),
                null);

        RewireMessage toB = RewireMessage.of(
                RecoveryMessageType.REWIRE_SCHEME,
                txId + ":B",
                localAddress,
                defA,
                defB,
                c,
                d,
                c,
                null,
                List.of(),
                List.of(),
                null);

        CompletableFuture<RewireMessage> ackC = sendAsync(c, toC);
        CompletableFuture<RewireMessage> ackD = sendAsync(d, toD);
        CompletableFuture<RewireMessage> ackB = sendAsync(defB, toB);

        try {
            RewireMessage cAck = ackC.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RewireMessage dAck = ackD.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RewireMessage bAck = ackB.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            boolean allRemoteAccepted =
                    accepted(cAck)
                            && accepted(dAck)
                            && accepted(bAck);

            if (!allRemoteAccepted) {
                Console.log("[REWIRE] Scheme aborted. At least one participant rejected.");
                return false;
            }

            boolean localApplied = connectionManager.addIfSpace(
                    d,
                    "rewiring scheme: defA connects to D");

            if (!localApplied) {
                Console.log("[REWIRE] Scheme failed locally. defA could not add D.");
                return false;
            }

            Console.log("[REWIRE] Scheme committed. "
                    + defA.nodeId() + " connected to " + d.nodeId()
                    + ", " + defB.nodeId() + " connected to " + c.nodeId()
                    + ", edge " + c.nodeId() + "-" + d.nodeId() + " removed.");

            return true;
        } catch (Exception exception) {
            Console.log("[REWIRE] Scheme failed: " + exception.getMessage());
            return false;
        } finally {
            releaseRewiringSession(recoveryId);
        }
    }

    private void handle(RewireMessage message) {
        if (message == null || message.type() == null) {
            return;
        }

        CompletableFuture<RewireMessage> pending = pendingReplies.remove(message.recoveryId());
        if (pending != null && isReply(message.type())) {
            pending.complete(message);
            return;
        }

        switch (message.type()) {
            case REWIRE_REQ_DIRECT -> handleDirectRequest(message);
            case REWIRE_REQ -> handleRewireRequest(message);
            case COMMIT_ACK -> handleCommitAck(message);
            case NEIGHBORS_QUERY -> handleNeighborsQuery(message);
            case REWIRING_PROPOSE -> handleRewiringPropose(message);
            case REWIRE_SCHEME -> handleRewireScheme(message);
            default -> {
            }
        }
    }

    private void handleDirectRequest(RewireMessage message) {
        boolean accepted = canAcceptDeficientRequest()
                && connectionManager.addIfSpace(message.sender(), "direct rewiring request");

        sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_ACK, accepted, null));
    }

    private void handleRewireRequest(RewireMessage message) {
        if (!canAcceptDeficientRequest()) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_DENY, false, null));
            return;
        }

        NodeAddress leader = electLeader(message.defA(), message.defB());

        if (!leader.nodeId().equals(message.sender().nodeId())) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_DENY, false, null));
            return;
        }

        boolean accepted = becomeDeficientPending();

        RewireMessage response = RewireMessage.of(
                accepted ? RecoveryMessageType.REWIRE_ACK : RecoveryMessageType.REWIRE_DENY,
                message.recoveryId(),
                localAddress,
                message.defA(),
                message.defB(),
                null,
                null,
                null,
                null,
                List.of(),
                connectionManager.neighborAddresses(),
                accepted ? RewireStatus.ACCEPTED : RewireStatus.REFUSED);

        sendOnly(message.sender(), response);
    }

    private void handleCommitAck(RewireMessage message) {
        if (message.status() == RewireStatus.ACCEPTED) {
            becomeDeficientFellow(message.recoveryId());
        }
    }

    private void handleNeighborsQuery(RewireMessage message) {
        NodeAddress d = findCandidateD(
                message.nodeC(),
                message.defANeighbors(),
                message.defBNeighbors());

        if (d == null) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            return;
        }

        if (!tryLockPair(message.nodeC(), d, message.recoveryId())) {
            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            return;
        }

        if (!joinRewiringSession(message.recoveryId())) {
            releasePair(message.nodeC(), d, message.recoveryId());
            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
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
                null,
                null,
                message.defANeighbors(),
                message.defBNeighbors(),
                null);

        RewireMessage dAck = sendAndWait(d, proposal, REQUEST_TIMEOUT);

        if (dAck == null || dAck.status() != RewireStatus.ACCEPTED) {
            releasePair(message.nodeC(), d, message.recoveryId());
            releaseRewiringSession(message.recoveryId());
            sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, false, null));
            return;
        }

        sendOnly(message.sender(), reply(message, RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE, true, d));
    }

    private void handleRewiringPropose(RewireMessage message) {
        boolean valid = connectionManager.containsNode(message.nodeC().nodeId())
                && tryLockPair(message.nodeC(), message.nodeD(), message.recoveryId())
                && joinRewiringSession(message.recoveryId());

        sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRING_PROPOSE_ACK, valid, message.nodeD()));
    }

    private void handleRewireScheme(RewireMessage message) {
        boolean accepted = connectionManager.applyRewireScheme(
                message.recoveryId(),
                message.connectsTo(),
                message.disconnectsFrom(),
                "rewiring scheme from " + message.sender().nodeId());

        if (message.disconnectsFrom() != null) {
            releasePair(message.nodeC(), message.nodeD(), message.recoveryId());
        }

        releaseDeficientRoleIfMatching(message.recoveryId());
        releaseRewiringSession(message.recoveryId());

        sendOnly(message.sender(), reply(message, RecoveryMessageType.REWIRE_SCHEME_ACK, accepted, null));
    }

    private NodeAddress findCandidateD(
            NodeAddress c,
            List<NodeAddress> defANeighbors,
            List<NodeAddress> defBNeighbors) {

        List<NodeAddress> shuffled = new ArrayList<>(connectionManager.neighborAddresses());
        Collections.shuffle(shuffled);

        for (NodeAddress d : shuffled) {
            if (d.nodeId().equals(c.nodeId())) {
                continue;
            }

            // redundant prompt
            // boolean dInDefB = contains(defBNeighbors, d.nodeId());
            boolean dNotInDefA = !contains(defANeighbors, d.nodeId());
            boolean cAndDAdjacent = connectionManager.containsNode(d.nodeId());

            if (dNotInDefA && cAndDAdjacent) {
                return d;
            }
        }

        return null;
    }

    private List<NodeAddress> selectCandidateCs(
            List<NodeAddress> defANeighbors,
            List<NodeAddress> defBNeighbors) {

        List<NodeAddress> result = defANeighbors.stream()
                .filter(c -> !contains(defBNeighbors, c.nodeId()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        Collections.shuffle(result);
        return result;
    }

    private boolean tryBecomeDeficient() {
        roleLock.lock();
        try {
            if (role != RecoveryRole.FREE) {
                return false;
            }

            role = RecoveryRole.DEFICIENT;
            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean becomeDeficientPending() {
        roleLock.lock();
        try {
            return role == RecoveryRole.FREE || role == RecoveryRole.DEFICIENT;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean becomeDeficientLeader(String recoveryId) {
        roleLock.lock();
        try {
            if (role != RecoveryRole.DEFICIENT) {
                return false;
            }

            role = RecoveryRole.DEFICIENT_LEADER;
            activeDeficientRecoveryId = recoveryId;
            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private void becomeDeficientFellow(String recoveryId) {
        roleLock.lock();
        try {
            role = RecoveryRole.DEFICIENT_FELLOW;
            activeDeficientRecoveryId = recoveryId;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean canAcceptDeficientRequest() {
        roleLock.lock();
        try {
            return role == RecoveryRole.FREE || role == RecoveryRole.DEFICIENT;
        } finally {
            roleLock.unlock();
        }
    }

    private boolean isStillDeficient() {
        return connectionManager.size() < connectionManager.getMaxNeighbors();
    }
    // this is not correct in the case that a node lacks many nodes, not just one
    // UPDATED: consider that, but I didnt test :)))
    private void clearDeficientRoleAfterAttempt() {
        roleLock.lock();
        try {
            if (role == RecoveryRole.DEFICIENT
                    || role == RecoveryRole.DEFICIENT_LEADER
                    || role == RecoveryRole.DEFICIENT_FELLOW) {

                if (isStillDeficient()) {
                    role = RecoveryRole.DEFICIENT;
                    activeDeficientRecoveryId = null;

                    Console.log("[REWIRE] Recovery attempt ended, but node is still DEFICIENT. neighbors="
                            + connectionManager.size()
                            + "/"
                            + connectionManager.getMaxNeighbors());
                } else {
                    role = RecoveryRole.FREE;
                    activeDeficientRecoveryId = null;

                    Console.log("[REWIRE] Node recovered. Role returned to FREE.");
                }
            }
        } finally {
            roleLock.unlock();
        }
    }

    private void releaseDeficientRoleIfMatching(String recoveryId) {
        roleLock.lock();
        try {
            if (Objects.equals(activeDeficientRecoveryId, recoveryId)) {
                role = RecoveryRole.FREE;
                activeDeficientRecoveryId = null;
            }
        } finally {
            roleLock.unlock();
        }
    }

    private boolean joinRewiringSession(String recoveryId) {
        roleLock.lock();
        try {
            if (role == RecoveryRole.DEFICIENT_LEADER || role == RecoveryRole.DEFICIENT_FELLOW) {
                return false;
            }

            rewiringSessions.put(recoveryId, RecoveryRole.REWIRING_NODE);
            return true;
        } finally {
            roleLock.unlock();
        }
    }

    private void releaseRewiringSession(String recoveryId) {
        rewiringSessions.remove(recoveryId);
    }

    // the implementation for this lock pair is kinda vague: if one node knows about the pair is locked: how can others know
    // -> suggested solution: each node maintains a current recovery id list -> if node A asks node C to become a Rewiring_node of 
    // 1 recovery session, C under normal conditions(reachable) can accept if its recovery id list size is not exceeds its current neighbor list size
    // -> meaning it still has some connections left to "break".
    private boolean tryLockPair(NodeAddress c, NodeAddress d, String recoveryId) {
        String key = pairKey(c, d);
        String existing = lockedPairs.putIfAbsent(key, recoveryId);
        return existing == null || existing.equals(recoveryId);
    }

    // same as above
    private void releasePair(NodeAddress c, NodeAddress d, String recoveryId) {
        lockedPairs.remove(pairKey(c, d), recoveryId);
    }

    // same as above
    private String pairKey(NodeAddress a, NodeAddress b) {
        List<String> ids = new ArrayList<>();
        ids.add(a.nodeId());
        ids.add(b.nodeId());
        ids.sort(Comparator.naturalOrder());
        return ids.get(0) + "--" + ids.get(1);
    }

    private NodeAddress electLeader(NodeAddress a, NodeAddress b) {
        return a.nodeId().compareTo(b.nodeId()) >= 0 ? a : b;
    }

    private RewireMessage reply(
            RewireMessage request,
            RecoveryMessageType replyType,
            boolean accepted,
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

    private RewireMessage sendAndWait(NodeAddress target, RewireMessage message, Duration timeout) {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            CompletableFuture<RewireMessage> future = new CompletableFuture<>();
            pendingReplies.put(message.recoveryId(), future);

            try {
                sendOnly(target, message);
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                pendingReplies.remove(message.recoveryId());
            }
        }

        return null;
    }

    private CompletableFuture<RewireMessage> sendAsync(NodeAddress target, RewireMessage message) {
        return CompletableFuture.supplyAsync(() -> sendAndWait(target, message, REQUEST_TIMEOUT));
    }

    private void sendOnly(NodeAddress target, RewireMessage message) {
        try {
            udpCoordinator.send(target.host(), target.port(), UdpPacketType.RECOVERY, message.encode());
        } catch (IOException exception) {
            Console.log("[REWIRE] Failed to send " + message.type()
                    + " to " + target
                    + ": " + exception.getMessage());
        }
    }

    private boolean accepted(RewireMessage message) {
        return message != null && message.status() == RewireStatus.ACCEPTED;
    }

    private boolean contains(List<NodeAddress> addresses, String nodeId) {
        return addresses.stream().anyMatch(address -> address.nodeId().equals(nodeId));
    }

    private boolean isReply(RecoveryMessageType type) {
        return type == RecoveryMessageType.REWIRE_ACK
                || type == RecoveryMessageType.REWIRE_DENY
                || type == RecoveryMessageType.NEIGHBORS_QUERY_RESPONSE
                || type == RecoveryMessageType.REWIRING_PROPOSE_ACK
                || type == RecoveryMessageType.REWIRE_SCHEME_ACK;
    }

    private void randomBackoff() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(250, 1000));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}