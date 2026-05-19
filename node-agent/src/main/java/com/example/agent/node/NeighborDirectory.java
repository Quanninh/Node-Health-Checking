package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class NeighborDirectory {
    private final List<NodeAddress> neighborList;
    private final Map<String, NodeState> nodeStates;
    private int nextIndex = 0;

    NeighborDirectory(List<NodeAddress> neighborList) {
        this.neighborList = new ArrayList<>(neighborList);
        this.nodeStates = new ConcurrentHashMap<>();

        for (NodeAddress node : neighborList) {
            nodeStates.put(node.nodeId(), new NodeState(node));
        }
    }

    synchronized Optional<NodeAddress> nextTargetNode() {
        List<NodeAddress> reachableNeighbors = neighborList.stream()
                .filter(node -> getStatus(node.nodeId()) != NodeStatus.UNREACHABLE)
                .toList();

        if (reachableNeighbors.isEmpty()) {
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

    List<NodeAddress> selectHelperNodes(NodeAddress targetNode) {
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

    void markAlive(String nodeId, PhiAccrualFailureDetector phiDetector) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markAlive(phiDetector);
        }
    }

    void markWarning(String nodeId, double phi) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markWarning(phi);
        }
    }

    void markSuspected(String nodeId, double phi) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markSuspected(phi);
        }
    }

    void markUnreachable(String nodeId, double phi) {
        NodeState state = nodeStates.get(nodeId);

        if (state != null) {
            state.markUnreachable(phi);
        }
    }

    NodeStatus getStatus(String nodeId) {
        NodeState state = nodeStates.get(nodeId);

        if (state == null) {
            return NodeStatus.UNKNOWN;
        }

        return state.status();
    }

    Optional<NodeState> getState(String nodeId) {
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    List<NodeState> states() {
        List<NodeState> states = new ArrayList<>(nodeStates.values());
        states.sort(Comparator.comparing(state -> state.address().nodeId()));
        return states;
    }
}
