package com.example.agent.node;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class JoinCoordinator {
    private final NodeAddress localAddress;
    private final NeighborDirectory neighborDirectory;
    private final NodeClient nodeClient;
    private final List<NodeAddress> bootstrapPeers;
    private final int maxNeighbors;
    private final int joinTimeoutSeconds;
    private final double joinMinProbability;
    private final double joinMaxProbability;
    private final Random random;

    JoinCoordinator(
            NodeAddress localAddress,
            NeighborDirectory neighborDirectory,
            NodeClient nodeClient,
            List<NodeAddress> bootstrapPeers,
            int maxNeighbors,
            int joinTimeoutSeconds,
            double joinMinProbability,
            double joinMaxProbability) {
        this.localAddress = localAddress;
        this.neighborDirectory = neighborDirectory;
        this.nodeClient = nodeClient;
        this.bootstrapPeers = new ArrayList<>(bootstrapPeers);
        this.maxNeighbors = maxNeighbors;
        this.joinTimeoutSeconds = joinTimeoutSeconds;
        this.joinMinProbability = joinMinProbability;
        this.joinMaxProbability = joinMaxProbability;
        this.random = new Random();
    }

    void joinNetwork() {
        if (bootstrapPeers.isEmpty()) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "No bootstrap peers configured. Node starts as the first node in a new overlay.");
            return;
        }

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Sending HTTP JOIN requests to bootstrap peers: " + bootstrapPeers);

        List<CompletableFuture<Optional<JoinAck>>> joinRequests = bootstrapPeers.stream()
                .filter(peer -> !peer.nodeId().equals(localAddress.nodeId()))
                .map(peer -> nodeClient.join(peer, localAddress))
                .toList();

        CompletableFuture<Void> allRequests = CompletableFuture.allOf(joinRequests.toArray(new CompletableFuture[0]));

        try {
            allRequests.orTimeout(joinTimeoutSeconds, TimeUnit.SECONDS).join();
        } catch (Exception ignored) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "JOIN collection window ended. Continuing with replies received so far.");
        }

        List<JoinAck> replies = joinRequests.stream()
                .filter(CompletableFuture::isDone)
                .map(future -> future.getNow(Optional.empty()))
                .flatMap(Optional::stream)
                .filter(JoinAck::accepted)
                .toList();

        if (replies.isEmpty()) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "No JOIN_ACK replies received. Node remains alone until new peers join later.");
            return;
        }

        List<NodeAddress> candidates = chooseCandidates(replies);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Selected join candidates after random filtering: " + candidates);

        confirmCandidates(candidates);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "JOIN finished. Current neighbors: " + neighborDirectory.addresses());
    }

    private List<NodeAddress> chooseCandidates(List<JoinAck> replies) {
        Map<String, NodeAddress> uniqueCandidates = new LinkedHashMap<>();

        for (JoinAck reply : replies) {
            addCandidate(uniqueCandidates, reply.responder());
            addCandidate(uniqueCandidates, reply.suggestedPeer());
        }

        List<NodeAddress> candidates = new ArrayList<>(uniqueCandidates.values());
        Collections.shuffle(candidates);

        if (candidates.size() <= maxNeighbors) {
            return candidates;
        }

        double p = randomProbability();
        List<NodeAddress> filtered = new ArrayList<>();

        for (NodeAddress candidate : candidates) {
            if (random.nextDouble() <= p) {
                filtered.add(candidate);
            }
        }

        if (filtered.isEmpty()) {
            filtered.add(candidates.get(0));
        }

        Collections.shuffle(filtered);

        if (filtered.size() > maxNeighbors) {
            return new ArrayList<>(filtered.subList(0, maxNeighbors));
        }

        for (NodeAddress candidate : candidates) {
            if (filtered.size() >= maxNeighbors) {
                break;
            }

            if (!filtered.contains(candidate)) {
                filtered.add(candidate);
            }
        }

        return filtered;
    }


    private void confirmCandidates(List<NodeAddress> candidates) {
        List<NodeAddress> extraCandidatesFromRedistribution = new ArrayList<>();

        for (NodeAddress candidate : candidates) {
            if (neighborDirectory.size() >= maxNeighbors) {
                break;
            }

            Optional<JoinConfirmResult> result = nodeClient.confirmJoin(candidate, localAddress).join();

            if (result.isEmpty() || !result.get().accepted()) {
                continue;
            }

            neighborDirectory.addNeighbor(candidate);

            NodeAddress evicted = result.get().evictedPeer();
            if (evicted != null) {
                extraCandidatesFromRedistribution.add(evicted);
            }
        }

        Collections.shuffle(extraCandidatesFromRedistribution);

        for (NodeAddress redistributed : extraCandidatesFromRedistribution) {
            if (neighborDirectory.size() >= maxNeighbors) {
                break;
            }

            if (neighborDirectory.contains(redistributed.nodeId())
                    || redistributed.nodeId().equals(localAddress.nodeId())) {
                continue;
            }

            Optional<JoinConfirmResult> result = nodeClient.confirmJoin(redistributed, localAddress).join();

            if (result.isPresent() && result.get().accepted()) {
                neighborDirectory.addNeighbor(redistributed);
            }
        }
    }

    private void addCandidate(Map<String, NodeAddress> candidates, NodeAddress candidate) {
        if (candidate == null) {
            return;
        }

        if (candidate.nodeId().equals(localAddress.nodeId())) {
            return;
        }

        candidates.putIfAbsent(candidate.nodeId(), candidate);
    }

    private double randomProbability() {
        double min = Math.min(joinMinProbability, joinMaxProbability);
        double max = Math.max(joinMinProbability, joinMaxProbability);

        if (Double.compare(min, max) == 0) {
            return min;
        }

        return min + (random.nextDouble() * (max - min));
    }
}
