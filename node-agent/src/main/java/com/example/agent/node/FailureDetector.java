package com.example.agent.node;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class FailureDetector {

    private final String localNodeId;
    private final PeerDirectory peerDirectory;
    private final PeerClient peerClient;
    private final DashboardReporter dashboardReporter;
    private final int gossipIntervalSeconds;
    private final ScheduledExecutorService scheduler;

    FailureDetector(
            String localNodeId,
            PeerDirectory peerDirectory,
            PeerClient peerClient,
            DashboardReporter dashboardReporter,
            int gossipIntervalSeconds
    ) {
        this.localNodeId = localNodeId;
        this.peerDirectory = peerDirectory;
        this.peerClient = peerClient;
        this.dashboardReporter = dashboardReporter;
        this.gossipIntervalSeconds = gossipIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    void start() {
        scheduler.scheduleAtFixedRate(
                this::runOneProbeSafely,
                0,
                gossipIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void runOneProbeSafely() {
        try {
            runOneProbe();
        } catch (Exception exception) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Failure detector error: "
                            + exception.getMessage()
            );
        }
    }

    private void runOneProbe() {
        Optional<PeerAddress> selectedPeer = peerDirectory.nextPeer();

        if (selectedPeer.isEmpty()) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "No peers configured. Nothing to ping."
            );
            return;
        }

        PeerAddress peer = selectedPeer.get();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + localNodeId
                        + " pings Node " + peer.nodeId()
                        + " at " + peer.host() + ":" + peer.port()
        );

        peerClient.ping(peer).thenAccept(ackReceived -> {
            if (ackReceived) {
                peerDirectory.markAlive(peer.nodeId());

                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "ACK received from Node "
                                + peer.nodeId()
                );
            } else {
                handleFailedPeer(peer);
            }

            printLocalPeerStates();
        });
    }

    private void handleFailedPeer(PeerAddress failedPeer) {
        PeerStatus previousStatus = peerDirectory.getStatus(failedPeer.nodeId());

        peerDirectory.markFailed(failedPeer.nodeId());

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + localNodeId
                        + " finds out Node "
                        + failedPeer.nodeId()
                        + " has failed"
        );

        if (previousStatus != PeerStatus.FAILED) {
            dashboardReporter.reportFailure(failedPeer);
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
