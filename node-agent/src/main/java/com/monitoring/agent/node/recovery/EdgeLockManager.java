package com.monitoring.agent.node.recovery;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks or releases an edge. An edge is an unordered set of two node IDs.
 */
public class EdgeLockManager {

    private final Set<String> edgeLocks = ConcurrentHashMap.newKeySet();

    private String key(String a, String b) {
        return a.compareTo(b) < 0
                ? a + ":" + b
                : b + ":" + a;
    }

    /**
     * Locks the edge A-B.
     * 
     * @param a node A
     * @param b node B
     * @return true if the edge has not been locked
     */
    public boolean reserve(String a, String b) {
        return edgeLocks.add(key(a, b));
    }

    /**
     * Releases the edge A-B.
     * 
     * @param a node A
     * @param b node B
     */
    public void release(String a, String b) {
        edgeLocks.remove(key(a, b));
    }

    /**
     * Checks if the edge A-B is locked.
     * 
     * @param a node A
     * @param b node B
     * @return whether the edge is locked
     */
    public boolean isReserved(String a, String b) {
        return edgeLocks.contains(key(a, b));
    }

}