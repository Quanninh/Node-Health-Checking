package com.monitoring.agent.node;

/**
 * Type of Gossip Message
 */
public enum GossipMessageType {
    /**
     * A node is suspected when we can't ping it or it takes a very long time to
     * reply. However, the phi value for this node has not reached the threshold yet
     * so it is suspected.
     */
    SUSPECTED,
    /** This node is unreachable. */
    UNREACHABLE,
    /** This node is alive. */
    ALIVE
}
