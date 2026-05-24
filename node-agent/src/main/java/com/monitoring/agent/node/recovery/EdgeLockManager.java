package com.monitoring.agent.node.recovery;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EdgeLockManager {

    private final Set<String> edgeLocks =
            ConcurrentHashMap.newKeySet();

    private String key(
            String a,
            String b) {

        return a.compareTo(b) < 0
                ? a + ":" + b
                : b + ":" + a;
    }

    public boolean reserve(
            String a,
            String b) {

        return edgeLocks.add(key(a, b));
    }

    public void release(
            String a,
            String b) {

        edgeLocks.remove(key(a, b));
    }

    public boolean isReserved(
            String a,
            String b) {

        return edgeLocks.contains(key(a, b));
    }
}