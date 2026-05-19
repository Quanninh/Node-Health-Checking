package com.example.agent.node;

record JoinAck(
        boolean accepted,
        NodeAddress responder,
        NodeAddress suggestedPeer,
        String reason) {
}
