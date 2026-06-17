package com.monitoring.agent.node.connection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.recovery.HealthState;
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

    private boolean isInNetwork = false;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, NodeAddress> neighborsById = new LinkedHashMap<>();
    private final Set<String> processedTransactions = ConcurrentHashMap.newKeySet();
    private volatile HealthState healthState = HealthState.DEFICIENT;

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
                Console.log(
                        "Can't add because " + (peer == null ? "null" : peer.nodeId()) + " is null or is me");
                return false;
            }

            if (neighborsById.containsKey(peer.nodeId())) {
                Console.log("Already a neighbor");
                return true;
            }

            if (neighborsById.size() >= maxNeighbors) {
                Console.log("Full neighbors -> can't add");
                return false;
            }

            neighborsById.put(peer.nodeId(), peer);
            version++;
            refreshHealthStateLocked();

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
                Console.log("Couldn't remove " + nodeId + ".");
                return false;
            }

            version++;
            refreshHealthStateLocked();
            Console.log("Removed neighbor " + removed + " because " + reason
                    + ". neighbors=" + neighborsById.values());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * This function is called by the direct target after any needed delete commit
     * has completed. This node will add the joining node as a new neighbor.
     * 
     * @param txId          transaction ID
     * @param joiningNode   the joining node
     * @param evictedNodeId retained for compatibility with existing callers; this
     *                      method no longer removes it => put it here just to refactor mayebe in the future?
     * @return the result of the transaction
     */
    public CommitResult applyDirectTargetCommit(String txId, NodeAddress joiningNode, String evictedNodeId) {
        lock.lock();
        try {
            // `add` returns false if already in set
            if (!processedTransactions.add("DIRECT:" + txId)) {
                Console.log("duplicate direct commit ignored");
                return new CommitResult(true, "duplicate direct commit ignored");
            }

            if (joiningNode == null || joiningNode.nodeId().equals(localAddress.nodeId())) {
                Console.log("cannot add self/null joining node");
                return new CommitResult(false, "cannot add self/null joining node");
            }

            if (neighborsById.containsKey(joiningNode.nodeId())) {
                Console.log("joining node already exists");
                return new CommitResult(true, "joining node already exists");
            }

            if (neighborsById.size() >= maxNeighbors) {
                Console.log("direct target would exceed maxNeighbors");
                return new CommitResult(false, "direct target would exceed maxNeighbors");
            }

            neighborsById.put(joiningNode.nodeId(), joiningNode);
            version++;
            refreshHealthStateLocked();

            Console.log("Node " + localAddress.nodeId()
                    + " accepted joining node " + joiningNode
                    + ". neighbors=" + neighborsById.values(), Constant.GREEN);
            return new CommitResult(true, "direct target commit accepted");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a delete commit by removing one existing neighbor. This is used by
     * the evicted node when a direct target asks it to drop their old edge.
     *
     * @param txId         transaction ID
     * @param targetNodeId the neighbor to remove
     * @return the result of the transaction
     */
    public CommitResult applyDeleteCommit(String txId, String targetNodeId) {
        lock.lock();
        try {
            if (!processedTransactions.add("DELETE:" + txId + ":" + targetNodeId)) {
                Console.log("duplicate delete commit ignored");
                return new CommitResult(true, "duplicate delete commit ignored");
            }

            if (targetNodeId == null || targetNodeId.isBlank()) {
                Console.log("target node id is blank");
                return new CommitResult(false, "target node id is blank");
            }

            NodeAddress removed = neighborsById.remove(targetNodeId);
            if (removed == null) {
                Console.log("delete commit target " + targetNodeId + " was already absent");
                return new CommitResult(true, "delete commit target already absent");
            }

            version++;
            refreshHealthStateLocked();
            Console.log(localAddress.nodeId() + " applied delete commit and removed " + removed
                    + ". neighbors=" + neighborsById.values());
            return new CommitResult(true, "delete commit accepted");
        } finally {
            lock.unlock();
        }
    }

    /**
     * This function is called by the evicted node. The evicted node will add the
     * joining node as a neighbor and remove its old neighbor(now no longer removes its old neighbor -> that task is delegated to other method).
     * Now this method acts just like a small commit, just diff name for better logging
     * 
     * @param txId              transaction ID
     * @param joiningNode       the joining node
     * @param oldDirectTargetId the old neighbor
     * @return the result of the transaction
     */
    public CommitResult applyEvictedNodeCommit(String txId, NodeAddress joiningNode) {
        lock.lock();
        try {
            if (!processedTransactions.add("VICTIM:" + txId)) {
                Console.log("duplicate victim commit ignored");
                return new CommitResult(true, "duplicate victim commit ignored");
            }

            if (joiningNode == null) {
                Console.log("joining node is null");
                return new CommitResult(false, "joining node is null");
            }

            if (neighborsById.containsKey(joiningNode.nodeId())) {
                Console.log("Joining node already exists");
                return new CommitResult(true, "Joining node already exists");
            }

            if (neighborsById.size() >= maxNeighbors) {
                Console.log("Accepting " + joiningNode + " will exceed neighbor limit.",
                        Constant.BG_PURPLE);
                return new CommitResult(false, "Small join target has no free neighbor slot");
            }

            if (joiningNode.nodeId().equals(localAddress.nodeId())) {
                Console.log("Cannot add self");
                return new CommitResult(false, "Cannot add self");
            }

            // NodeAddress removed = null;
            // if (oldDirectTargetId != null && !oldDirectTargetId.isBlank()) {
            //     removed = neighborsById.remove(oldDirectTargetId);
            // }

            
            neighborsById.put(joiningNode.nodeId(), joiningNode);
            version++;
            refreshHealthStateLocked();
            Console.log(localAddress.nodeId() + " is a victim and added " + joiningNode
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
                Console.log("Duplicate small join commit ignored");
                return new CommitResult(true, "Duplicate small join commit ignored");
            }

            if (joiningNode == null) {
                Console.log("Joining node is null");
                return new CommitResult(false, "Joining node is null");
            }

            if (joiningNode.nodeId().equals(localAddress.nodeId())) {
                Console.log("Cannot add self");
                return new CommitResult(false, "Cannot add self");
            }

            if (neighborsById.containsKey(joiningNode.nodeId())) {
                Console.log("Joining node already exists");
                return new CommitResult(true, "Joining node already exists");
            }

            if (neighborsById.size() >= maxNeighbors) {
                Console.log("Accepting " + joiningNode + " will exceed neighbor limit.",
                        Constant.BG_PURPLE);
                return new CommitResult(false, "Small join target has no free neighbor slot");
            }

            neighborsById.put(joiningNode.nodeId(), joiningNode);
            version++;
            refreshHealthStateLocked();

            Console.log("Node " + localAddress.nodeId() + " accepted small-network joining " + joiningNode
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
    public List<NodeAddress> neighborAddresses() {
        lock.lock();
        try {
            return new ArrayList<>(neighborsById.values());
        } finally {
            lock.unlock();
        }
    }

    public List<String> neighborIds() {
        lock.lock();
        try {
            return new ArrayList<>(neighborsById.keySet());
        } finally {
            lock.unlock();
        }
    }

    public boolean isInNetwork() {
        return isInNetwork;
    }

    public void setInNetwork(boolean isInNetwork) {
        this.isInNetwork = isInNetwork;
    }

    /**
     * Applies the rewiring scheme. Connects to a node and disconnects from another
     * node.
     * 
     * @param txId            the transaction ID
     * @param connectsTo      connects to this node
     * @param disconnectsFrom disconnects from this node
     * @param reason          the reason
     * @return true if rewiring is successful or is already carried out, false if
     *         after rewiring, the local node exceeds neighbor limit.
     */
    public boolean applyRewireScheme(String txId, NodeAddress connectsTo, NodeAddress disconnectsFrom, String reason) {
        lock.lock();
        try {
            if (!processedTransactions.add("REWIRE_SCHEME:" + txId)) {
                Console.log("TxID already exists");
                return true;
            }

            if (disconnectsFrom != null) {
                neighborsById.remove(disconnectsFrom.nodeId());
            }

            if (connectsTo != null && !connectsTo.nodeId().equals(localAddress.nodeId())) {
                neighborsById.put(connectsTo.nodeId(), connectsTo);
            }

            if (neighborsById.size() > maxNeighbors) {
                // maybe not rollback because trust?
                // if (connectsTo != null) {
                // neighborsById.remove(connectsTo.nodeId());
                // }

                // if (disconnectsFrom != null) {
                // neighborsById.put(disconnectsFrom.nodeId(), disconnectsFrom);
                // }

                refreshHealthStateLocked();
                Console.log("Neighbors exceeded");
                return false;
            }

            version++;
            refreshHealthStateLocked();

            Console.log("[REWIRE] " + localAddress.nodeId()
                    + " applied scheme. connectsTo=" + connectsTo
                    + ", disconnectsFrom=" + disconnectsFrom
                    + ", reason=" + reason
                    + ", neighbors=" + neighborsById.keySet());

            return true;
        } finally {
            lock.unlock();
        }
    }

    public HealthState getHealthState() {
        return healthState;
    }

    public void setHealthState(HealthState healthState) {
        this.healthState = healthState;
    }

    public void refreshHealthStateLocked() {
        healthState = neighborsById.size() < maxNeighbors
                ? HealthState.DEFICIENT
                : HealthState.SUFFICIENT;
    }
}
