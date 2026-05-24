package com.monitoring.agent.node.recovery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.node.NodeAddress;

/**
 * Temporary cache for saving network topology. The cache can know which nodes
 * are adjacent to another, and whether a node has enough neighbors (i.e.
 * sufficient)
 * or not (i.e. deficient).
 */
public class NetworkTopologyCache {

    /** The map of a node and its neighbors. */
    private final Map<String, Set<NodeAddress>> adjacencyCache = new ConcurrentHashMap<>();

    /** The set of deficient nodes. */
    private final Set<NodeAddress> deficientNodes = ConcurrentHashMap.newKeySet();

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
        deficientNodes.add(node);
    }

    /**
     * Marks a node as sufficient (not deficient).
     * 
     * @param node the sufficient node
     */
    public void clearDeficient(NodeAddress node) {
        deficientNodes.remove(node);
    }

    /**
     * Gets the list of deficient nodes.
     * 
     * @return the list of deficient nodes
     */
    public Set<NodeAddress> getDeficientNodes() {
        return deficientNodes;
    }

}