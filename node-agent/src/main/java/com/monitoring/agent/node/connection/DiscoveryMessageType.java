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
    /** */
    COMMIT_ACK,
    /** The direct target node adds the joining node as a neighbor. */
    COMMIT_SMALL_JOIN,
    /** */
    JOIN_REQUEST,
    /** */
    JOIN_ACK
}
