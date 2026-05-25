package com.monitoring.agent.node.recovery;

// import java.util.UUID;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.util.Console;

/**
 * Removes a failed (disconnected...) node, then if node is deficient, starts
 * the recovery process.
 */
public class FailureRecoveryManager {

    private final ConnectionManager connectionManager;
    // Old recovery coordinator is commented out while the new RewiringCoordinator
    // owns RECOVERY packets. This path only gossiped DEFICIENT messages and did
    // not drive RewiringCoordinator.attemptRewiring.
    // private final RecoveryCoordinator recoveryCoordinator;

    public FailureRecoveryManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        // this.recoveryCoordinator = recoveryCoordinator;
    }

    /**
     * Removes a failed (disconnected...) node, then if node is deficient, starts
     * the recovery process.
     * 
     * @param failedNodeId the failed node
     */
    public void onRemoveUnreachableNeighbor(String failedNodeId) {
        // Console.log(
        // "Reached here in the code of failure recovery manager. Prepare to remove node
        // and start self recovery process.",
        // Constant.BG_BLUE);
        // connectionManager.remove(failedNodeId, "failure recovery removal");

        if (connectionManager.size() < connectionManager.getMaxNeighbors()) {
            Console.log("Recovery process starting...", Constant.BG_BLUE);
            // Old self-recovery path is intentionally disabled for the new rewiring
            // protocol. It only broadcasts DEFICIENT via RecoveryUDPService, which
            // conflicts with RewiringCoordinator's RECOVERY packet consumer.
            // recoveryCoordinator.startSelfRecovery(UUID.randomUUID().toString());
        }
    }

}
