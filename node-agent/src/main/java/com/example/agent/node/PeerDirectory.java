package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
        List<PeerAddress> reachablePeers = peers.stream()
                .filter(peer -> getStatus(peer.nodeId()) != PeerStatus.UNREACHABLE)
                .toList();

        if (reachablePeers.isEmpty()) {
            return Optional.empty();
        }

        if (nextIndex >= peers.size()) {
            Collections.shuffle(peers);
            nextIndex = 0;

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Completed one full peer cycle. Shuffled peer list: "
                            + peers);
        }

        int attempts = 0;
        while (attempts < peers.size()) {
            PeerAddress selectedPeer = peers.get(nextIndex);
            nextIndex = (nextIndex + 1) % peers.size();
            attempts++;

            if (getStatus(selectedPeer.nodeId()) != PeerStatus.UNREACHABLE) {
                return Optional.of(selectedPeer);
            }
        }

        return Optional.empty();
    }

    void markAlive(String nodeId, PhiAccrualFailureDetector phiDetector) {
        PeerState state = peerStates.get(nodeId);

        if (state != null) {
            state.markAlive(phiDetector);
        }
    }

    void markWarning(String nodeId, double phi) {
        PeerState state = peerStates.get(nodeId);

        if (state != null) {
            state.markWarning(phi);
        }
    }

    void markSuspected(String nodeId, double phi) {
        PeerState state = peerStates.get(nodeId);

        if (state != null) {
            state.markSuspected(phi);
        }
    }

    void markUnreachable(String nodeId, double phi) {
        PeerState state = peerStates.get(nodeId);

        if (state != null) {
            state.markUnreachable(phi);
        }
    }

    PeerStatus getStatus(String nodeId) {
        PeerState state = peerStates.get(nodeId);

        if (state == null) {
            return PeerStatus.UNKNOWN;
        }

        return state.status();
    }

    Optional<PeerState> getState(String nodeId) {
        return Optional.ofNullable(peerStates.get(nodeId));
    }

    List<PeerAddress> selectKHelpers(String localNodeId, PeerAddress target, int k) {
        List<PeerAddress> candidates = new ArrayList<>();

        for (PeerAddress peer : peers) {
            if (peer.nodeId().equals(localNodeId)) {
                continue;
            }

            if (peer.nodeId().equals(target.nodeId())) {
                continue;
            }

            if (getStatus(peer.nodeId()) == PeerStatus.UNREACHABLE) {
                continue;
            }

            candidates.add(peer);
        }

        Collections.shuffle(candidates);

        int limit = Math.min(k, candidates.size());

        return new ArrayList<>(candidates.subList(0, limit));
    }

    List<PeerState> states() {
        List<PeerState> states = new ArrayList<>(peerStates.values());
        states.sort(Comparator.comparing(state -> state.address().nodeId()));
        return states;
    }
}