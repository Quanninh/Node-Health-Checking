package com.monitoring.agent.node.recovery;

import java.util.List;

import com.monitoring.agent.node.NodeAddress;

public record RecoveryMessage(
        RecoveryMessageType type,

        String txId,
        String repairEpoch,

        NodeAddress sender,

        NodeAddress deficientNode,
        NodeAddress targetNode,

        List<NodeAddress> neighbors,

        int ttl,
        long timestamp,
        int incarnationNumber
) {
}