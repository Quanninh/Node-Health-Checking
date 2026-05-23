package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NeighborDirectory {
    private final ConnectionManager connectionManager;
    private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();
    private int nextIndex = 0;

    NeighborDirectory(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        syncStatesWithConnections();
    }

    synchronized Optional<NodeAddress> nextTargetNode() {
        List<NodeAddress> neighbors = reachableNeighbors();
        if (neighbors.isEmpty()) {
            return Optional.empty();
        }

        if (nextIndex >= neighbors.size()) {
            Collections.shuffle(neighbors);
            nextIndex = 0;
            log("Completed one full neighbor cycle. Shuffled reachable neighbors: " + neighbors);
        }

        NodeAddress selected = neighbors.get(nextIndex);
        nextIndex = (nextIndex + 1) % neighbors.size();
        return Optional.of(selected);
    }

    synchronized List<NodeAddress> selectHelperNodes(NodeAddress targetNode) {
        List<NodeAddress> helpers = new ArrayList<>();

        for (NodeAddress node : connectionManager.addresses()) {
            if (node.nodeId().equals(targetNode.nodeId())) {
                continue;
            }
            if (getStatus(node.nodeId()) == NodeStatus.UNREACHABLE) {
                continue;
            }
            helpers.add(node);
        }

        Collections.shuffle(helpers);
        return helpers;
    }

    synchronized void removeUnreachableNeighbors() {
        List<String> unreachableNodeIds = nodeStates.values().stream()
                .filter(state -> state.status() == NodeStatus.UNREACHABLE)
                .map(state -> state.address().nodeId())
                .toList();

        for (String nodeId : unreachableNodeIds) {
            connectionManager.remove(nodeId, "failure detector marked node unreachable");
            nodeStates.remove(nodeId);
        }

        if (nextIndex > connectionManager.size()) {
            nextIndex = 0;
        }
    }

    synchronized boolean contains(String nodeId) {
        return connectionManager.contains(nodeId);
    }

    synchronized int size() {
        return connectionManager.size();
    }

    synchronized int maxNeighbors() {
        return connectionManager.maxNeighbors();
    }

    synchronized List<NodeAddress> addresses() {
        syncStatesWithConnections();
        return connectionManager.addresses();
    }

    synchronized void markAlive(String nodeId, PhiAccrualFailureDetector phiDetector) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);
        if (state != null) {
            state.markAlive(phiDetector);
        }
    }

    synchronized void markWarning(String nodeId, double phi) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);
        if (state != null) {
            state.markWarning(phi);
        }
    }

    synchronized void markSuspected(String nodeId, double phi) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);
        if (state != null) {
            state.markSuspected(phi);
        }
    }

    synchronized void markUnreachable(String nodeId, double phi) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);
        if (state != null) {
            state.markUnreachable(phi);
        }
    }

    synchronized NodeStatus getStatus(String nodeId) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);
        if (state == null) {
            return NodeStatus.UNKNOWN;
        }
        return state.status();
    }

    synchronized Optional<NodeState> getState(String nodeId) {
        syncStatesWithConnections();
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    synchronized List<NodeState> states() {
        syncStatesWithConnections();
        List<NodeState> states = new ArrayList<>(nodeStates.values());
        states.sort(Comparator.comparing(state -> state.address().nodeId()));
        return states;
    }

    private List<NodeAddress> reachableNeighbors() {
        syncStatesWithConnections();
        return connectionManager.addresses().stream()
                .filter(node -> getStatusWithoutSync(node.nodeId()) != NodeStatus.UNREACHABLE)
                .toList();
    }

    private NodeStatus getStatusWithoutSync(String nodeId) {
        NodeState state = nodeStates.get(nodeId);
        return state == null ? NodeStatus.UNKNOWN : state.status();
    }

    private void syncStatesWithConnections() {
        List<NodeAddress> currentNeighbors = connectionManager.addresses();
        Set<String> currentIds = new HashSet<>();

        for (NodeAddress address : currentNeighbors) {
            currentIds.add(address.nodeId());
            nodeStates.putIfAbsent(address.nodeId(), new NodeState(address));
        }

        nodeStates.keySet().removeIf(nodeId -> !currentIds.contains(nodeId));
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now() + "] " + message);
    }
}
