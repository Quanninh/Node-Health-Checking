package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.monitoring.agent.node.NodeAddress;

public class DirectRepairCoordinator {

    private final RepairCache repairCache;
    private final RepairLockManager lockManager;

    public DirectRepairCoordinator(
            RepairCache repairCache,
            RepairLockManager lockManager) {

        this.repairCache = repairCache;
        this.lockManager = lockManager;
    }

    public NodeAddress findDirectCandidate(
            NodeAddress localNode,
            List<NodeAddress> deficientNodes) {

        List<NodeAddress> sorted =
                new ArrayList<>(deficientNodes);

        sorted.sort(Comparator.comparing(NodeAddress::nodeId));

        for (NodeAddress candidate : sorted) {

            if (candidate.nodeId().equals(localNode.nodeId())) {
                continue;
            }

            if (repairCache.areAdjacent(
                    localNode.nodeId(),
                    candidate.nodeId())) {
                continue;
            }

            if (lockManager.isLocked(candidate.nodeId())) {
                continue;
            }

            return candidate;
        }

        return null;
    }
}