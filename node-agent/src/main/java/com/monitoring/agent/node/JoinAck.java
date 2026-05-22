package com.monitoring.agent.node;

import java.util.List;

public record JoinAck(        
    String txId,
        NodeAddress responder,
        long neighborVersion,
        List<NodeAddress> responderNeighbors) {
}
