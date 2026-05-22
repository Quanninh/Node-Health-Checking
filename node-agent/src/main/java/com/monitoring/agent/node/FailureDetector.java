package com.monitoring.agent.node;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.monitoring.agent.constant.Constant;

/**
 * 
 */
public class FailureDetector {

    private final String localNodeId;
    private final NeighborDirectory neighborDirectory;
    private final NodeClient nodeClient;
    private final DashboardReporter dashboardReporter;
    private final PhiAccrualFailure phiDetector;
    private final GossipService gossipService;
    private final int probeIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final double unreachableThreshold;

    public FailureDetector(
            String localNodeId,
            NeighborDirectory neighborDirectory,
            NodeClient nodeClient,
            DashboardReporter dashboardReporter,
            PhiAccrualFailure phiDetector,
            GossipService gossipService,
            int probeIntervalSeconds,
            double unreachableThreshold) {
        this.localNodeId = localNodeId;
        this.neighborDirectory = neighborDirectory;
        this.nodeClient = nodeClient;
        this.dashboardReporter = dashboardReporter;
        this.phiDetector = phiDetector;
        this.gossipService = gossipService;
        this.probeIntervalSeconds = probeIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.unreachableThreshold = unreachableThreshold;
    }

    /**
     * Schedules to run one probe every configured interval.
     * 
     * @see #probeIntervalSeconds
     * @see #runOneProbeSafely()
     */
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::runOneProbeSafely,
                probeIntervalSeconds,
                probeIntervalSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Wrapper to run one probe safely (catches all errors).
     * 
     * @see #runOneProbe()
     */
    private void runOneProbeSafely() {
        try {
            runOneProbe();
        } catch (Exception exception) {
            System.out.println("\n[" + Constant.NOW() + "] " + Constant.RED + "Failure detector error: "
                    + exception.getMessage() + Constant.RESET);
        }
    }

    /**
     * Gets the next neighbor node and tries to ping it.
     */
    private void runOneProbe() {
        Optional<NodeAddress> selectedTarget = neighborDirectory.nextTargetNode();

        if (selectedTarget.isEmpty()) {
            System.out.println("\n[" + Constant.NOW() + "] " + Constant.RED
                    + "No reachable neighbor nodes configured. Nothing to ping." + Constant.RESET);
            return;
        }

        NodeAddress targetNode = selectedTarget.get();

        System.out.println(
                "\n[" + Constant.NOW() + "] " + Constant.CYAN + "Node " + localNodeId + " directly pings targetNode "
                        + targetNode.nodeId() + " at " + targetNode.host() + ":" + targetNode.port() + Constant.RESET);

        nodeClient.ping(targetNode).thenAccept(ackReceived -> {
            if (ackReceived) {
                handleAckReceived(targetNode, "direct ping");
                printLocalNodeStates();
                return;
            }

            handleDirectPingFailure(targetNode);
        });
    }

    /**
     * Handles when node can't ping the targeted node. The node asks a group of
     * "helper nodes" to send pings to the targeted node (PING_REQ).
     * 
     * @param targetNode the node that this node can't ping
     */
    private void handleDirectPingFailure(NodeAddress targetNode) {
        List<NodeAddress> helperNodes = neighborDirectory.selectHelperNodes(targetNode);

        System.out.println("\n[" + Constant.NOW() + "] " + Constant.RED + "Direct ping failed for targetNode "
                + targetNode.nodeId() + ". helperNodes selected from neighborList: " + helperNodes + Constant.RESET);

        if (helperNodes.isEmpty()) {
            handleNoAckAfterDirectAndIndirect(targetNode);
            return;
        }

        List<CompletableFuture<Boolean>> helperChecks = helperNodes.stream()
                .map(helperNode -> nodeClient.pingReq(helperNode, targetNode))
                .toList();

        CompletableFuture
                .allOf(helperChecks.toArray(CompletableFuture[]::new))
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

    /**
     * Handles when the ACK from the target node is received successfully.
     * 
     * @param targetNode the target node
     * @param source     the source of the ACK (direct, indirect...)
     */
    private void handleAckReceived(NodeAddress targetNode, String source) {
        NodeStatus previousStatus = neighborDirectory.getStatus(targetNode.nodeId());

        if (previousStatus == NodeStatus.UNREACHABLE) {
            System.out.println("\n[" + Constant.NOW() + "] " + Constant.YELLOW + "ACK received from Node "
                    + targetNode.nodeId() + " through " + source
                    + ", but local state is UNREACHABLE. This node must send JOIN and re-enter as a new node instance."
                    + Constant.RESET);
            return;
        }

        neighborDirectory.markAlive(targetNode.nodeId(), phiDetector);

        System.out.println("\n[" + Constant.NOW() + "] " + Constant.CYAN + "ACK received from Node "
                + targetNode.nodeId() + " through " + source + ". Status becomes ALIVE." + Constant.RESET);

        if (previousStatus == NodeStatus.SUSPECTED
                || previousStatus == NodeStatus.WARNING
                || previousStatus == NodeStatus.UNKNOWN) {
            gossipService.gossipAlive(targetNode);
        }
    }

    /**
     * Handle when the target node can't be pinged in any way (directly or
     * indirectly). The status of the target node is determined by the Phi Accural
     * Failure. If the node is Suspected to be down (SUSPECTED), this information is
     * gossiped to other nodes. If the node status is UNREACHABLE, the situation is
     * handled by {@link #handleUnreachableNode(NodeAddress, double, double)}.
     * 
     * @param targetNode
     * @see PhiAccrualFailure
     */
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
            handleUnreachableNode(targetNode, phi, unreachableThreshold);
            return;
        }

        if (phiStatus == NodeStatus.WARNING) {
            neighborDirectory.markWarning(targetNode.nodeId(), phi);
        } else {
            neighborDirectory.markSuspected(targetNode.nodeId(), phi);
            gossipService.gossipSuspect(targetNode);
        }

        System.out.println("\n[" + Constant.NOW() + "] "
                + "targetNode " + targetNode.nodeId()
                + " has no direct/indirect ACK. phi="
                + String.format("%.4f", phi)
                + ", status=" + neighborDirectory.getStatus(targetNode.nodeId())
                + ". It is not declared unreachable yet.");
    }

    /**
     * Marks a node as UNREACHABLE. Gossips this information to all other nodes.
     * 
     * @param targetNode           the unreachable node
     * @param phi                  the phi value
     * @param unreachableThreshold the unreachable threshold
     */
    private void handleUnreachableNode(NodeAddress targetNode, double phi, double unreachableThreshold) {
        NodeStatus previousStatus = neighborDirectory.getStatus(targetNode.nodeId());

        neighborDirectory.markUnreachable(targetNode.nodeId(), phi);

        System.out.println("\n[" + Constant.NOW() + "] "
                + "Node " + localNodeId
                + " marks targetNode " + targetNode.nodeId()
                + " as UNREACHABLE. phi="
                + String.format("%.4f", phi)
                + ". It must rejoin as a new node if it comes back.");

        if (previousStatus != NodeStatus.UNREACHABLE) {
            gossipService.gossipUnreachable(targetNode);
            dashboardReporter.reportFailure(targetNode, phi, unreachableThreshold);
        }
    }

    /**
     * Prints the states of this node's neighbor.
     */
    private void printLocalNodeStates() {
        System.out.println("----- Local Neighbor Node States at Node " + localNodeId + " -----");

        for (NodeState state : neighborDirectory.states()) {
            System.out.println(state + "\n");
        }

        System.out.println("---------------------------------------------------------------");
    }
}
