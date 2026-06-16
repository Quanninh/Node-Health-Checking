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
     * that
     * are from nodes with less than the maximum amount of neighbors, the other list
     * (B)
     * for all ACKs from fully connected nodes.
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
        Map<String, JoinAck> uniqueNodeMap = new LinkedHashMap<>();
        Map<String, JoinAck> fullNeighborMap = new LinkedHashMap<>();
        Map<String, JoinAck> missingNeighborMap = new LinkedHashMap<>();

        for (JoinAck joinAck : joinAckList) {
            if (joinAck == null || joinAck.responder().nodeId().equals(localAddress.nodeId())) {
                continue;
            }
            uniqueNodeMap.putIfAbsent(joinAck.responder().nodeId(), joinAck);

            if (joinAck.responderNeighbors().size() >= maxNeighbors) {
                fullNeighborMap.putIfAbsent(joinAck.responder().nodeId(), joinAck);
            } else {
                missingNeighborMap.putIfAbsent(joinAck.responder().nodeId(), joinAck);
            }
        }

        List<JoinAck> uniqueAcks = new ArrayList<>(uniqueNodeMap.values());
        List<JoinAck> fullNeighborAcks = new ArrayList<>(fullNeighborMap.values());
        List<JoinAck> missingNeighborAcks = new ArrayList<>(missingNeighborMap.values());

        Collections.shuffle(uniqueAcks);
        Collections.shuffle(fullNeighborAcks);
        Collections.shuffle(missingNeighborAcks);

        if (uniqueAcks.isEmpty()) {
            Console.log("No ACKs received, empty join plan", Constant.ORANGE);
            return JoinPlan.empty();
        }

        int missingDirectTargetCount;
        int fullDirectTargetCount;

        if (missingNeighborAcks.size() + fullNeighborAcks.size() * 2 <= maxNeighbors) {
            missingDirectTargetCount = missingNeighborAcks.size();
            fullDirectTargetCount = fullNeighborAcks.size();
        } else {
            int evenMissingDirectTargetCount = Math
                    .max(missingNeighborAcks.size() % 2 == 0 ? missingNeighborAcks.size()
                            : missingNeighborAcks.size() - 1, 0);
            missingDirectTargetCount = Math.min(evenMissingDirectTargetCount, maxNeighbors);
            fullDirectTargetCount = Math.min((maxNeighbors - missingDirectTargetCount) / 2,
                    fullNeighborAcks.size());
                    
            int minimumFullTargets = Math.min(2, fullNeighborAcks.size());

            if (fullDirectTargetCount < minimumFullTargets
                    && maxNeighbors >= 2 * minimumFullTargets) {

                fullDirectTargetCount = minimumFullTargets;

                // Fill remaining slots with missing targets.
                missingDirectTargetCount = maxNeighbors - 2 * fullDirectTargetCount;

                // Keep missing count even.
                if ((missingDirectTargetCount & 1) != 0) {
                    missingDirectTargetCount--;
                }

                missingDirectTargetCount = Math.max(0, missingDirectTargetCount);
            }
        }
        // TODO: Update this to allow more full-neighbor to engage to prevent network
        // partition
        List<JoinAck> directTargetAcks = new ArrayList<>();

        List<JoinAck> fullDirectTargetAcks = fullNeighborAcks.subList(0, fullDirectTargetCount);

        directTargetAcks.addAll(missingNeighborAcks.subList(0, missingDirectTargetCount));
        directTargetAcks.addAll(fullDirectTargetAcks);

        // Console.log("&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&");
        // Console.log("UNIQUE ACKS: " + uniqueAcks, Constant.CYAN);
        // Console.log("FULL NEIGHBOR ACKS: " + fullNeighborAcks, Constant.CYAN);
        // Console.log("MISSING NEIGHBOR ACKS: " + missingNeighborAcks, Constant.CYAN);
        // Console.log("Missing direct target count: " + missingDirectTargetCount + " ||
        // Full direct target count: "
        // + fullDirectTargetCount, Constant.BG_CYAN);
        // Console.log("&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&");

        // uniqueAcks.subList(0, fullDirectTargetCount);

        Set<String> directTargetIds = new HashSet<>();

        for (JoinAck ack : directTargetAcks) {
            directTargetIds.add(ack.responder().nodeId());
        }

        Map<NodeAddress, NodeAddress> evictionByDirectTarget = new LinkedHashMap<>();

        Set<String> alreadySelectedVictimIds = new HashSet<>();
        alreadySelectedVictimIds.add(localAddress.nodeId());
        alreadySelectedVictimIds.addAll(directTargetIds);

        // for (JoinAck directAck : directTargetAcks) {
        for (JoinAck directAck : fullDirectTargetAcks) {
            List<NodeAddress> candidates = new ArrayList<>(directAck.responderNeighbors());
            Collections.shuffle(candidates);

            for (NodeAddress candidate : candidates) {
                if (candidate == null || alreadySelectedVictimIds.contains(candidate.nodeId())) {
                    continue;
                }

                evictionByDirectTarget.put(directAck.responder(), candidate);
                alreadySelectedVictimIds.add(candidate.nodeId());
                break;
            }
        }

        List<NodeAddress> directTargets = directTargetAcks.stream()
                .map(JoinAck::responder)
                .toList();

        Console.log("Join plan created. directTargets=" + directTargets
                + ", evictions=" + evictionByDirectTarget, Constant.ITALIC + Constant.GREEN);

        return new JoinPlan(directTargets, evictionByDirectTarget);
    }

}
