package com.monitoring.agent.node;

/**
 * Possible statuses of a node.
 */
public enum NodeStatus {
    /** The node is running properly and considered healthy. */
    ALIVE,
    /** The node can still reachable but response ack time is becoming unsual. */
    WARNING,
    /**
     * When direct ping fails, it can still reachable because of some reason such
     * as network congestion.
     */
    SUSPECTED,
    /**
     * The node can not ping or response to all the node in the system anymore ->
     * must rejoin if it comes back.
     */
    UNREACHABLE,
    /** When a node has just been joined to the system. */
    UNKNOWN
}
