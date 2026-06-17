package com.monitoring.agent.node.connection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.monitoring.agent.node.GossipMessageType;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.NodeState;
import com.monitoring.agent.node.NodeStatus;
import com.monitoring.agent.node.PhiAccrualFailure;
import com.monitoring.agent.util.Console;

/**
 * A directory to keep track of all neighbors of a given node and their states.
 */
public class NeighborDirectory {

    /**
     * Manager for a node's neighbors.
     */
    private final ConnectionManager connectionManager;

    /**
     * Mapping of node id and its state.
     * 
     * @see NodeState
     */
    private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();

    /** Index for iterating through neighbors (for failure detection). */
    private int nextIndex = 0;
    private final List<NodeAddress> shuffledNeighbors;

    public NeighborDirectory(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        syncStatesWithConnections();
        shuffledNeighbors = connectionManager.neighborAddresses();
    }

    /**
     * Gets the next node from the list of neighbors. Shuffles the list if the node
     * has retrieve all neighbors already to simulate randomness.
     * 
     * @return the next node, empty if node has no active neighbors
     */
    public synchronized Optional<NodeAddress> nextTargetNode() {
        // List<NodeAddress> neighbors = connectionManager.neighborAddresses();
        if (shuffledNeighbors.isEmpty()) {
            return Optional.empty();
        }

        if (nextIndex >= shuffledNeighbors.size()) {
            Collections.shuffle(shuffledNeighbors);
            nextIndex = 0;
        }

        NodeAddress selected = shuffledNeighbors.get(nextIndex);
        nextIndex++;
        return Optional.of(selected);
    }

    /**
     * Gets a list of helper nodes. These nodes must be reachable and not the same
     * node as target node.
     * 
     * @param targetNode the target node
     * @return the list of helper nodes
     */
    public List<NodeAddress> selectHelperNodes(NodeAddress targetNode) {
        List<NodeAddress> helperNodes = new ArrayList<>(connectionManager.neighborAddresses().stream()
                .filter(node -> (getStatus(node.nodeId()) != NodeStatus.UNREACHABLE)
                        && (!node.nodeId().equals(targetNode.nodeId())))
                .toList());
        Collections.shuffle(helperNodes);

        return helperNodes;
    }

    /**
     * Removes unreachable neighbors from the list of neighbors.
     */
    public synchronized void removeUnreachableNeighbors() {
        List<String> unreachableNodeIds = nodeStates.values().stream()
                .filter(state -> state.getStatus() == NodeStatus.UNREACHABLE)
                .map(state -> state.getNodeAddress().nodeId())
                .toList();

        for (String nodeId : unreachableNodeIds) {
            connectionManager.remove(nodeId, "Failure detector marked node UNREACHABLE.");
            nodeStates.remove(nodeId);
        }

        if (nextIndex > connectionManager.size()) {
            nextIndex = 0;
        }
    }

    /**
     * Syncs with the connection manager and gets the list of all addresses.
     * 
     * @return list of addresses
     * @see NodeAddress
     */
    public synchronized List<NodeAddress> addresses() {
        syncStatesWithConnections();
        return connectionManager.neighborAddresses();
    }

    /**
     * Marks the given node as ALIVE.
     * 
     * @param nodeId      the node id
     * @param phiDetector phi accrual failure
     * @see PhiAccrualFailure
     */
    public void markAlive(String nodeId, PhiAccrualFailure phiDetector, long pingSendTime) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markAlive(phiDetector, pingSendTime);
        }
    }

    /**
     * Marks the given node as WARNING.
     * 
     * @param nodeId the node id
     * @param phi    the phi value
     * @see PhiAccrualFailure
     */
    public void markWarning(String nodeId, double phi) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markWarning(phi);
        }
    }

    /**
     * Marks the given node as SUSPECTED.
     * 
     * @param nodeId the node id
     * @param phi    the phi value
     * @see PhiAccrualFailure
     */
    public void markSuspected(String nodeId, double phi) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markSuspected(phi);
        }
    }

    /**
     * Marks the given node as UNREACHABLE.
     * 
     * @param nodeId the node id
     * @param phi    the phi value
     * @see PhiAccrualFailure
     */
    public void markUnreachable(String nodeId, double phi) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markUnreachable(phi);
        }
    }

    /**
     * Gets the status of a node (part of NodeState).
     * 
     * @param nodeId
     * @return the status
     * @see NodeStatus
     * @see NodeState
     */
    public NodeStatus getStatus(String nodeId) {
        syncStatesWithConnections();
        NodeState state = nodeStates.get(nodeId);

        if (state == null) {
            return NodeStatus.UNKNOWN;
        }

        return state.getStatus();
    }

    /**
     * Gets the state of a node (includes #getStatus).
     * 
     * @param nodeId
     * @return the NodeState
     * @see NodeState
     */
    public Optional<NodeState> getState(String nodeId) {
        syncStatesWithConnections();
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    /**
     * Gets the address of the target node
     * 
     * @param targetNodeId the id of the target node
     * @return the node address
     * @see NodeAddress
     */
    public Optional<NodeAddress> getAddress(String targetNodeId) {
        syncStatesWithConnections();
        return connectionManager.neighborAddresses().stream()
                .filter(node -> node.nodeId().equals(targetNodeId))
                .findFirst();
    }

    /**
     * Syncs the neighbor states with the connection manager.
     */
    private void syncStatesWithConnections() {
        List<NodeAddress> currentNeighbors = connectionManager.neighborAddresses();
        Set<String> currentIds = new HashSet<>();

        for (NodeAddress address : currentNeighbors) {
            currentIds.add(address.nodeId());
            nodeStates.putIfAbsent(address.nodeId(), new NodeState(address));
        }

        nodeStates.keySet().removeIf(nodeId -> !currentIds.contains(nodeId));
    }

    /**
     * Gets the incarnation number of the node state.
     * 
     * @param nodeId the node ID
     * @return the incarnation number
     */
    public int incarnationNumber(String nodeId) {
        NodeState state = nodeStates.get(nodeId);

        if (state == null) {
            return 0;
        }

        return state.getIncarnationNumber();
    }

    /**
     * Applies the state of the
     * 
     * @param subjectNodeId
     * @param messageType
     * @param incarnationNumber
     */
    public void applyGossipStatus(String subjectNodeId, GossipMessageType messageType, int incarnationNumber) {
        NodeState state = nodeStates.get(subjectNodeId);

        if (state == null) {
            Console.log("Not a neighbor with " + subjectNodeId + " -> skip");
            return;
        }

        switch (messageType) {
            case SUSPECTED -> state.markSuspectedFromGossip(incarnationNumber);
            case UNREACHABLE -> state.markUnreachableFromGossip(incarnationNumber);
            case ALIVE -> state.markAliveFromGossip(incarnationNumber);
        }
    }

    /**
     * Gets list of states sort by ID.
     * 
     * @return sorted list of states
     * @see NodeState
     */
    public List<NodeState> states() {
        syncStatesWithConnections();
        List<NodeState> states = new ArrayList<>(nodeStates.values());
        states.sort(Comparator.comparing(state -> state.getNodeAddress().nodeId()));
        return states;
    }

}
