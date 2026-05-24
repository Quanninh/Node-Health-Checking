package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.List;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

public class RecoveryCoordinator {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;

    private final RecoveryControlService controlService;
    private final NetworkTopologyCache repairCache;

    private final DirectRepairCoordinator directRepairCoordinator;
    private final RewiringCoordinator rewiringCoordinator;

    private final ConvergenceMonitor convergenceMonitor;

    public RecoveryCoordinator(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            RecoveryControlService controlService,
            NetworkTopologyCache repairCache,
            DirectRepairCoordinator directRepairCoordinator,
            RewiringCoordinator rewiringCoordinator,
            ConvergenceMonitor convergenceMonitor) {

        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.controlService = controlService;
        this.repairCache = repairCache;
        this.directRepairCoordinator = directRepairCoordinator;
        this.rewiringCoordinator = rewiringCoordinator;
        this.convergenceMonitor = convergenceMonitor;
    }

    public void startRecovery(String repairEpoch) {

        controlService.gossipDeficientNode(
                repairEpoch,
                2);

        List<NodeAddress> deficientNodes = new ArrayList<>(
                connectionManager.neighborAddresses());

        NodeAddress candidate = directRepairCoordinator.findDirectCandidate(
                localAddress,
                deficientNodes);

        if (candidate != null) {

            connectionManager.addIfSpace(
                    candidate,
                    "direct repair");

            return;
        }

        rewiringCoordinator.attemptRewiring(
                localAddress,
                localAddress,
                deficientNodes);

        convergenceMonitor.hasConverged();
    }
}