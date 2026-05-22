package com.monitoring.agent.node.connection;

import java.util.List;

import com.monitoring.agent.node.NodeAddress;

public record Snapshot(long version, List<NodeAddress> neighbors) {
}
