package com.monitoring.agent.node.connection;

import java.util.List;
import java.util.Map;

import com.monitoring.agent.node.NodeAddress;

/**
 * A join plan. The joining node will become neighbors with the direct targets
 * as well as the evicted nodes. The evicted nodes will be removed from the
 * direct target's neighbor list
 */
public record JoinPlan(List<NodeAddress> directTargets, Map<NodeAddress, NodeAddress> evictionByDirectTarget) {

    /**
     * An empty join plan.
     * 
     * @return the empty join plan
     */
    public static JoinPlan empty() {
        return new JoinPlan(List.of(), Map.of());
    }

}