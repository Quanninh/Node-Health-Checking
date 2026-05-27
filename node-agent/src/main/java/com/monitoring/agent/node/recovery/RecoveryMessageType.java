package com.monitoring.agent.node.recovery;

public enum RecoveryMessageType {
    /** Notifies that the node itself is deficient. */
    DEFICIENT,
    /** Acknowledges that a node is deficient. */
    DEFICIENT_ACK,

    /** Requests a direct rewire. */
    REWIRE_REQ_DIRECT,
    /**
     * The receiver sends back to the sender of REWIRE_REQ_DIRECT in case of
     * success.
     * Once that sender receives this, it adds the receiver node to its neighbor
     * list
     * and send REWIRE_DIRECT_COMMIT to the receiver.
     */
    REWIRE_REQ_DIRECT_ACK,
    /**
     * Message sent to the node that receives REWIRE_REQ_DIRECT. Once it received
     * this message,
     * it updates its neighbor list if successful.
     */
    REWIRE_DIRECT_COMMIT,

    /**
     * Sent to ask other deficient node if it wants to join the recovery process.
     * If the receiver CANNOT become DEFICIENT_FELLOW, it refuses.
     */
    REWIRE_SESSION_REQ,
    /**
     * If the receiver of REWIRE_SESSION_REQ can become DEFICIENT_FELLOW, it
     * send back this message to sender. The sender then will become the
     * DEFICIENT_LEADER
     * and sends REWIRE_SESSION_COMMIT.
     */
    REWIRE_SESSION_ACK,
    /**
     * Message sent to the node that receives REWIRE_SESSION_REQ. Once it received
     * this message,
     * it becomes DEFICIENT_FELLOW.
     */
    REWIRE_SESSION_COMMIT,

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

    /** Asks if node D wants to join the rewiring */
    REWIRING_PROPOSE,
    /** */
    REWIRING_PROPOSE_ACK,

    /** */
    REWIRE_SCHEME_REQ,
    /** */
    REWIRE_SCHEME_ACK,
    /** */
    REWIRE_SCHEME_COMMIT,

    /** */
    REWIRE_SCHEME
}
