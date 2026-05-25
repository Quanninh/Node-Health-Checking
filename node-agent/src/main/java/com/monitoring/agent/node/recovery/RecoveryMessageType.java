package com.monitoring.agent.node.recovery;

public enum RecoveryMessageType {
    /** Notifies that the node itself is deficient. */
    DEFICIENT,
    /** Acknowledges that a node is deficient. */
    DEFICIENT_ACK,

    REWIRE_REQ_DIRECT,
    REWIRE_REQ,
    REWIRE_ACK,
    REWIRE_DENY,
    COMMIT_ACK,

    NEIGHBORS_QUERY,
    NEIGHBORS_QUERY_RESPONSE,

    REWIRING_PROPOSE,
    REWIRING_PROPOSE_ACK,

    REWIRE_SCHEME,
    REWIRE_SCHEME_ACK
}