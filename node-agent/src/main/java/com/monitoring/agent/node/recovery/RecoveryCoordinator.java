package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;
import com.monitoring.agent.node.connection.*;

public final class RecoveryCoordinator {
    private final NodeAddress localAddress;

    private final ConnectionManager connectionManager;

    private final RepairCache repairCache;

    private final RecoveryControlService controlService;

    private final DirectRepairCoordinator directRepairCoordinator;

    public RecoveryCoordinator(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            RepairCache repairCache,
            RecoveryControlService controlService,
            DirectRepairCoordinator directRepairCoordinator) {

        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.repairCache = repairCache;
        this.controlService = controlService;
        this.directRepairCoordinator = directRepairCoordinator;
    }

    public void beginRecovery(String removedNodeId) {

        connectionManager.remove(
                removedNodeId,
                "failure recovery");

        if (connectionManager.size()
                >= connectionManager.getMaxNeighbors()) {
            return;
        }

        String epoch = UUID.randomUUID().toString();

        controlService.gossipDeficientNode(epoch, 2);

        directRepairCoordinator.attemptRepair(epoch);
    }
}