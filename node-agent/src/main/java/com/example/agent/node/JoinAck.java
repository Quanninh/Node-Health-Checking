package com.example.agent.node;

import java.util.List;
// this is one type of DiscoveryMessage -> have 1 file like this here for
// implementation for JobPlanner
record JoinAck(
        String txId,
        NodeAddress responder,
        long neighborVersion,
        List<NodeAddress> responderNeighbors
) {
}