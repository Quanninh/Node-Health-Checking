package com.monitoring.agent.node.recovery;

/**
 * The role of a node with respect to the recovery process.
 * 
 * <p>
 * - A node can only be a {@code DEFICIENT_LEADER} or a
 * {@code DEFICIENT_FELLOW} for <b>one</b> recovery process at a time.
 * <p>
 * - A node currently acting as a {@code DEFICIENT_LEADER} or
 * {@code DEFICIENT_FELLOW} <b>cannot</b> act as a
 * {@code REWIRING_NODE} for any other process.
 * <p>
 * - A node <b>can</b> act as a {@code REWIRING_NODE} for multiple
 * concurrent recovery processes, <b>provided</b> that no two processes
 * utilize the exact same <i>pair</i> of rewiring nodes simultaneously
 * (each pair must be unique to a single {@code recoveryId} at any given
 * moment).
 */
public enum RecoveryRole {
    /**
     * The node is operating normally and is not engaged in any recovery/rewiring
     * process.
     */
    FREE,
    /**
     * A deficient node that initiates and coordinates a recovery process. It must
     * generate a unique, random recoveryId (serving as a session/room ID) to
     * isolate this specific recovery session.
     */
    DEFICIENT_LEADER,
    /**
     * A deficient node that accepts a request to join a recovery process initiated
     * by a leader.
     */
    DEFICIENT_FELLOW,
    /**
     * A healthy pair of connected nodes (C and D) selected to break their
     * mutual connection so they can re-route their slots to help the deficient
     * nodes.
     */
    REWIRING_NODE
}
