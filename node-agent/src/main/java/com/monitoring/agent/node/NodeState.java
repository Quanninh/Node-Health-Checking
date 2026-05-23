package com.monitoring.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.monitoring.agent.constant.Constant;

/**
 * Represents the state of a node.
 */
public class NodeState {

    private final NodeAddress nodeAddress;
    private final List<Double> slidingWindowSeconds;
    private volatile NodeStatus status;
    private volatile long lastAckTimeMs;
    private volatile LocalDateTime lastAckTime;
    private volatile LocalDateTime lastSuspicionTime;
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
            System.out.println(
                    "\n[" + Constant.NOW() + "] "
                            + "ACK received from " + nodeAddress.nodeId()
                            + ", but it is already UNREACHABLE locally. "
                            + "It must rejoin as a new node instance.");
            return;
        }

        long now = System.currentTimeMillis();

        if (lastAckTimeMs > 0) {
            double intervalSeconds = (now - pingSendTime) / 1000.0;
            phiDetector.updateSlidingWindow(slidingWindowSeconds, intervalSeconds);
            // Just for make sure the elapse time is correct

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "ACK received from node " + nodeAddress.nodeId()
                            + " | pingSendTimeMillis=" + pingSendTime
                            + " | ackReceiveTimeMillis=" + now
                            + " | elapsed=" + String.format("%.4f", intervalSeconds) + " seconds"
                            + " | slidingWindow=" + slidingWindowSeconds);
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
            return;
        }

        if (status == NodeStatus.UNREACHABLE) {
            System.out.println("\n[" + Constant.NOW() + "] "
                    + "ALIVE gossip for " + nodeAddress.nodeId()
                    + " is newer, but local state is UNREACHABLE. Treat this as requiring JOIN/rejoin, not simple recovery.");
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
        return "NodeState{\n\tnodeAddress=" + Constant.CYAN + nodeAddress + Constant.RESET +
                ", status=" + Constant.YELLOW + status + Constant.RESET +
                ",\n\tphi=" + String.format("%.4f", phi) +
                ", slidingWindowSeconds=" + slidingWindowSeconds +
                ", lastAckTime=" + lastAckTime +
                ", lastSuspicionTime=" + lastSuspicionTime +
                ", lastUnreachableTime=" + lastUnreachableTime +
                ", incarnationNumber=" + incarnationNumber +
                "}";
    }

}
