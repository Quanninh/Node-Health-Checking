package com.monitoring.agent.node.recovery;

public class FailureRecoveryManager {
    private final RecoveryCoordinator recoveryCoordinator;

    public FailureRecoveryManager(
            RecoveryCoordinator recoveryCoordinator) {

        this.recoveryCoordinator = recoveryCoordinator;
    }

    public void onNodeFailure(String nodeId) {

        recoveryCoordinator.beginRecovery(nodeId);
    }
}
