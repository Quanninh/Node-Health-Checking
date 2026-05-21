package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit; 

import static com.example.agent.constant.Constant.UNREACHABLE_CLEANUP_INTERVAL_SECONDS; 

class FailureDetector {

    private final String localNodeId;
    private final NeighborDirectory neighborDirectory;
    private final NodeClient nodeClient;
    private final DashboardReporter dashboardReporter;
    private final PhiAccrualFailureDetector phiDetector;
    private final int probeIntervalSeconds;
    private final ScheduledExecutorService scheduler;

    private final FailureEventLog failureEventLog;
    private final double unreachableThreshold;

    FailureDetector(
            String localNodeId,
            NeighborDirectory neighborDirectory,
            NodeClient nodeClient,
            DashboardReporter dashboardReporter,
            PhiAccrualFailureDetector phiDetector,
            int probeIntervalSeconds,
            FailureEventLog failureEventLog,
            double unreachableThreshold) {
        this.localNodeId = localNodeId;
        this.neighborDirectory = neighborDirectory;
        this.nodeClient = nodeClient;
        this.dashboardReporter = dashboardReporter;
        this.phiDetector = phiDetector;
        this.probeIntervalSeconds = probeIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.failureEventLog = failureEventLog;
        this.unreachableThreshold = unreachableThreshold;
    }

    void start() {
        scheduler.scheduleAtFixedRate(
                this::runOneProbeSafely,
                0,
                probeIntervalSeconds,
                TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(
        neighborDirectory::removeUnreachableNeighbors,
        UNREACHABLE_CLEANUP_INTERVAL_SECONDS,
        UNREACHABLE_CLEANUP_INTERVAL_SECONDS,
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
        Optional<NodeAddress> selectedTarget = neighborDirectory.nextTargetNode();

        if (selectedTarget.isEmpty()) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "No reachable neighbor nodes configured. Nothing to ping.");
            return;
        }

        NodeAddress targetNode = selectedTarget.get();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + localNodeId
                        + " directly pings targetNode " + targetNode.nodeId()
                        + " at " + targetNode.host() + ":" + targetNode.port());

