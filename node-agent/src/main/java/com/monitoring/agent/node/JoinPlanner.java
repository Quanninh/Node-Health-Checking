package com.monitoring.agent.node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.monitoring.agent.util.Console;

public final class JoinPlanner {

    private final NodeAddress localAddress;
    private final int maxNeighbors;

    public JoinPlanner(NodeAddress localAddress, int maxNeighbors) {
        this.localAddress = localAddress;
        this.maxNeighbors = maxNeighbors;
    }

    public JoinPlan createPlan(Collection<JoinAck> acks) {
        Map<String, JoinAck> uniqueAcks = new LinkedHashMap<>();

        for (JoinAck ack : acks) {
            if (ack == null || ack.responder().nodeId().equals(localAddress.nodeId())) {
                continue;
            }
            uniqueAcks.putIfAbsent(ack.responder().nodeId(), ack);
        }

        List<JoinAck> responders = new ArrayList<>(uniqueAcks.values());
        Collections.shuffle(responders);

        if (responders.isEmpty()) {
            return JoinPlan.empty();
        }

        if (responders.size() <= maxNeighbors) {
            List<NodeAddress> directOnly = responders.stream()
                    .map(JoinAck::responder)
                    .limit(maxNeighbors)
                    .toList();
            return new JoinPlan(directOnly, Map.of());
        }

        int directTargetCount = maxNeighbors / 2;
        List<JoinAck> directTargetAcks = responders.subList(0, directTargetCount);

        Set<String> directTargetIds = new HashSet<>();
        for (JoinAck ack : directTargetAcks) {
            directTargetIds.add(ack.responder().nodeId());
        }

        Map<NodeAddress, NodeAddress> evictionByDirectTarget = new LinkedHashMap<>();
        Set<String> alreadySelectedVictimIds = new HashSet<>();
        alreadySelectedVictimIds.add(localAddress.nodeId());
        alreadySelectedVictimIds.addAll(directTargetIds);

        for (JoinAck directAck : directTargetAcks) {
            List<NodeAddress> candidates = new ArrayList<>(directAck.responderNeighbors());
            Collections.shuffle(candidates);

            for (NodeAddress candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                if (alreadySelectedVictimIds.contains(candidate.nodeId())) {
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
                + ", evictions=" + evictionByDirectTarget);

        return new JoinPlan(directTargets, evictionByDirectTarget);
    }

}
