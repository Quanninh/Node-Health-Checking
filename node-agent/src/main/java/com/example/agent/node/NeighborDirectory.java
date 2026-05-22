package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class NeighborDirectory {

    private final List<NodeAddress> neighborList;
    private final Map<String, NodeState> nodeStates;
    private final int maxNeighbors;
    private final Random random;
    private int nextIndex = 0;

    NeighborDirectory(List<NodeAddress> neighborList, int maxNeighbors) {
        if (maxNeighbors <= 0) {
            throw new IllegalArgumentException("maxNeighbors must be greater than zero.");
        }

        this.neighborList = new ArrayList<>();
        this.nodeStates = new ConcurrentHashMap<>();
        this.maxNeighbors = maxNeighbors;
        this.random = new Random();

        for (NodeAddress node : neighborList) {
            if (this.neighborList.size() >= maxNeighbors) {
                break;
            }

            addNeighbor(node);
        }
    }

    synchronized Optional<NodeAddress> nextTargetNode() {

        if (neighborList.isEmpty()) {
            return Optional.empty();
        }

        if (nextIndex >= neighborList.size()) {
            Collections.shuffle(neighborList);
            nextIndex = 0;

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Completed one full neighbor cycle. Shuffled neighborList: "
                            + neighborList);
        }

        int attempts = 0;
        while (attempts < neighborList.size()) {
            NodeAddress selectedNode = neighborList.get(nextIndex);
            nextIndex = (nextIndex + 1) % neighborList.size();
            attempts++;

            if (getStatus(selectedNode.nodeId()) != NodeStatus.UNREACHABLE) {
                return Optional.of(selectedNode);
            }
        }

        return Optional.empty();
    }

    synchronized List<NodeAddress> selectHelperNodes(NodeAddress targetNode) {
        List<NodeAddress> helperNodes = new ArrayList<>();

        for (NodeAddress node : neighborList) {
            if (node.nodeId().equals(targetNode.nodeId())) {
                continue;
            }

            if (getStatus(node.nodeId()) == NodeStatus.UNREACHABLE) {
                continue;
            }

            helperNodes.add(node);
        }

        Collections.shuffle(helperNodes);

        return helperNodes;
    }

    void markAlive(String nodeId, PhiAccrualFailure phiDetector) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markAlive(phiDetector);
        }
    }

    synchronized void markWarning(String nodeId, double phi) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markWarning(phi);
        }
    }

    synchronized void markSuspected(String nodeId, double phi) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markSuspected(phi);
        }
    }

    synchronized void markUnreachable(String nodeId, double phi) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markUnreachable(phi);
        }
    }

    synchronized NodeStatus getStatus(String nodeId) {
        NodeState state = nodeStates.get(nodeId);

        if (state == null) {
            return NodeStatus.UNKNOWN;
        }

        return state.status();
    }

    synchronized Optional<NodeState> getState(String nodeId) {
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    List<NodeAddress> neighborList() {
        return new ArrayList<>(neighborList);
    }

    Optional<NodeAddress> getAddress(String nodeId) {
        return neighborList.stream()
                .filter(node -> node.nodeId().equals(nodeId))
                .findFirst();
    }

    int incarnationNumber(String nodeId) {
        NodeState state = nodeStates.get(nodeId);

        if (state == null) {
            return 0;
        }

        return state.incarnationNumber();
    }

    void applyGossipStatus(String subjectNodeId, GossipMessageType messageType, int incarnationNumber) {
        NodeState state = nodeStates.get(subjectNodeId);

        if (state == null) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Gossip subjectNodeId " + subjectNodeId
                            + " is not in this node's neighborList. Message is recorded but not added.");
            return;
        }

        switch (messageType) {
            case SUSPECT -> state.markSuspectedFromGossip(incarnationNumber);
            case UNREACHABLE -> state.markUnreachableFromGossip(incarnationNumber);
            case ALIVE -> state.markAliveFromGossip(incarnationNumber);
            case LEAVE -> state.markLeftFromGossip(incarnationNumber);
            case JOIN -> {
                if (incarnationNumber > state.incarnationNumber()) {
                    state.markAliveFromGossip(incarnationNumber);
                }
            }
        }
    }

    synchronized List<NodeState> states() {
        List<NodeState> states = new ArrayList<>(nodeStates.values());
        states.sort(Comparator.comparing(state -> state.address().nodeId()));
        return states;
    }

    private Optional<NodeAddress> selectEvictionCandidate() {
        List<NodeAddress> unreachableCandidates = neighborList.stream()
                .filter(node -> getStatus(node.nodeId()) == NodeStatus.UNREACHABLE)
                .toList();

        if (!unreachableCandidates.isEmpty()) {
            return Optional.of(unreachableCandidates.get(random.nextInt(unreachableCandidates.size())));
        }

        if (neighborList.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(neighborList.get(random.nextInt(neighborList.size())));
    }
}
