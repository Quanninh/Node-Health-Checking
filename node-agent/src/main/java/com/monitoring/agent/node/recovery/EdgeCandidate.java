package com.monitoring.agent.node.recovery;

import com.monitoring.agent.node.NodeAddress;

public record EdgeCandidate(
        NodeAddress left,
        NodeAddress right) {
}    

