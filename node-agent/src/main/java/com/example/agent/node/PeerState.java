package com.example.agent.node;

import java.time.LocalDateTime;

public class PeerState {
    private final PeerAddress peerAddress;
    private volatile PeerStatus status;
    private volatile LocalDateTime lastAckTime;
    private volatile LocalDateTime lastFailureTime;

    PeerState(PeerAddress peerAddress) {
        this.peerAddress = peerAddress;
        this.status = PeerStatus.UNKNOWN;
    }

    void markAlive() {
        this.status = PeerStatus.ALIVE;
        this.lastAckTime = LocalDateTime.now();
    }

    void markFailed() {
        this.status = PeerStatus.FAILED;
        this.lastFailureTime = LocalDateTime.now();
    }

    PeerStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return "PeerState{" +
                "peerAddress=" + peerAddress +
                ", status=" + status +
                ", lastAckTime=" + lastAckTime +
                ", lastFailureTime=" + lastFailureTime +
                '}';
    }
}
