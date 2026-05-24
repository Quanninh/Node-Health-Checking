package com.monitoring.agent.node.recovery;

import java.util.UUID;

import com.monitoring.agent.node.connection.ConnectionManager;

/**
 * Removes a failed (disconnected...) node, then if node is deficient, starts
 * the recovery process.
 */
public class FailureRecoveryManager {

    private final ConnectionManager connectionManager;
    private final RecoveryCoordinator recoveryCoordinator;

    public FailureRecoveryManager(ConnectionManager connectionManager, RecoveryCoordinator recoveryCoordinator) {
        this.connectionManager = connectionManager;
        this.recoveryCoordinator = recoveryCoordinator;
    }

    /**
     * Removes a failed (disconnected...) node, then if node is deficient, starts
     * the recovery process.
     * 
     * @param failedNodeId the failed node
     */
    public void onNeighborFailure(String failedNodeId) {
        connectionManager.remove(failedNodeId, "failure recovery removal");

        if (connectionManager.size() < connectionManager.getMaxNeighbors()) {

            recoveryCoordinator.startSelfRecovery(
                    UUID.randomUUID().toString());
        }
    }

}