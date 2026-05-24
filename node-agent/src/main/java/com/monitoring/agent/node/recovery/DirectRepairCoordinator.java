package com.monitoring.agent.node.recovery;

import java.util.List;

import com.monitoring.agent.node.NodeAddress;

public class DirectRepairCoordinator {

    private final NetworkTopologyCache repairCache;
    // private final RepairLockManager lockManager;

    public DirectRepairCoordinator(NetworkTopologyCache repairCache
    // , RepairLockManager lockManager
    ) {
        this.repairCache = repairCache;
        // this.lockManager = lockManager;
    }

    /**
     * Finds a candidate to add as a new neighbor.
     * 
     * @param localNode
     * @param deficientNodes
     * @return a deficient candidate node
     */
    public NodeAddress findDirectCandidate(NodeAddress localNode, List<NodeAddress> deficientNodes) {
        for (NodeAddress candidate : deficientNodes) {
            // If the node is equal to self, skip
            if (candidate.nodeId().equals(localNode.nodeId())) {
                continue;
            }

            // If the node is already a neighbor, skip
            if (repairCache.areAdjacent(localNode.nodeId(), candidate.nodeId())) {
                continue;
            }

            // // If the node is locked, skip
            // if (lockManager.isLocked(candidate.nodeId())) {
            // continue;
            // }

            return candidate;
        }

        return null;
    }

}