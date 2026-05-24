// package com.monitoring.agent.node.recovery;

// import java.util.Map;
// import java.util.concurrent.ConcurrentHashMap;

// @Deprecated
// public class RepairLockManager {

// private final Map<String, LockRecord> locks = new ConcurrentHashMap<>();

// /**
// * Locks a node
// *
// * @param nodeId
// * @param owner
// * @param repairEpoch
// * @return
// */
// private boolean tryLock(String nodeId, String owner, String repairEpoch) {
// return locks.putIfAbsent(nodeId,
// new LockRecord(owner, System.currentTimeMillis(), repairEpoch)) == null;
// }

// @Deprecated
// public void unlock(String nodeId) {
// locks.remove(nodeId);
// }

// @Deprecated
// public boolean isLocked(String nodeId) {
// return locks.containsKey(nodeId);
// }
// }