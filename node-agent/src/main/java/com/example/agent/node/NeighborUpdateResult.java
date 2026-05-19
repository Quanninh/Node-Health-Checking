package com.example.agent.node;

record NeighborUpdateResult(
        boolean accepted,
        NodeAddress addedPeer,
        NodeAddress evictedPeer,
        String reason) {
}
