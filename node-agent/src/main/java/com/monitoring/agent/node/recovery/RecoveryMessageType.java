package com.monitoring.agent.node.recovery;

public enum RecoveryMessageType {
    /** Notifies that the node itself is deficient. */
    DEFICIENT,

    /** Acknowledges that a node is deficient. */
    DEFICIENT_ACK,

    /** Currently unused. */
    NODE_LOCK,

    /** Currently unused. */
    NODE_UNLOCK,

    /** Currently unused. */
    REPAIR_REQUEST,
    /** Currently unused. */
    REPAIR_ACCEPT,
    /** Currently unused. */
    REPAIR_REJECT,
    /** Currently unused. */
    REPAIR_SUCCESS,
    /** Currently unused. */
    REPAIR_CANCEL,

    /** Currently unused. */
    EDGE_LOCK,
    /** Currently unused. */
    EDGE_UNLOCK,

    /** Currently unused. */
    REWIRE_REQUEST,
    /** Currently unused. */
    REWIRE_ACCEPT,
    /** Currently unused. */
    REWIRE_REJECT,

    /** Currently unused. */
    EDGE_BREAK,
    /** Currently unused. */
    EDGE_ADD,

    /** Currently unused. */
    ROLLBACK
}