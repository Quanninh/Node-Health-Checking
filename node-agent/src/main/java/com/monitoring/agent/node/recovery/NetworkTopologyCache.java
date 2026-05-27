package com.monitoring.agent.node.recovery;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

/**
 * Temporary cache for saving network topology. The cache can know which nodes
 * are adjacent to another, and whether a node has enough neighbors (i.e.
 * sufficient)
 * or not (i.e. deficient).
 */
public class NetworkTopologyCache {

    /** The map of a node and its neighbors. */
    @Deprecated
    private final Map<String, Set<NodeAddress>> adjacencyCache = new ConcurrentHashMap<>();

    /** Deficient nodes discovered through recovery gossip, keyed by node id. */
    private final Map<String, DeficientNodeRecord> deficientNodes = new ConcurrentHashMap<>();

    /**
     * Stores a connection between a node and its neighbor.
     * 
     * @param nodeId    the node
     * @param neighbors its neighbor
     */
    public void storeNeighbors(String nodeId, Set<NodeAddress> neighbors) {
        adjacencyCache.put(nodeId, neighbors);
    }

    /**
     * Finds the neighbors of a node.
     * 
     * @param nodeId the node
     * @return its neighbors
     */
    public Set<NodeAddress> neighborsOf(String nodeId) {
        return adjacencyCache.getOrDefault(nodeId, Set.of());
    }

    /**
     * Checks if two nodes are adjacent.
     * 
     * @return whether the two nodes are adjacent
     */
    public boolean areAdjacent(String a, String b) {
        return neighborsOf(a).stream().anyMatch(n -> n.nodeId().equals(b));
    }

    /**
     * Marks a node as deficient.
     * 
     * @param node the deficient node
     */
    public void markDeficient(NodeAddress node) {
        markDeficient(new DeficientNodeRecord(node, -1, "", Instant.now(), 0));
    }

    /**
     * Stores the latest deficient-node record for a node.
     * 
     * @param record the deficient-node record
     */
    public void markDeficient(DeficientNodeRecord record) {
        if (record == null || record.node() == null) {
            Console.log("Invalid record");
            return;
        }

        deficientNodes.merge(record.nodeId(), record, (existing, incoming) -> {
            if (incoming.incarnationNumber() > existing.incarnationNumber()) {
                return incoming;
            }

            if (incoming.incarnationNumber() == existing.incarnationNumber()
                    && incoming.timestamp().isAfter(existing.timestamp())) {
                return incoming;
            }

            return existing;
        });
    }

    /**
     * Marks a node as sufficient (not deficient).
     * 
     * @param node the sufficient node
     */
    public void clearDeficient(NodeAddress node) {
        if (node != null) {
            deficientNodes.remove(node.nodeId());
        }
    }

    /**
     * Gets the deficient-node records discovered by this node.
     * 
     * @return deficient-node records
     */
    public Set<DeficientNodeRecord> getDeficientNodeRecords() {
        return Set.copyOf(deficientNodes.values());
    }

}
