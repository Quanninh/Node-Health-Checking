package com.monitoring.agent.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.constant.Constant;

/**
 * A directory to keep track of all neighbors of a given node and their states.
 */
public class NeighborDirectory {

    /** List of neighbors. */
    private final List<NodeAddress> neighborList;

    /**
     * Mapping of node id and its state.
     * 
     * @see NodeState
     */
    private final Map<String, NodeState> nodeStates;

    /** Index for iterating through neighbors (for failure detection). */
    private int nextIndex = 0;

    public NeighborDirectory(List<NodeAddress> neighborList) {
        this.neighborList = new ArrayList<>(neighborList);
        this.nodeStates = new ConcurrentHashMap<>();

        for (NodeAddress node : neighborList) {
            nodeStates.put(node.nodeId(), new NodeState(node));
        }
    }

    /**
     * Gets the next node from the list of neighbors. Shuffles the list if the node
     * has retrieve all neighbors already to simulate randomness.
     * 
     * @return the next node, empty if node has no active neighbors
     */
    public synchronized Optional<NodeAddress> nextTargetNode() {
        if (nextIndex >= neighborList.size()) {
            Collections.shuffle(neighborList);
            nextIndex = 0;

            System.out.println("\n[" + Constant.NOW() + "] " + Constant.BLUE
                    + "Completed one full neighbor cycle. Shuffled neighborList: "
                    + neighborList + Constant.RESET);
        }

        int attempts = 0;
        while (attempts < neighborList.size()) {
            NodeAddress selectedNode = neighborList.get(nextIndex);
            nextIndex = (nextIndex + 1) % neighborList.size();
            attempts++;

            // Check if the neighbor is reachable
            if (getStatus(selectedNode.nodeId()) != NodeStatus.UNREACHABLE) {
                return Optional.of(selectedNode);
            }
        }

        return Optional.empty();
    }

    /**
     * Gets a list of helper nodes. These nodes must be reachable and not the same
     * node as target node.
     * 
     * @param targetNode the target node
     * @return the list of helper nodes
     */
    public List<NodeAddress> selectHelperNodes(NodeAddress targetNode) {
        List<NodeAddress> helperNodes = new ArrayList<>(neighborList.stream()
                .filter(node -> (getStatus(node.nodeId()) != NodeStatus.UNREACHABLE)
                        && (!node.nodeId().equals(targetNode.nodeId())))
                .toList());
        Collections.shuffle(helperNodes);

        return helperNodes;
    }

    /**
     * Marks the given node as ALIVE.
     * 
     * @param nodeId      the node id
     * @param phiDetector phi accrual failure
     * @see PhiAccrualFailure
     */
    public void markAlive(String nodeId, PhiAccrualFailure phiDetector) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markAlive(phiDetector);
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
        NodeState state = nodeStates.get(nodeId);

        if (state == null) {
            return NodeStatus.UNKNOWN;
        }

        return state.status();
    }

    /**
     * Gets the state of a node (includes #getStatus).
     * 
     * @param nodeId
     * @return
     * @see NodeState
     */
    public Optional<NodeState> getState(String nodeId) {
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    public List<NodeAddress> neighborList() {
        return new ArrayList<>(neighborList);
    }

    /**
     * Gets the address of the target node
     * 
     * @param targetNodeId the id of the target node
     * @return the node address
     * @see NodeAddress
     */
    public Optional<NodeAddress> getAddress(String targetNodeId) {
        return neighborList.stream()
                .filter(node -> node.nodeId().equals(targetNodeId))
                .findFirst();
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

        return state.incarnationNumber();
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
                if (incarnationNumber > state.incarnationNumber()) {
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
        List<NodeState> states = new ArrayList<>(nodeStates.values());
        states.sort(Comparator.comparing(state -> state.address().nodeId()));
        return states;
    }
}
