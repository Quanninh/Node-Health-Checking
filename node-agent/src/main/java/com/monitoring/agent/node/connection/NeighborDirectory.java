package com.monitoring.agent.node.connection;

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

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.GossipMessageType;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.NodeState;
import com.monitoring.agent.node.NodeStatus;
import com.monitoring.agent.node.PhiAccrualFailure;

/**
 * A directory to keep track of all neighbors of a given node and their states.
 */
public class NeighborDirectory {

    private final ConnectionManager connectionManager;
    /**
     * Mapping of node id and its state.
     * 
     * @see NodeState
     */
    private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();

    /** Index for iterating through neighbors (for failure detection). */
    private int nextIndex = 0;

    public NeighborDirectory(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        syncStatesWithConnections();
    }

    /**
     * Gets the next node from the list of neighbors. Shuffles the list if the node
     * has retrieve all neighbors already to simulate randomness.
     * 
     * @return the next node, empty if node has no active neighbors
     */
    public synchronized Optional<NodeAddress> nextTargetNode() {
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

    /**
     * Gets a list of helper nodes. These nodes must be reachable and not the same
     * node as target node.
     * 
     * @param targetNode the target node
     * @return the list of helper nodes
     */
    public List<NodeAddress> selectHelperNodes(NodeAddress targetNode) {
        List<NodeAddress> helperNodes = new ArrayList<>(connectionManager.addresses().stream()
                .filter(node -> (getStatus(node.nodeId()) != NodeStatus.UNREACHABLE)
                        && (!node.nodeId().equals(targetNode.nodeId())))
                .toList());
        Collections.shuffle(helperNodes);

        return helperNodes;
    }

    synchronized void removeUnreachableNeighbors() {
        List<String> unreachableNodeIds = nodeStates.values().stream()
                .filter(state -> state.getStatus() == NodeStatus.UNREACHABLE)
                .map(state -> state.getNodeAddress().nodeId())
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
        return connectionManager.containsNode(nodeId);
    }

    public synchronized int size() {
        return connectionManager.size();
    }

    synchronized int maxNeighbors() {
        return connectionManager.getMaxNeighbors();
    }

    public synchronized List<NodeAddress> addresses() {
        syncStatesWithConnections();
        return connectionManager.addresses();
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
     * @return
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
        return connectionManager.addresses().stream()
                .filter(node -> node.nodeId().equals(targetNodeId))
                .findFirst();
    }

    private List<NodeAddress> reachableNeighbors() {
        syncStatesWithConnections();
        return connectionManager.addresses().stream()
                .filter(node -> getStatusWithoutSync(node.nodeId()) != NodeStatus.UNREACHABLE)
                .toList();
    }

    private NodeStatus getStatusWithoutSync(String nodeId) {
        NodeState state = nodeStates.get(nodeId);
        return state == null ? NodeStatus.UNKNOWN : state.getStatus();
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

    // TODO: Javadoc
    /**
     * 
     * @param nodeId
     * @return
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
            System.out.println(
                    "\n[" + Constant.NOW() + "] "
                            + "Gossip subjectNodeId " + subjectNodeId
                            + " is not in this node's neighborList. Message is recorded but not added.");
            return;
        }

        switch (messageType) {
            case SUSPECTED -> state.markSuspectedFromGossip(incarnationNumber);
            case UNREACHABLE -> state.markUnreachableFromGossip(incarnationNumber);
            case ALIVE -> state.markAliveFromGossip(incarnationNumber);
            case JOIN -> {
                if (incarnationNumber > state.getIncarnationNumber()) {
                    state.markAliveFromGossip(incarnationNumber);
                }
            }
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
