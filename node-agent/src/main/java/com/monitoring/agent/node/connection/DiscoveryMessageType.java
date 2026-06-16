package com.monitoring.agent.node.connection;

public enum DiscoveryMessageType {
    /**
     * The direct target node coordinates deletion with the evicted node, then adds
     * the joining node as a neighbor.
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
    /** The evicted node removes the direct target node from its neighbor list. This one is sent by the direct target to the victim */
    COMMIT_DELETE,
    /** Acknowledges COMMIT_DELETE (accepted or rejected). This one is sent by the victim back to the direct target in response to COMMIT_DELETE */
    COMMIT_DELETE_ACK,
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
