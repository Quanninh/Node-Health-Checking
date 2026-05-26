package com.monitoring.agent.node;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.monitoring.agent.constant.Constant;
import static com.monitoring.agent.constant.Constant.UNREACHABLE_CLEANUP_INTERVAL_SECONDS;
import com.monitoring.agent.node.connection.NeighborDirectory;
import com.monitoring.agent.node.recovery.RecoveryUDPService;
import com.monitoring.agent.util.Console;

/**
 * Periodically sends out ping to this node's neighbors to detect if any node
 * fails.
 */
public class FailureDetector {

    private final String localNodeId;
    private final NeighborDirectory neighborDirectory;
    private final NodeClient nodeClient;
    private final DashboardReporter dashboardReporter;
    private final PhiAccrualFailure phiDetector;
    private final GossipService gossipService;
    private final RecoveryUDPService recoveryUdpService;
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
            RecoveryUDPService recoveryUdpService,
            int probeIntervalSeconds,
            double unreachableThreshold) {
        this.localNodeId = localNodeId;
        this.neighborDirectory = neighborDirectory;
        this.nodeClient = nodeClient;
        this.dashboardReporter = dashboardReporter;
        this.phiDetector = phiDetector;
        this.gossipService = gossipService;
        this.recoveryUdpService = recoveryUdpService;
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

        scheduler.scheduleAtFixedRate(
                this::removeUnreachableNeighborsSafely,
                UNREACHABLE_CLEANUP_INTERVAL_SECONDS,
                UNREACHABLE_CLEANUP_INTERVAL_SECONDS,
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
            Console.log("Failure detector error: "
                    + exception.getMessage(), Constant.RED);
        }
    }

    private void removeUnreachableNeighborsSafely() {
        try {
            neighborDirectory.removeUnreachableNeighbors();
            recoveryUdpService.gossipSelfIfDeficient("unreachable neighbor cleanup");
        } catch (Exception exception) {
            Console.log("Unreachable-neighbor cleanup error: "
                    + exception.getMessage(), Constant.RED);
        }
    }

    /**
     * Gets the next neighbor node and tries to ping it.
     */
    private void runOneProbe() {
        Optional<NodeAddress> selectedTarget = neighborDirectory.nextTargetNode();

        if (selectedTarget.isEmpty()) {
            Console.log("No reachable neighbor nodes configured. Nothing to ping.", Constant.PURPLE);
            return;
        }

        NodeAddress targetNode = selectedTarget.get();

        long pingSendTime = System.currentTimeMillis();

        Console.log("Node " + localNodeId + " directly pings targetNode "
                + targetNode.nodeId() + " at " + targetNode.host() + ":" + targetNode.port(), Constant.CYAN);

        nodeClient.ping(targetNode).thenAccept(ackReceived -> {
            if (ackReceived) {
                handleAckReceived(targetNode, "direct ping", pingSendTime);
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

        long pingSendTime = System.currentTimeMillis();

        Console.log("Direct ping failed for targetNode " + targetNode.nodeId()
                + ". helperNodes selected from neighborList: " + helperNodes, Constant.RED);

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
                        handleAckReceived(targetNode, "indirect ping-req by helperNodes", pingSendTime);
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
    private void handleAckReceived(NodeAddress targetNode, String source, long pingSendTime) {
        NodeStatus previousStatus = neighborDirectory.getStatus(targetNode.nodeId());

        if (previousStatus == NodeStatus.UNREACHABLE) {
            Console.log("ACK received from Node " + targetNode.nodeId() + " through " + source
                    + ", but local state is UNREACHABLE. This node must send JOIN and re-enter as a new node instance.",
                    Constant.YELLOW);
            return;
        }

        neighborDirectory.markAlive(targetNode.nodeId(), phiDetector, pingSendTime);

        Console.log("ACK received from Node " + targetNode.nodeId() + " through " + source + ". Status becomes ALIVE.",
                Constant.CYAN);

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
                state.getSlidingWindowSeconds(),
                state.getLastAckTimeMs(),
                System.currentTimeMillis());

        NodeStatus phiStatus = phiDetector.determineStatus(phi);

        if (phiStatus == NodeStatus.UNREACHABLE) {
            handleUnreachableNode(targetNode, phi, unreachableThreshold);
            return;
        }

        long now = System.currentTimeMillis();

        double elapsedSeconds = (now - state.getLastAckTimeMs()) / 1000.0;

        if (state.getLastAckTimeMs() <= 0) {
            neighborDirectory.markSuspected(targetNode.nodeId(), 0.0);
            gossipService.gossipSuspect(targetNode);

            System.out.println("targetNode " + targetNode.nodeId()
                    + " has never replied. Mark SUSPECTED first.");

            return;
        }

        if (state.getSlidingWindowSeconds() == null
                || state.getSlidingWindowSeconds().size() < 2) {

            double coldStartTimeoutSeconds = probeIntervalSeconds * 3.0;

            if (elapsedSeconds >= coldStartTimeoutSeconds) {
                handleUnreachableNode(
                        targetNode,
                        unreachableThreshold,
                        unreachableThreshold);
                return;
            }

            if (phiStatus == NodeStatus.WARNING) {
                neighborDirectory.markWarning(targetNode.nodeId(), phi);
            } else {
                neighborDirectory.markSuspected(targetNode.nodeId(), phi);
                gossipService.gossipSuspect(targetNode);
            }

            Console.log("targetNode " + targetNode.nodeId()
                    + " has no direct/indirect ACK. phi="
                    + String.format("%.4f", phi)
                    + ", status=" + neighborDirectory.getStatus(targetNode.nodeId())
                    + ". It is not declared unreachable yet.", Constant.YELLOW);
        }
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

        Console.log("Node " + localNodeId
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
        Console.log("\n----- Local Neighbor Node States at Node " + localNodeId + " -----");

        for (NodeState state : neighborDirectory.states()) {
            Console.println(state + "\n");
        }

        Console.println("---------------------------------------------------------------");
    }
}
