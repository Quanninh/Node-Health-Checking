package com.monitoring.agent.node.recovery;

import java.util.UUID;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

public class FailureRecoveryManager {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final RecoveryCoordinator recoveryCoordinator;

    public FailureRecoveryManager(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            RecoveryCoordinator recoveryCoordinator) {

        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.recoveryCoordinator = recoveryCoordinator;
    }

    public void onNeighborFailure(String failedNodeId) {

        connectionManager.remove(
                failedNodeId,
                "failure recovery removal");

        if (connectionManager.size()
                <
                connectionManager.getMaxNeighbors()) {

            recoveryCoordinator.startRecovery(
                    UUID.randomUUID().toString());
        }
    }
}