package com.monitoring.agent.node;

import java.util.List;
import java.util.Map;

public record JoinPlan(
        List<NodeAddress> directTargets,
        Map<NodeAddress, NodeAddress> evictionByDirectTarget) {

    static JoinPlan empty() {
        return new JoinPlan(List.of(), Map.of());
    }
}