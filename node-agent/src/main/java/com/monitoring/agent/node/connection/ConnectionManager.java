package com.monitoring.agent.node.connection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

/**
 * Manages the neighbors of one node. Can add or remove neighbors, and also
 * provide functions for dealing with new joining nodes.
 * 
 * Networks are classified into two types: Small networks (when there are less
 * than k+1 nodes) and Scaled networks (when there are k+1 nodes or more).
 */
public final class ConnectionManager {

    private final NodeAddress localAddress;
    private final int maxNeighbors;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, NodeAddress> neighborsById = new LinkedHashMap<>();
    private final Set<String> processedTransactions = ConcurrentHashMap.newKeySet();

    /** The version of the snapshot. Highest = latest. */
    private long version = 0;

    /**
     * Constructor for Connection Manager.
     * 
     * @param localAddress the local address of the node
     * @param maxNeighbors the maximum number of neighbors, must be a positive even
     *                     number
     */
    public ConnectionManager(NodeAddress localAddress, int maxNeighbors) {
        if (maxNeighbors <= 0 || maxNeighbors % 2 != 0) {
            throw new IllegalArgumentException("maxNeighbors must be a positive even number.");
        }

        this.localAddress = localAddress;
        this.maxNeighbors = maxNeighbors;
    }

    /**
     * Takes a snapshot of the current neighbors list.
     * 
     * @return a snapshot
     * @see Snapshot
     */
    public Snapshot takeSnapshot() {
        lock.lock();
        try {
            return new Snapshot(version, List.copyOf(neighborsById.values()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if a node is one of this node's neighbor.
     * 
     * @param nodeId the node
     * @return whether the node is a neighbor
     */
    public boolean containsNode(String nodeId) {
        lock.lock();
        try {
            return neighborsById.containsKey(nodeId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds a new neighbor to the list if there is still space. Return {@code false}
     * if the node is null, itself, already a neighbor, or if the node can't accept
     * new neighbors.
     * 
     * @param peer   the requesting node
     * @param reason reason for becoming a neighbor
     * @return successful or not
     */
    public boolean addIfSpace(NodeAddress peer, String reason) {
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

            Console.log("Added neighbor " + peer + " because " + reason + ". neighbors=" + neighborsById.values());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a node from the neighbor list.
     * 
     * @param nodeId node to be removed
     * @param reason reason for removal
     * @return status
     */
    public boolean remove(String nodeId, String reason) {
        lock.lock();
        try {
            NodeAddress removed = neighborsById.remove(nodeId);
            if (removed == null) {
                return false;
            }

            version++;
            Console.log("Removed neighbor " + removed + " because " + reason
                    + ". neighbors=" + neighborsById.values());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * This function is called by the node that is randomly chosen by the joining
     * node. This node will add the joining node as a new neighbor, as well as
     * remove a neighbor which is determined by the joining node.
     * 
     * @param txId          transaction ID
     * @param joiningNode   the joining node
     * @param evictedNodeId the evicted node
     * @return the result of the transaction
     */
    public CommitResult applyDirectTargetCommit(String txId, NodeAddress joiningNode, String evictedNodeId) {
        lock.lock();
        try {
            // add returns false if already in set
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

            Console.log("Node " + localAddress.nodeId()
                    + " accepted joining node " + joiningNode
                    + (evicted == null ? "" : " and evicted " + evicted)
                    + ". neighbors=" + neighborsById.values());
            return new CommitResult(true, "direct target commit accepted");
        } finally {
            lock.unlock();
        }
    }

    /**
     * This function is called by the evicted node. The evictec node will add the
     * joining node as a neighbor and remove its old neighbor.
     * 
     * @param txId              transaction ID
     * @param joiningNode       the joining node
     * @param oldDirectTargetId the old neighbor
     * @return the result of the transaction
     */
    public CommitResult applyEvictedNodeCommit(String txId, NodeAddress joiningNode, String oldDirectTargetId) {
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

            version++;
            Console.log("Node " + localAddress.nodeId()
                    + " replaced old direct target " + removed
                    + " with joining node " + joiningNode
                    + ". neighbors=" + neighborsById.values());
            return new CommitResult(true, "victim commit accepted");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a small join commit. This function is called by the node that is in
     * the network already and will add the joining node into the neighbor list.
     * 
     * @param txId        transaction ID
     * @param joiningNode the joining node
     * @return
     */
    CommitResult applySmallJoinCommit(String txId, NodeAddress joiningNode) {
        lock.lock();

        try {
            if (!processedTransactions.add("SMALL_JOIN:" + txId)) {
                return new CommitResult(true, "Duplicate small join commit ignored");
            }

            if (joiningNode == null) {
                return new CommitResult(false, "Joining node is null");
            }

            if (joiningNode.nodeId().equals(localAddress.nodeId())) {
                return new CommitResult(false, "Cannot add self");
            }

            if (neighborsById.containsKey(joiningNode.nodeId())) {
                return new CommitResult(true, "Joining node already exists");
            }

            if (neighborsById.size() >= maxNeighbors) {
                return new CommitResult(false, "Small join target has no free neighbor slot");
            }

            neighborsById.put(joiningNode.nodeId(), joiningNode);
            version++;

            Console.log("Node " + localAddress.nodeId()
                    + " accepted small-network joining node " + joiningNode
                    + ". neighbors=" + neighborsById.values());

            return new CommitResult(true, "small join commit accepted");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the size of the neighbors list.
     * 
     * @return the size
     */
    public int size() {
        lock.lock();
        try {
            return neighborsById.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the number of max neighbors for this node.
     * 
     * @return the max neighbors
     */
    public int getMaxNeighbors() {
        return maxNeighbors;
    }

    /**
     * Gets the list of addresses of neighbors.
     * 
     * @return list of addresses
     */
    public List<NodeAddress> addresses() {
        lock.lock();
        try {
            return new ArrayList<>(neighborsById.values());
        } finally {
            lock.unlock();
        }
    }

}
