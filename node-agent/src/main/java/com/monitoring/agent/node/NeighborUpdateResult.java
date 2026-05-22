package com.monitoring.agent.node;

record NeighborUpdateResult(
        boolean accepted,
        NodeAddress addedPeer,
        NodeAddress evictedPeer,
        String reason) {
}
