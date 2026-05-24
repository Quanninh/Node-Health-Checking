package com.monitoring.agent.node.recovery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RepairLockManager {

    private final Map<String, LockRecord> locks =
            new ConcurrentHashMap<>();

    public boolean tryLock(
            String nodeId,
            String owner,
            String repairEpoch) {

        return locks.putIfAbsent(
                nodeId,
                new LockRecord(
                        owner,
                        System.currentTimeMillis(),
                        repairEpoch)) == null;
    }

    public void unlock(String nodeId) {
        locks.remove(nodeId);
    }

    public boolean isLocked(String nodeId) {
        return locks.containsKey(nodeId);
    }
}