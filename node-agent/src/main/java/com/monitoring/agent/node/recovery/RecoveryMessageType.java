package com.monitoring.agent.node.recovery;

public enum RecoveryMessageType {
    DEFICIENT,
    DEFICIENT_ACK,

    NODE_LOCK,
    NODE_UNLOCK,

    REPAIR_REQUEST,
    REPAIR_ACCEPT,
    REPAIR_REJECT,

    REPAIR_SUCCESS,
    REPAIR_CANCEL,

    EDGE_LOCK,
    EDGE_UNLOCK,

    REWIRE_REQUEST,
    REWIRE_ACCEPT,
    REWIRE_REJECT,

    EDGE_BREAK,
    EDGE_ADD,

    ROLLBACK
}