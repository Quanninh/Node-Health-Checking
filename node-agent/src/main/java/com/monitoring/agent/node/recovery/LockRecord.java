package com.monitoring.agent.node.recovery;

/**
 * Checks if a lock (on a node?) has expired or not.
 * 
 * @param nodeId         the node ID
 * @param expirationTime the time of expiration (unix time)
 * @param repairEpoch    the repair ID
 */
public record LockRecord(String nodeId, long expirationTime, String repairEpoch) {

    /**
     * Checks if a lock has expired or not.
     * 
     * @return whether a lock has expired
     */
    public boolean expired() {
        return System.currentTimeMillis() > expirationTime;
    }

}