package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

public class RewiringCoordinator {
    private final ConnectionManager connectionManager;
    private final NetworkTopologyCache repairCache;
    private final EdgeLockManager edgeLockManager;

    public RewiringCoordinator(
            ConnectionManager connectionManager,
            NetworkTopologyCache repairCache,
            EdgeLockManager edgeLockManager) {
        this.connectionManager = connectionManager;
        this.repairCache = repairCache;
        this.edgeLockManager = edgeLockManager;
    }

    public boolean attemptRewiring(NodeAddress deficientA, NodeAddress deficientB, List<NodeAddress> localNodes) {
        List<Edge> candidates = new ArrayList<>();

        for (NodeAddress c : localNodes) {
            for (NodeAddress d : repairCache.neighborsOf(c.nodeId())) {
                candidates.add(new Edge(c, d));
            }
        }
        Collections.shuffle(candidates);

        for (Edge edge : candidates) {
            NodeAddress candidateC = edge.left();
            NodeAddress candidateD = edge.right();

            boolean reserved = edgeLockManager.reserve(candidateC.nodeId(), candidateD.nodeId());

            if (!reserved) {
                // Failed to reserve the edge, skip
                continue;
            }

            try {
                /**
                 * Rewiring scheme:
                 * A       B
                 * |       |
                 * C - - - D
                 */
                boolean orientation1 = !repairCache.areAdjacent(deficientA.nodeId(), candidateD.nodeId())
                        && !repairCache.areAdjacent(deficientB.nodeId(), candidateC.nodeId());

                // BUG: CONNECTION MANAGER IS FOR SELF, CAN'T CONNECT OTHER NODES
                if (orientation1) {
                    connectionManager.remove(candidateD.nodeId(), "rewiring edge break");
                    connectionManager.addIfSpace(deficientA, "rewiring repair");
                    connectionManager.addIfSpace(deficientB, "rewiring repair");
                    return true;
                }

                boolean orientation2 = !repairCache.areAdjacent(deficientA.nodeId(), candidateD.nodeId())
                        && !repairCache.areAdjacent(deficientB.nodeId(), candidateC.nodeId());

            } catch (Exception e) {

                rollback(candidateC, candidateD);

            } finally {

                edgeLockManager.release(
                        candidateC.nodeId(),
                        candidateD.nodeId());
            }
        }
        return false;
    }

    private void rollback(
            NodeAddress c,
            NodeAddress d) {

        connectionManager.addIfSpace(
                d,
                "rollback restore");
    }

}