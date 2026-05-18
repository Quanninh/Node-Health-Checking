package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PeerDirectory {
    private final List<PeerAddress> peers;
    private final Map<String, PeerState> peerStates;
    private int nextIndex = 0;

    PeerDirectory(List<PeerAddress> peers) {
        this.peers = new ArrayList<>(peers);
        this.peerStates = new ConcurrentHashMap<>();

        for (PeerAddress peer : peers) {
            peerStates.put(peer.nodeId(), new PeerState(peer));
        }
    }

    synchronized Optional<PeerAddress> nextPeer() {
        if (peers.isEmpty()) {
            return Optional.empty();
        }

        if (nextIndex >= peers.size()) {
            Collections.shuffle(peers);
            nextIndex = 0;

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Completed one full peer cycle. Shuffled peer list: "
                            + peers
            );
        }

        PeerAddress selectedPeer = peers.get(nextIndex);
        nextIndex++;

        return Optional.of(selectedPeer);
    }

    void markAlive(String nodeId) {
        PeerState state = peerStates.get(nodeId);

        if (state != null) {
            state.markAlive();
        }
    }

    void markFailed(String nodeId) {
        PeerState state = peerStates.get(nodeId);

        if (state != null) {
            state.markFailed();
        }
    }

    PeerStatus getStatus(String nodeId) {
        PeerState state = peerStates.get(nodeId);

        if (state == null) {
            return PeerStatus.UNKNOWN;
        }

        return state.status();
    }

    List<PeerState> states() {
        return new ArrayList<>(peerStates.values());
    }
}
