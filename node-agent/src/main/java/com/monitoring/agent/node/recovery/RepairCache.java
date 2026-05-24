package com.monitoring.agent.node.recovery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.node.NodeAddress;

public final class RepairCache {

    private final Map<String, DeficientNodeRecord> deficientNodes =
            new ConcurrentHashMap<>();

    private final Map<String, Set<NodeAddress>> adjacencyCache =
            new ConcurrentHashMap<>();

    private final Set<String> processedMessages =
            ConcurrentHashMap.newKeySet();

    public boolean markProcessed(String id) {
        return processedMessages.add(id);
    }

    public void storeDeficientNode(
            DeficientNodeRecord record) {

        deficientNodes.put(record.nodeId(), record);
    }

    public void removeDeficientNode(String nodeId) {
        deficientNodes.remove(nodeId);
    }

    public Map<String, DeficientNodeRecord> deficientNodes() {
        return deficientNodes;
    }

    public void storeNeighbors(
            String nodeId,
            Set<NodeAddress> neighbors) {

        adjacencyCache.put(nodeId, neighbors);
    }

    public Set<NodeAddress> neighbors(String nodeId) {
        return adjacencyCache.getOrDefault(nodeId, Set.of());
    }
}