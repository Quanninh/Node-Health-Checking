package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.List;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

// TODO: This seems to be the main class, will return to this later
public class RecoveryCoordinator {

    /** TODO: Why 2? Should it be higher for more coverage? */
    private static final int RECOVERY_TTL = 2;

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;

    private final RecoveryControlService recoveryControlService;
    private final NetworkTopologyCache networkTopologyCache;

    private final DirectRepairCoordinator directRepairCoordinator;
    private final RewiringCoordinator rewiringCoordinator;

    private final ConvergenceMonitor convergenceMonitor;

    public RecoveryCoordinator(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            RecoveryControlService controlService,
            NetworkTopologyCache networkTopologyCache,
            DirectRepairCoordinator directRepairCoordinator,
            RewiringCoordinator rewiringCoordinator,
            ConvergenceMonitor convergenceMonitor) {

        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.recoveryControlService = controlService;
        this.networkTopologyCache = networkTopologyCache;
        this.directRepairCoordinator = directRepairCoordinator;
        this.rewiringCoordinator = rewiringCoordinator;
        this.convergenceMonitor = convergenceMonitor;
    }

    /**
     * Starts the recovery process for the local node.
     * 
     * @param repairEpoch the ID of the epoch (random UUID)
     */
    public void startSelfRecovery(String repairEpoch) {
        recoveryControlService.gossipSelfDeficient(repairEpoch, RECOVERY_TTL);

        // List<NodeAddress> deficientNodes = new
        // ArrayList<>(connectionManager.neighborAddresses());
        List<NodeAddress> deficientNodes = new ArrayList<>(networkTopologyCache.getDeficientNodes());

        NodeAddress candidate = directRepairCoordinator.findDirectCandidate(localAddress, deficientNodes);

        if (candidate != null) {
            connectionManager.addIfSpace(candidate, "direct repair");
            return;
        }

        // BUG: WHY AM I REWIRING MYSELF TO MYSELF?
        // rewiringCoordinator.attemptRewiring(localAddress, localAddress,
        // connectionManager.neighborAddresses());

        // BUG: I'M GETTING A BOOLEAN AND DOING NOTHING WITH IT...
        convergenceMonitor.hasConverged();
    }

}