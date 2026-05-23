package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class ConnectionManager {

    private final NodeAddress localAddress;
    private final int maxNeighbors;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, NodeAddress> neighborsById = new LinkedHashMap<>();
    private final Set<String> processedTransactions = ConcurrentHashMap.newKeySet();

    private long version = 0;

    ConnectionManager(NodeAddress localAddress, int maxNeighbors) {
        if (maxNeighbors <= 0 || maxNeighbors % 2 != 0) {
            throw new IllegalArgumentException("maxNeighbors must be a positive even number.");
        }

        this.localAddress = localAddress;
        this.maxNeighbors = maxNeighbors;
    }

    Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(version, List.copyOf(neighborsById.values()));
        } finally {
            lock.unlock();
        }
    }

    boolean contains(String nodeId) {
        lock.lock();
        try {
            return neighborsById.containsKey(nodeId);
        } finally {
            lock.unlock();
        }
    }

    boolean addIfSpace(NodeAddress peer, String reason) {
        lock.lock();
        try {
            if (peer == null || peer.nodeId().equals(localAddress.nodeId())) {
                return false;
            }

            if (neighborsById.containsKey(peer.nodeId())) {
                return true;
            }

            if (neighborsById.size() >= maxNeighbors) {
                return false;
            }

            neighborsById.put(peer.nodeId(), peer);
            version++;

            log("Added neighbor " + peer + " because " + reason
                    + ". neighbors=" + neighborsById.values());
            return true;
        } finally {
            lock.unlock();
        }
    }

    boolean remove(String nodeId, String reason) {
        lock.lock();
        try {
            NodeAddress removed = neighborsById.remove(nodeId);
            if (removed == null) {
                return false;
            }

            version++;
            log("Removed neighbor " + removed + " because " + reason
                    + ". neighbors=" + neighborsById.values());
            return true;
        } finally {
            lock.unlock();
        }
    }

    CommitResult applyDirectTargetCommit(
            String txId,
            NodeAddress joiningNode,
            String evictedNodeId
    ) {
        lock.lock();
        try {
            if (!processedTransactions.add("DIRECT:" + txId)) {
                return new CommitResult(true, "duplicate direct commit ignored");
            }

            if (joiningNode == null || joiningNode.nodeId().equals(localAddress.nodeId())) {
                return new CommitResult(false, "cannot add self/null joining node");
            }

            if (neighborsById.containsKey(joiningNode.nodeId())) {
                return new CommitResult(true, "joining node already exists");
            }

            NodeAddress evicted = null;
            if (evictedNodeId != null && !evictedNodeId.isBlank()) {
                evicted = neighborsById.remove(evictedNodeId);
            }

            if (neighborsById.size() >= maxNeighbors) {
                if (evicted != null) {
                    neighborsById.put(evicted.nodeId(), evicted);
                }
                return new CommitResult(false, "direct target would exceed maxNeighbors");
            }

            neighborsById.put(joiningNode.nodeId(), joiningNode);
            version++;

            log("Node " + localAddress.nodeId()
                    + " accepted joining node " + joiningNode
                    + (evicted == null ? "" : " and evicted " + evicted)
                    + ". neighbors=" + neighborsById.values());
            return new CommitResult(true, "direct target commit accepted");
        } finally {
            lock.unlock();
        }
    }

    CommitResult applyEvictedNodeCommit(
            String txId,
            NodeAddress joiningNode,
            String oldDirectTargetId
    ) {
        lock.lock();
        try {
            if (!processedTransactions.add("VICTIM:" + txId + ":" + oldDirectTargetId)) {
                return new CommitResult(true, "duplicate victim commit ignored");
            }

            if (joiningNode == null) {
                return new CommitResult(false, "joining node is null");
            }

            NodeAddress removed = null;
            if (oldDirectTargetId != null && !oldDirectTargetId.isBlank()) {
                removed = neighborsById.remove(oldDirectTargetId);
            }

            if (!joiningNode.nodeId().equals(localAddress.nodeId())) {
                neighborsById.put(joiningNode.nodeId(), joiningNode);
            }

            while (neighborsById.size() > maxNeighbors) {
                String firstKey = neighborsById.keySet().iterator().next();
                if (firstKey.equals(joiningNode.nodeId())) {
                    break;
                }
                neighborsById.remove(firstKey);
            }

            version++;
            log("Node " + localAddress.nodeId()
                    + " replaced old direct target " + removed
                    + " with joining node " + joiningNode
                    + ". neighbors=" + neighborsById.values());
            return new CommitResult(true, "victim commit accepted");
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return neighborsById.size();
        } finally {
            lock.unlock();
        }
    }

    int maxNeighbors() {
        return maxNeighbors;
    }

    List<NodeAddress> addresses() {
        lock.lock();
        try {
            return new ArrayList<>(neighborsById.values());
        } finally {
            lock.unlock();
        }
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now() + "] " + message);
    }

    record Snapshot(long version, List<NodeAddress> neighbors) {
    }

    record CommitResult(boolean accepted, String reason) {
    }
}
