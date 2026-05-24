package com.monitoring.agent.node.recovery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RepairLockManager {
    private static final long LOCK_TIMEOUT_MS = 10_000;

    private final Map<String, LockRecord> locks =
            new ConcurrentHashMap<>();

    public boolean tryLock(String nodeId) {

        cleanupExpiredLocks();

        LockRecord record = new LockRecord(
                nodeId,
                System.currentTimeMillis() + LOCK_TIMEOUT_MS);

        return locks.putIfAbsent(nodeId, record) == null;
    }

    public void unlock(String nodeId) {
        locks.remove(nodeId);
    }

    public boolean isLocked(String nodeId) {

        cleanupExpiredLocks();

        return locks.containsKey(nodeId);
    }

    public void cleanupExpiredLocks() {
        locks.values().removeIf(LockRecord::expired);
    }
}