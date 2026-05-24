package com.monitoring.agent.node.recovery;

import java.time.Instant;

import com.monitoring.agent.node.NodeAddress;

/**
 * Represents a deficient node, i.e. not having enough neighbors.
 */
public record DeficientNodeRecord(
        NodeAddress node,
        int degree,
        String repairEpoch,
        Instant timestamp,
        int incarnationNumber) {

    public String nodeId() {
        return node.nodeId();
    }

}
