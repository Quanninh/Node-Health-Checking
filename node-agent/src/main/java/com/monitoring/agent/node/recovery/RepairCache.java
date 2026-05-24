package com.monitoring.agent.node.recovery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.node.NodeAddress;

public class RepairCache {

    private final Map<String, Set<NodeAddress>> adjacencyCache =
            new ConcurrentHashMap<>();

    private final Set<String> deficientNodes =
            ConcurrentHashMap.newKeySet();

    public void storeNeighbors(
            String nodeId,
            Set<NodeAddress> neighbors) {

        adjacencyCache.put(nodeId, neighbors);
    }

    public Set<NodeAddress> neighborsOf(String nodeId) {
        return adjacencyCache.getOrDefault(nodeId, Set.of());
    }

    public boolean areAdjacent(
            String a,
            String b) {

        return neighborsOf(a)
                .stream()
                .anyMatch(n -> n.nodeId().equals(b));
    }

    public void markDeficient(String nodeId) {
        deficientNodes.add(nodeId);
    }

    public void clearDeficient(String nodeId) {
        deficientNodes.remove(nodeId);
    }

    public Set<String> deficientNodes() {
        return deficientNodes;
    }
}