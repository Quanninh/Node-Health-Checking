package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class FailureDetector {

    private final String localNodeId;
    private final PeerDirectory peerDirectory;
    private final PeerClient peerClient;
    private final DashboardReporter dashboardReporter;
    private final PhiAccrualFailureDetector phiDetector;
    private final int gossipIntervalSeconds;
    private final int kHelpers;
    private final ScheduledExecutorService scheduler;

    FailureDetector(
            String localNodeId,
            PeerDirectory peerDirectory,
            PeerClient peerClient,
            DashboardReporter dashboardReporter,
            PhiAccrualFailureDetector phiDetector,
            int gossipIntervalSeconds,
            int kHelpers) {
        this.localNodeId = localNodeId;
        this.peerDirectory = peerDirectory;
        this.peerClient = peerClient;
        this.dashboardReporter = dashboardReporter;
        this.phiDetector = phiDetector;
        this.gossipIntervalSeconds = gossipIntervalSeconds;
        this.kHelpers = kHelpers;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    void start() {
        scheduler.scheduleAtFixedRate(
                this::runOneProbeSafely,
                0,
                gossipIntervalSeconds,
                TimeUnit.SECONDS);
    }

    private void runOneProbeSafely() {
        try {
            runOneProbe();
        } catch (Exception exception) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Failure detector error: "
                            + exception.getMessage());
        }
    }

    private void runOneProbe() {
        Optional<PeerAddress> selectedPeer = peerDirectory.nextPeer();

        if (selectedPeer.isEmpty()) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "No reachable peers configured. Nothing to ping.");
            return;
        }

        PeerAddress peer = selectedPeer.get();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + localNodeId
                        + " directly pings Node " + peer.nodeId()
                        + " at " + peer.host() + ":" + peer.port());

        peerClient.ping(peer).thenAccept(ackReceived -> {
            if (ackReceived) {
                handleAckReceived(peer, "direct ping");
                printLocalPeerStates();
                return;
            }

            handleDirectPingFailure(peer);
        });
    }

    private void handleDirectPingFailure(PeerAddress target) {
        List<PeerAddress> helpers = peerDirectory.selectKHelpers(localNodeId, target, kHelpers);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Direct ping failed for Node " + target.nodeId()
                        + ". Selected K helpers: " + helpers);

        if (helpers.isEmpty()) {
            handleNoAckAfterDirectAndIndirect(target);
            return;
        }

        List<CompletableFuture<Boolean>> helperChecks = helpers.stream()
                .map(helper -> peerClient.pingReq(helper, target))
                .toList();

        CompletableFuture
                .allOf(helperChecks.toArray(new CompletableFuture[0]))
                .thenAccept(ignored -> {
                    boolean anyHelperReceivedAck = helperChecks.stream()
                            .anyMatch(CompletableFuture::join);

                    if (anyHelperReceivedAck) {
                        handleAckReceived(target, "indirect ping-req");
                    } else {
                        handleNoAckAfterDirectAndIndirect(target);
                    }

                    printLocalPeerStates();
                });
    }

    private void handleAckReceived(PeerAddress peer, String source) {
        PeerStatus previousStatus = peerDirectory.getStatus(peer.nodeId());

        if (previousStatus == PeerStatus.UNREACHABLE) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "ACK received from Node " + peer.nodeId()
                            + " through " + source
                            + ", but local state is UNREACHABLE. "
                            + "This node must send JOIN and re-enter as a new node instance.");
            return;
        }

        peerDirectory.markAlive(peer.nodeId(), phiDetector);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "ACK received from Node "
                        + peer.nodeId()
                        + " through " + source
                        + ". Status becomes ALIVE.");
    }

    private void handleNoAckAfterDirectAndIndirect(PeerAddress target) {
        Optional<PeerState> optionalState = peerDirectory.getState(target.nodeId());

        if (optionalState.isEmpty()) {
            return;
        }

        PeerState state = optionalState.get();

        double phi = phiDetector.calculatePhi(
                state.slidingWindowSeconds(),
                state.lastAckTimeMillis(),
                System.currentTimeMillis());

        PeerStatus phiStatus = phiDetector.determineStatus(phi);

        if (phiStatus == PeerStatus.UNREACHABLE) {
            handleUnreachablePeer(target, phi);
            return;
        }

        if (phiStatus == PeerStatus.WARNING) {
            peerDirectory.markWarning(target.nodeId(), phi);
        } else {
            peerDirectory.markSuspected(target.nodeId(), phi);
        }

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + target.nodeId()
                        + " has no direct/indirect ACK. phi="
                        + String.format("%.4f", phi)
                        + ", status=" + peerDirectory.getStatus(target.nodeId())
                        + ". It is not declared unreachable yet.");
    }

    private void handleUnreachablePeer(PeerAddress target, double phi) {
        PeerStatus previousStatus = peerDirectory.getStatus(target.nodeId());

        peerDirectory.markUnreachable(target.nodeId(), phi);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + localNodeId
                        + " marks Node " + target.nodeId()
                        + " as UNREACHABLE. phi="
                        + String.format("%.4f", phi)
                        + ". It must rejoin as a new node if it comes back.");

        if (previousStatus != PeerStatus.UNREACHABLE) {
            dashboardReporter.reportFailure(target, phi);
        }
    }

    private void printLocalPeerStates() {
        System.out.println("----- Local Peer States at Node " + localNodeId + " -----");

        for (PeerState state : peerDirectory.states()) {
            System.out.println(state);
        }

        System.out.println("---------------------------------------------------------");
    }
}
