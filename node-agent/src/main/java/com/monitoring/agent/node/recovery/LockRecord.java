package com.monitoring.agent.node.recovery;

public record LockRecord(
        String nodeId,
        long expirationTime,
        String repairEpoch
) {

    public boolean expired() {
        return System.currentTimeMillis() > expirationTime;
    }
}