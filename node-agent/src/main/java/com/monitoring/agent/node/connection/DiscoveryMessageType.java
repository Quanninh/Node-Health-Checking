package com.monitoring.agent.node.connection;

public enum DiscoveryMessageType {
    /**
     * The direct target node adds the joining node as a neighbor, and evicts the
     * evicted node.
     */
    COMMIT_DIRECT,
    /**
     * The evicted node removes the direct target node from the neighbor list, and
     * adds the joining node as a neighbor.
     */
    COMMIT_VICTIM,
    /** The direct target node adds the joining node as a neighbor. */
    COMMIT_SMALL_JOIN,
    /** Acknowledges the commit (accepted or rejected) */
    COMMIT_ACK,
    /**
     * The joining node send requests (broadcast) to all nodes, asking to join the
     * network.
     */
    JOIN_REQUEST,
    /**
     * Nodes that are in the network accepts the join request, but not confirm yet
     * because the join request is a broadcast message.
     */
    JOIN_ACK
}
