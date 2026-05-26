package com.monitoring.agent.node.recovery;

public enum RecoveryMessageType {
    /** Notifies that the node itself is deficient. */
    DEFICIENT,
    /** Acknowledges that a node is deficient. */
    DEFICIENT_ACK,

    /** Requests a direct rewire. */
    REWIRE_REQ_DIRECT,
    /** Requests a full rewire. */
    REWIRE_REQ,
    /** Acknowledges that the requested rewire can be carried out. */
    REWIRE_ACK,
    /** Denies the requested rewire. */
    REWIRE_DENY,
    /** Confirms that the current request will be carried out. */
    COMMIT_ACK,

    /** Asks for the neighbor list. */
    NEIGHBORS_QUERY,
    /** Returns the neighbor list. */
    NEIGHBORS_QUERY_RESPONSE,

    REWIRING_PROPOSE,
    REWIRING_PROPOSE_ACK,

    REWIRE_SCHEME,
    REWIRE_SCHEME_ACK
}