        nodeClient.ping(targetNode).thenAccept(ackReceived -> {
            if (ackReceived) {
                handleAckReceived(targetNode, "direct ping");
                printLocalNodeStates();
                return;
            }

            handleDirectPingFailure(targetNode);
        });
    }

    private void handleDirectPingFailure(NodeAddress targetNode) {
        List<NodeAddress> helperNodes = neighborDirectory.selectHelperNodes(targetNode);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Direct ping failed for targetNode " + targetNode.nodeId()
                        + ". helperNodes selected from neighborList: " + helperNodes);

        if (helperNodes.isEmpty()) {
            handleNoAckAfterDirectAndIndirect(targetNode);
            return;
        }

        List<CompletableFuture<Boolean>> helperChecks = helperNodes.stream()
                .map(helperNode -> nodeClient.pingReq(helperNode, targetNode))
                .toList();

        CompletableFuture
                .allOf(helperChecks.toArray(new CompletableFuture[0]))
                .thenAccept(ignored -> {
                    boolean anyHelperReceivedAck = helperChecks.stream()
                            .anyMatch(CompletableFuture::join);

                    if (anyHelperReceivedAck) {
                        handleAckReceived(targetNode, "indirect ping-req by helperNodes");
                    } else {
                        handleNoAckAfterDirectAndIndirect(targetNode);
                    }

                    printLocalNodeStates();
                });
    }

    private void handleAckReceived(NodeAddress targetNode, String source) {
        NodeStatus previousStatus = neighborDirectory.getStatus(targetNode.nodeId());

        if (previousStatus == NodeStatus.UNREACHABLE) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "ACK received from Node " + targetNode.nodeId()
                            + " through " + source
                            + ", but local state is UNREACHABLE. "
                            + "This node must send JOIN and re-enter as a new node instance.");
            return;
        }

        neighborDirectory.markAlive(targetNode.nodeId(), phiDetector);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "ACK received from Node "
                        + targetNode.nodeId()
                        + " through " + source
                        + ". Status becomes ALIVE.");
    }

    private void handleNoAckAfterDirectAndIndirect(NodeAddress targetNode) {
        Optional<NodeState> optionalState = neighborDirectory.getState(targetNode.nodeId());

        if (optionalState.isEmpty()) {
            return;
        }

        NodeState state = optionalState.get();

        double phi = phiDetector.calculatePhi(
                state.slidingWindowSeconds(),
                state.lastAckTimeMillis(),
                System.currentTimeMillis());

        NodeStatus phiStatus = phiDetector.determineStatus(phi);

        if (phiStatus == NodeStatus.UNREACHABLE) {
            handleUnreachableNode(targetNode, phi);
            return;
        }

        if (phiStatus == NodeStatus.WARNING) {
            neighborDirectory.markWarning(targetNode.nodeId(), phi);
        } else {
            neighborDirectory.markSuspected(targetNode.nodeId(), phi);
        }

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "targetNode " + targetNode.nodeId()
                        + " has no direct/indirect ACK. phi="
                        + String.format("%.4f", phi)
                        + ", status=" + neighborDirectory.getStatus(targetNode.nodeId())
                        + ". It is not declared unreachable yet.");
    }

    private void handleUnreachableNode(NodeAddress targetNode, double phi) {
        NodeStatus previousStatus = neighborDirectory.getStatus(targetNode.nodeId());

        neighborDirectory.markUnreachable(targetNode.nodeId(), phi);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node " + localNodeId
                        + " marks targetNode " + targetNode.nodeId()
                        + " as UNREACHABLE. phi="
                        + String.format("%.4f", phi)
                        + ". It must rejoin as a new node if it comes back.");

        if (previousStatus != NodeStatus.UNREACHABLE) {
            // Store the failure locally before any cleanup removes the neighbor.
            // The dashboard will later read this from the node directly.
            FailureEvent event = FailureEvent.unreachable(
                    localNodeId,
                    targetNode,
                    phi,
                    unreachableThreshold
            );

            boolean added = failureEventLog.add(event);

            if (added) {
                // Phase 2:
                // After storing the event locally, spread it to this node's current neighbors.
                // This is the first decentralized replacement for the old central report path.
                broadcastFailureEvent(event.decreaseTtl());
            }

            // Temporary backward compatibility:
            // Keep the old central dashboard report for now.
            // Later, this will be removed once the peer-to-peer event flow is stable.
            dashboardReporter.reportFailure(targetNode, phi);
        }
    }

    private void printLocalNodeStates() {
        System.out.println("----- Local Neighbor Node States at Node " + localNodeId + " -----");

        for (NodeState state : neighborDirectory.states()) {
            System.out.println(state);
        }

        System.out.println("---------------------------------------------------------------");
    }

    private void broadcastFailureEvent(FailureEvent event) {
        // Best-effort local broadcast:
        // send the failure event to all current neighbors.
        // This is decentralized because we send directly node-to-node,
        // not through a central dashboard/server.
        for (NodeAddress neighbor : neighborDirectory.addresses()) {
            // Do not send the failure event to the node that is already declared unreachable.
            if (neighbor.nodeId().equals(event.failedNodeId())) {
                continue;
            }

            nodeClient.sendFailureEvent(neighbor, event)
                    .thenAccept(sent -> {
                        if (sent) {
                            System.out.println(
                                    "[" + LocalDateTime.now() + "] "
                                            + "Forwarded FAILURE_EVENT "
                                            + event.eventId()
                                            + " from Node " + localNodeId
                                            + " to Node " + neighbor.nodeId()
                            );
                        } else {
                            System.out.println(
                                    "[" + LocalDateTime.now() + "] "
                                            + "Could not forward FAILURE_EVENT "
                                            + event.eventId()
                                            + " from Node " + localNodeId
                                            + " to Node " + neighbor.nodeId()
                            );
                        }
                    });
        }
    }

}