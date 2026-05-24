package com.monitoring.agent.node.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

public class RewiringCoordinator {
    private final ConnectionManager connectionManager;
    private final RepairCache repairCache;
    private final EdgeLockManager edgeLockManager;

    public RewiringCoordinator(
            ConnectionManager connectionManager,
            RepairCache repairCache,
            EdgeLockManager edgeLockManager) {

        this.connectionManager = connectionManager;
        this.repairCache = repairCache;
        this.edgeLockManager = edgeLockManager;
    }

    public boolean attemptRewiring(
            NodeAddress deficientA,
            NodeAddress deficientB,
            List<NodeAddress> localNodes) {

        List<EdgeCandidate> candidates =
                new ArrayList<>();

        for (NodeAddress c : localNodes) {

            for (NodeAddress d :
                    repairCache.neighborsOf(c.nodeId())) {

                candidates.add(
                        new EdgeCandidate(c, d));
            }
        }
        Collections.shuffle(candidates);

        for (EdgeCandidate edge : candidates) {

            NodeAddress c = edge.left();
            NodeAddress d = edge.right();

            boolean reserved =
                    edgeLockManager.reserve(
                            c.nodeId(),
                            d.nodeId());

            if (!reserved) {
                continue;
            }

            try {

                boolean orientation1 =
                        !repairCache.areAdjacent(
                                deficientA.nodeId(),
                                d.nodeId())
                        &&
                        !repairCache.areAdjacent(
                                deficientB.nodeId(),
                                c.nodeId());

                if (orientation1) {

                    connectionManager.remove(
                            d.nodeId(),
                            "rewiring edge break");

                    connectionManager.addIfSpace(
                            deficientA,
                            "rewiring repair");

                    connectionManager.addIfSpace(
                            deficientB,
                            "rewiring repair");

                    return true;
                }

            } catch (Exception e) {

                rollback(c, d);

            } finally {

                edgeLockManager.release(
                        c.nodeId(),
                        d.nodeId());
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