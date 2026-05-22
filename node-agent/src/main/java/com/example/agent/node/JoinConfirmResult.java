package com.example.agent.node;

record JoinConfirmResult(
        boolean accepted,
        NodeAddress confirmedPeer,
        NodeAddress evictedPeer,
        String reason) {
}

