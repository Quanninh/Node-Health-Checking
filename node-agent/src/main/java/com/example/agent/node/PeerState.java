package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PeerState {
    private final PeerAddress peerAddress;
    private final List<Double> slidingWindowSeconds;
    // volatile -> make sure that the variable is updated latest (in context of
    // thread)
    private volatile PeerStatus status;
    private volatile long lastAckTimeMillis;
    private volatile LocalDateTime lastAckTime;
    private volatile LocalDateTime lastSuspicionTime;
    private volatile LocalDateTime lastUnreachableTime;
    private volatile double phi;
    private volatile int incarnationNumber;

    PeerState(PeerAddress peerAddress) {
        this.peerAddress = peerAddress;
        this.slidingWindowSeconds = Collections.synchronizedList(new ArrayList<>());
        this.status = PeerStatus.UNKNOWN;
        this.lastAckTimeMillis = -1L;
        this.phi = 0.0;
        this.incarnationNumber = 0;
    }

    synchronized void markAlive(PhiAccrualFailureDetector phiDetector) {
        if (status == PeerStatus.UNREACHABLE) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "ACK received from " + peerAddress.nodeId()
                            + ", but it is already UNREACHABLE locally. "
                            + "It must rejoin as a new node instance.");
            return;
        }

        long now = System.currentTimeMillis();

        if (lastAckTimeMillis > 0) {
            double intervalSeconds = (now - lastAckTimeMillis) / 1000.0;
            phiDetector.updateSlidingWindow(slidingWindowSeconds, intervalSeconds);
        }

        this.status = PeerStatus.ALIVE;
        this.lastAckTimeMillis = now;
        this.lastAckTime = LocalDateTime.now();
        this.phi = 0.0;
    }

    // synchronized means that if it called, it lock the object ( in this we lock
    // the object "this")
    synchronized void markSuspected(double phi) {
        if (status == PeerStatus.UNREACHABLE) {
            return;
        }

        this.status = PeerStatus.SUSPECTED;
        this.lastSuspicionTime = LocalDateTime.now();
        this.phi = phi;
    }

    synchronized void markWarning(double phi) {
        if (status == PeerStatus.UNREACHABLE) {
            return;
        }

        this.status = PeerStatus.WARNING;
        this.phi = phi;
    }

    synchronized void markUnreachable(double phi) {
        this.status = PeerStatus.UNREACHABLE;
        this.lastUnreachableTime = LocalDateTime.now();
        this.phi = phi;
        this.incarnationNumber++;
    }

    PeerAddress address() {
        return peerAddress;
    }

    PeerStatus status() {
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

    @Override
    public String toString() {
        return "PeerState{" +
                "peerAddress=" + peerAddress +
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
