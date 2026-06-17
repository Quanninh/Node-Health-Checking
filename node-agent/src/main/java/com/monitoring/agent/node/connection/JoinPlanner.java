package com.monitoring.agent.node.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.JoinAck;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

public final class JoinPlanner {

    private final NodeAddress localAddress;
    private final int maxNeighbors;

    public JoinPlanner(NodeAddress localAddress, int maxNeighbors) {
        this.localAddress = localAddress;
        this.maxNeighbors = maxNeighbors;
    }

    /**
     * Creates a join plan.
     * 
     * From the list of received ACKs, creates 2 lists. One list (A) for all ACKs
     * that are from nodes with less than the maximum amount of neighbors, the other
     * list (B) for all ACKs from fully connected nodes.
     * 
     * The joining node will choose as many nodes as possible from list (A) and an
     * even number of nodes from list (B). All nodes from list (A) will directly add
     * the joining node as a neighbor and vice versa, while nodes from list (B) will
     * have to evict one node and give that node to the joining node.
     * 
     * This will ensure that each node will have as many connections as possible, no
     * matter how many nodes are joining or how many neighbors a node already has.
     * 
     * @param joinAckList list of JOIN_ACKs
     * @return the join plan
     */
    public JoinPlan createPlan(Collection<JoinAck> joinAckList) {
        List<JoinAck> uniqueNodeAcks = getUniqueNodeAcksList(joinAckList);

        if (uniqueNodeAcks.isEmpty()) {
            Console.log("No ACKs received, empty join plan", Constant.ORANGE);
            return JoinPlan.empty();
        }

        List<JoinAck> sufficientNodeAcks = getSufficientNodeAcksList(joinAckList);
        List<JoinAck> deficientNodeAcks = getDeficientNodeAcksList(joinAckList);

        int sufficientTargetCount;
        int deficientTargetCount;

        if (deficientNodeAcks.size() + sufficientNodeAcks.size() * 2 <= maxNeighbors) {
            deficientTargetCount = deficientNodeAcks.size();
            sufficientTargetCount = sufficientNodeAcks.size();
        } else {
            sufficientTargetCount = calcSufficientTarget(sufficientNodeAcks.size(), deficientNodeAcks.size());
            deficientTargetCount = maxNeighbors - 2 * sufficientTargetCount;
        }

        List<JoinAck> directTargetAcks = new ArrayList<>();
        List<JoinAck> chosenSufficientTargetAcks = sufficientNodeAcks.subList(0, sufficientTargetCount);

        directTargetAcks.addAll(deficientNodeAcks.subList(0, deficientTargetCount));
        directTargetAcks.addAll(chosenSufficientTargetAcks);

        Map<NodeAddress, NodeAddress> evictionMap = generateEvictionMap(directTargetAcks, chosenSufficientTargetAcks);
        List<NodeAddress> directTargets = directTargetAcks.stream().map(JoinAck::responder).toList();

        Console.logSuccess("Join plan created. directTargets=" + directTargets + ", evictions=" + evictionMap);

        return new JoinPlan(directTargets, evictionMap);
    }

    private List<JoinAck> getUniqueNodeAcksList(Collection<JoinAck> joinAckList) {
        Map<String, JoinAck> uniqueNodeMap = new LinkedHashMap<>();

        for (JoinAck joinAck : joinAckList) {
            if (isJoinAckDiscarded(joinAck))
                continue;

            uniqueNodeMap.putIfAbsent(joinAck.responder().nodeId(), joinAck);
        }

        List<JoinAck> uniqueAcks = new ArrayList<>(uniqueNodeMap.values());
        Collections.shuffle(uniqueAcks);
        return uniqueAcks;
    }

    private List<JoinAck> getSufficientNodeAcksList(Collection<JoinAck> joinAckList) {
        Map<String, JoinAck> sufficientNodeMap = new LinkedHashMap<>();

        for (JoinAck joinAck : joinAckList) {
            if (isJoinAckDiscarded(joinAck))
                continue;

            if (joinAck.responderNeighbors().size() >= maxNeighbors) {
                sufficientNodeMap.putIfAbsent(joinAck.responder().nodeId(), joinAck);
            }
        }

        List<JoinAck> sufficientNodeAcks = new ArrayList<>(sufficientNodeMap.values());
        Collections.shuffle(sufficientNodeAcks);
        return sufficientNodeAcks;
    }

    private List<JoinAck> getDeficientNodeAcksList(Collection<JoinAck> joinAckList) {
        Map<String, JoinAck> deficientNodeMap = new LinkedHashMap<>();

        for (JoinAck joinAck : joinAckList) {
            if (isJoinAckDiscarded(joinAck))
                continue;

            if (joinAck.responderNeighbors().size() < maxNeighbors) {
                deficientNodeMap.putIfAbsent(joinAck.responder().nodeId(), joinAck);
            }
        }

        List<JoinAck> deficientNodeAcks = new ArrayList<>(deficientNodeMap.values());
        Collections.shuffle(deficientNodeAcks);
        return deficientNodeAcks;
    }

    private int calcSufficientTarget(int sufficientNodes, int deficientNodes) {
        int evenDeficientTargets = deficientNodes;

        if (evenDeficientTargets % 2 == 1) {
            evenDeficientTargets--;
        }

        evenDeficientTargets = Math.min(evenDeficientTargets, maxNeighbors);
        int sufficientTargets = Math.min((maxNeighbors - evenDeficientTargets) / 2, sufficientNodes);

        int minSufficientTargets = Math.min(Constant.MINIMUM_SUFFICIENT_TARGET_COUNT, sufficientNodes);

        boolean hasLessThanMinTargets = sufficientTargets < minSufficientTargets;
        boolean canAcceptMinTargets = maxNeighbors >= 2 * minSufficientTargets;

        if (hasLessThanMinTargets && canAcceptMinTargets) {
            sufficientTargets = minSufficientTargets;
        }

        return sufficientTargets;
    }

    private Map<NodeAddress, NodeAddress> generateEvictionMap(List<JoinAck> directTargets,
            List<JoinAck> sufficientTargets) {
        Set<String> directTargetIds = new HashSet<>();

        for (JoinAck ack : directTargets) {
            directTargetIds.add(ack.responder().nodeId());
        }

        Map<NodeAddress, NodeAddress> evictionByDirectTarget = new LinkedHashMap<>();

        Set<String> selectedVictimIds = new HashSet<>();
        selectedVictimIds.add(localAddress.nodeId());
        selectedVictimIds.addAll(directTargetIds);

        for (JoinAck directAck : sufficientTargets) {
            List<NodeAddress> candidates = new ArrayList<>(directAck.responderNeighbors());
            Collections.shuffle(candidates);

            for (NodeAddress candidate : candidates) {
                if (candidate == null)
                    continue;

                boolean hasCandidateAlreadyEvicted = selectedVictimIds.contains(candidate.nodeId());
                if (hasCandidateAlreadyEvicted)
                    continue;

                evictionByDirectTarget.put(directAck.responder(), candidate);
                selectedVictimIds.add(candidate.nodeId());
                break;
            }
        }

        return evictionByDirectTarget;
    }

    private boolean isJoinAckDiscarded(JoinAck joinAck) {
        return joinAck == null || joinAck.responder().nodeId().equals(localAddress.nodeId());
    }

}
