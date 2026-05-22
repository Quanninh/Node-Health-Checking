package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NodeState {
    private final NodeAddress nodeAddress;
    private final List<Double> slidingWindowSeconds;
    private volatile NodeStatus status;
    private volatile long lastAckTimeMillis;
    private volatile LocalDateTime lastAckTime;
    private volatile LocalDateTime lastSuspicionTime;
    private volatile LocalDateTime lastUnreachableTime;
    private volatile double phi;
    private volatile int incarnationNumber;

    NodeState(NodeAddress nodeAddress) {
        this.nodeAddress = nodeAddress;
        this.slidingWindowSeconds = Collections.synchronizedList(new ArrayList<>());
        this.status = NodeStatus.UNKNOWN;
        this.lastAckTimeMillis = -1L;
        this.phi = 0.0;
        this.incarnationNumber = 0;
    }

    synchronized void markAlive(PhiAccrualFailure phiDetector) {
        if (status == NodeStatus.UNREACHABLE) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "ACK received from " + nodeAddress.nodeId()
                            + ", but it is already UNREACHABLE locally. "
                            + "It must rejoin as a new node instance.");
            return;
        }

        long now = System.currentTimeMillis();

        if (lastAckTimeMillis > 0) {
            double intervalSeconds = (now - lastAckTimeMillis) / 1000.0;
            phiDetector.updateSlidingWindow(slidingWindowSeconds, intervalSeconds);
        }

        this.status = NodeStatus.ALIVE;
        this.lastAckTimeMillis = now;
        this.lastAckTime = LocalDateTime.now();
        this.phi = 0.0;
        this.incarnationNumber++;
    }

    synchronized void markAliveFromGossip(int messageIncarnationNumber) {
        if (messageIncarnationNumber <= this.incarnationNumber) {
            return;
        }

        if (status == NodeStatus.UNREACHABLE) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "ALIVE gossip for " + nodeAddress.nodeId()
                            + " is newer, but local state is UNREACHABLE. "
                            + "Treat this as requiring JOIN/rejoin, not simple recovery.");
            return;
        }

        this.status = NodeStatus.ALIVE;
        this.phi = 0.0;
        this.lastAckTime = LocalDateTime.now();
        this.lastAckTimeMillis = System.currentTimeMillis();
        this.incarnationNumber = messageIncarnationNumber;
    }

    synchronized void markSuspected(double phi) {
        if (status == NodeStatus.UNREACHABLE) {
            return;
        }

        this.status = NodeStatus.SUSPECTED;
        this.lastSuspicionTime = LocalDateTime.now();
        this.phi = phi;
    }

    synchronized void markWarning(double phi) {
        if (status == NodeStatus.UNREACHABLE) {
            return;
        }

        this.status = NodeStatus.WARNING;
        this.phi = phi;
    }

    synchronized void markUnreachable(double phi) {
        this.status = NodeStatus.UNREACHABLE;
        this.lastUnreachableTime = LocalDateTime.now();
        this.phi = phi;
        this.incarnationNumber++;
    }

    synchronized void markUnreachableFromGossip(int messageIncarnationNumber) {
        if (messageIncarnationNumber < this.incarnationNumber) {
            return;
        }

        this.status = NodeStatus.UNREACHABLE;
        this.lastUnreachableTime = LocalDateTime.now();
        this.phi = Double.POSITIVE_INFINITY;
        this.incarnationNumber = Math.max(this.incarnationNumber, messageIncarnationNumber);
    }

    synchronized void markSuspectedFromGossip(int messageIncarnationNumber) {
        if (status == NodeStatus.UNREACHABLE || messageIncarnationNumber < this.incarnationNumber) {
            return;
        }

        this.status = NodeStatus.SUSPECTED;
        this.lastSuspicionTime = LocalDateTime.now();
        this.incarnationNumber = Math.max(this.incarnationNumber, messageIncarnationNumber);
    }


    NodeAddress address() {
        return nodeAddress;
    }

    NodeStatus status() {
        return status;
    }

    long lastAckTimeMillis() {
        return lastAckTimeMillis;
    }

    List<Double> slidingWindowSeconds() {
        return slidingWindowSeconds;
    }

    double phi() {
        return phi;
    }

    int incarnationNumber() {
        return incarnationNumber;
    }

    @Override
    public String toString() {
        return "NodeState{" +
                "nodeAddress=" + nodeAddress +
                ", status=" + status +
                ", phi=" + String.format("%.4f", phi) +
                ", slidingWindowSeconds=" + slidingWindowSeconds +
                ", lastAckTime=" + lastAckTime +
                ", lastSuspicionTime=" + lastSuspicionTime +
                ", lastUnreachableTime=" + lastUnreachableTime +
                ", incarnationNumber=" + incarnationNumber +
                '}';
    }
}
