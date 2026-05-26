package com.monitoring.agent.node.recovery;

public enum RecoveryMessageType {
    /** Notifies that the node itself is deficient. */
    DEFICIENT,
    /** Acknowledges that a node is deficient. */
    DEFICIENT_ACK,

    REWIRE_REQ_DIRECT,
    REWIRE_REQ_DIRECT_ACK,
    REWIRE_DIRECT_COMMIT,

    REWIRE_SESSION_REQ,
    REWIRE_SESSION_ACK,
    REWIRE_SESSION_COMMIT,

    REWIRE_REQ,
    REWIRE_ACK,
    REWIRE_DENY,
    COMMIT_ACK,

    NEIGHBORS_QUERY,
    NEIGHBORS_QUERY_RESPONSE,

    REWIRING_PROPOSE,
    REWIRING_PROPOSE_ACK,

    REWIRE_SCHEME_REQ,
    REWIRE_SCHEME_ACK,
    REWIRE_SCHEME_COMMIT,

    REWIRE_SCHEME
}
