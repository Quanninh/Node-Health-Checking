package com.monitoring.agent.node.connection;

import java.util.List;

import com.monitoring.agent.node.NodeAddress;

/**
 * A snapshot of the neighbor list.
 */
public record Snapshot(long version, List<NodeAddress> neighbors) {
}
