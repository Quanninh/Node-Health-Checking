package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

public final class DirectRepairCoordinator {
    private final NodeAddress localAddress;

    private final ConnectionManager connectionManager;

    private final RepairCache repairCache;

    private final RepairLockManager lockManager;

    private final RecoveryControlService controlService;

    public DirectRepairCoordinator(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            RepairCache repairCache,
            RepairLockManager lockManager,
            RecoveryControlService controlService) {

        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.repairCache = repairCache;
        this.lockManager = lockManager;
        this.controlService = controlService;
    }

    public void attemptRepair(String repairEpoch) {

        Optional<NodeAddress> candidate = selectCandidate();

        if (candidate.isEmpty()) {
            return;
        }

        executeRepair(candidate.get(), repairEpoch);
    }

    private Optional<NodeAddress> selectCandidate() {

        List<DeficientNodeRecord> candidates =
                new ArrayList<>(
                        repairCache.deficientNodes().values());

        candidates.sort(
                Comparator.comparing(DeficientNodeRecord::nodeId));

        for (DeficientNodeRecord record : candidates) {

            NodeAddress candidate = record.node();

            if (candidate.nodeId()
                    .equals(localAddress.nodeId())) {
                continue;
            }

            if (connectionManager.containsNode(
                    candidate.nodeId())) {
                continue;
            }

            if (lockManager.isLocked(
                    candidate.nodeId())) {
                continue;
            }

            if (localAddress.nodeId()
                    .compareTo(candidate.nodeId()) > 0) {
                continue;
            }

            return Optional.of(candidate);
        }

        return Optional.empty();
    }

    private void executeRepair(
            NodeAddress target,
            String repairEpoch) {

        boolean locked =
                controlService.requestNodeLock(target);

        if (!locked) {
            return;
        }

        try {

            boolean accepted =
                    controlService.requestDirectRepair(
                            target,
                            repairEpoch);

            if (!accepted) {
                return;
            }

            connectionManager.addIfSpace(
                    target,
                    "direct repair");

            controlService.broadcastRepairSuccess(
                    localAddress,
                    target,
                    repairEpoch);

        } finally {
            controlService.releaseNodeLock(target);
        }
    }
}
    
