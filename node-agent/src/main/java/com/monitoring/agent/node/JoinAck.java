package com.monitoring.agent.node;

import java.util.List;

/**
 * Acknowledgement for a new node joining. Contains a list of neighbors.
 */
public record JoinAck(
        String txId,
        NodeAddress responder,
        boolean isInNetwork,
        long neighborVersion,
        List<NodeAddress> responderNeighbors) {
}
