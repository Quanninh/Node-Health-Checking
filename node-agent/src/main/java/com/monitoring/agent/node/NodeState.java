package com.monitoring.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.util.Console;

/**
 * Represents the state of a node.
 */
public class NodeState {

    private final NodeAddress nodeAddress;
    private final List<Double> slidingWindowSeconds;
    private volatile NodeStatus status;
    private volatile long lastAckTimeMs;
    @SuppressWarnings("unused")
    private volatile LocalDateTime lastAckTime;
    @SuppressWarnings("unused")
    private volatile LocalDateTime lastSuspicionTime;
    @SuppressWarnings("unused")
    private volatile LocalDateTime lastUnreachableTime;
    private volatile double phi;
    private volatile int incarnationNumber;

    public NodeState(NodeAddress nodeAddress) {
        this.nodeAddress = nodeAddress;
        this.slidingWindowSeconds = Collections.synchronizedList(new ArrayList<>());
        this.status = NodeStatus.UNKNOWN;
        this.lastAckTimeMs = -1L;
        this.phi = 0.0;
        this.incarnationNumber = 0;
    }

    /**
     * Marks this node as ALIVE. Also updates the sliding window of the phi accrual
     * failure detector.
     * 
     * @param phiDetector the phi accrual failure detector
     */
    public synchronized void markAlive(PhiAccrualFailure phiDetector, long pingSendTime) {
        if (status == NodeStatus.UNREACHABLE) {
            Console.log("ACK received from " + nodeAddress.nodeId()
                    + ", but it is already UNREACHABLE locally. "
                    + "It must rejoin as a new node instance.", Constant.PINK);
            return;
        }

        long now = System.currentTimeMillis();

        if (lastAckTimeMs > 0) {
            double intervalSeconds = (now - pingSendTime) / 1000.0;
            phiDetector.updateSlidingWindow(slidingWindowSeconds, intervalSeconds);

            Console.log("ACK received from node " + nodeAddress.nodeId()
                    + " | elapsed=" + String.format("%.4f", intervalSeconds) + " seconds", Constant.PURPLE);
        }

        this.status = NodeStatus.ALIVE;
        this.lastAckTimeMs = now;
        this.lastAckTime = LocalDateTime.now();
        this.phi = 0.0;
        this.incarnationNumber++;
    }

    /**
     * Marks this node as ALIVE from gossipping IF the received message's
     * incarnation number is greater than this node's current incarnation number. If
     * this node's state is UNREACHABLE, the gossip will be discarded.
     * 
     * @param messageIncarnationNumber
     */
    public synchronized void markAliveFromGossip(int messageIncarnationNumber) {
        if (messageIncarnationNumber <= this.incarnationNumber) {
            Console.log("Older incarnation -> skip");
            return;
        }

        if (status == NodeStatus.UNREACHABLE) {
            Console.log(
                    "ALIVE gossip for " + nodeAddress.nodeId() + " is newer, but local state is UNREACHABLE -> skip",
                    Constant.PINK);
            return;
        }

        this.status = NodeStatus.ALIVE;
        this.phi = 0.0;
        this.lastAckTime = LocalDateTime.now();
        this.lastAckTimeMs = System.currentTimeMillis();
        this.incarnationNumber = messageIncarnationNumber;
    }

    /**
     * Marks this node as SUSPECTED.
     * 
     * @param phi
     */
    public synchronized void markSuspected(double phi) {
        if (status == NodeStatus.UNREACHABLE) {
            Console.log("Node is unreachable -> no need to mark suspected");
            return;
        }

        this.status = NodeStatus.SUSPECTED;
        this.lastSuspicionTime = LocalDateTime.now();
        this.phi = phi;
    }

    /**
     * Marks this node as SUSPECTED from gossipping IF the received message's
     * incarnation number is greater than or equal to this node's current
     * incarnation number
     * 
     * @param messageIncarnationNumber
     */
    public synchronized void markSuspectedFromGossip(int messageIncarnationNumber) {
        if (status == NodeStatus.UNREACHABLE || messageIncarnationNumber < this.incarnationNumber) {
            Console.log("Node is unreachable or old incarnation -> skip");
            return;
        }

        this.status = NodeStatus.SUSPECTED;
        this.lastSuspicionTime = LocalDateTime.now();
        this.incarnationNumber = Math.max(this.incarnationNumber, messageIncarnationNumber);
    }

    /**
     * Marks this node as WARNING.
     * 
     * @param phi
     */
    public synchronized void markWarning(double phi) {
        if (status == NodeStatus.UNREACHABLE) {
            Console.log("Node is unreachable");
            return;
        }

        this.status = NodeStatus.WARNING;
        this.phi = phi;
    }

    /**
     * Marks this node as UNREACHABLE.
     * 
     * @param phi
     */
    public synchronized void markUnreachable(double phi) {
        this.status = NodeStatus.UNREACHABLE;
        this.lastUnreachableTime = LocalDateTime.now();
        this.phi = phi;
        this.incarnationNumber++;
    }

    /**
     * Marks this node as UNREACHABLE from gossipping IF the received message's
     * incarnation number is greater than or equal to this node's current
     * incarnation number
     * 
     * @param messageIncarnationNumber
     */
    public synchronized void markUnreachableFromGossip(int messageIncarnationNumber) {
        if (messageIncarnationNumber < this.incarnationNumber) {
            Console.log("Old incarnation -> skip");
            return;
        }

        this.status = NodeStatus.UNREACHABLE;
        this.lastUnreachableTime = LocalDateTime.now();
        this.phi = Double.POSITIVE_INFINITY;
        this.incarnationNumber = Math.max(this.incarnationNumber, messageIncarnationNumber);
    }

    public NodeAddress getNodeAddress() {
        return nodeAddress;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public long getLastAckTimeMs() {
        return lastAckTimeMs;
    }

    public List<Double> getSlidingWindowSeconds() {
        return slidingWindowSeconds;
    }

    public double getPhi() {
        return phi;
    }

    public int getIncarnationNumber() {
        return incarnationNumber;
    }

    @Override
    public String toString() {
        return "NodeState{\n\tnodeAddress=" + Constant.CYAN + Constant.BG_RED + nodeAddress + Constant.RESET +
                ", status=" + Constant.PURPLE + Constant.BG_RED + status + Constant.RESET +
                "\n}";
    }

}
