package com.monitoring.agent.node;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MulticastJoinCoordinator {

    private final NodeAddress localAddress;
    private final int maxNeighbors;
    private final ConnectionManager connectionManager;
    private final MulticastDiscoveryService discoveryService;
    private final MembershipControlService membershipControlService;
    private final JoinPlanner joinPlanner;

    MulticastJoinCoordinator(
            NodeAddress localAddress,
            int maxNeighbors,
            ConnectionManager connectionManager,
            MulticastDiscoveryService discoveryService,
            MembershipControlService membershipControlService
    ) {
        this.localAddress = localAddress;
        this.maxNeighbors = maxNeighbors;
        this.connectionManager = connectionManager;
        this.discoveryService = discoveryService;
        this.membershipControlService = membershipControlService;
        this.joinPlanner = new JoinPlanner(localAddress, maxNeighbors);
    }

    void joinNetwork() {
        try {
            List<JoinAck> acks = discoveryService.discoverPeers();

            if (acks.isEmpty()) {
                log("No peers discovered. Node starts as the first node.");
                return;
            }

            JoinPlanner.JoinPlan plan = joinPlanner.createPlan(acks);

            if (plan.directTargets().isEmpty()) {
                log("No valid join plan. Node remains alone temporarily.");
                return;
            }

            if (acks.size() < maxNeighbors) {
                joinSmallNetwork(plan);
                return;
            }

            joinScaledNetwork(plan);
        } catch (Exception exception) {
            log("Join failed: " + exception.getMessage());
        }
    }

    private void joinSmallNetwork(JoinPlanner.JoinPlan plan) {
        for (NodeAddress peer : plan.directTargets()) {
            connectionManager.addIfSpace(peer, "small-network multicast join");
        }

        log("Small-network join complete. Current neighbors="
                + connectionManager.addresses());
    }

    // NOTE: remember to handle node failures
    private void joinScaledNetwork(JoinPlanner.JoinPlan plan) {
        String txId = UUID.randomUUID().toString();

        for (Map.Entry<NodeAddress, NodeAddress> entry : plan.evictionByDirectTarget().entrySet()) {
            NodeAddress directTarget = entry.getKey();
            NodeAddress victim = entry.getValue();

            boolean directCommitted = membershipControlService.commitDirectTarget(
                    directTarget,
                    localAddress,
                    victim,
                    txId + ":direct:" + directTarget.nodeId()
            );

            if (!directCommitted) {
                log("Direct target commit failed for " + directTarget);
                continue;
            }

            boolean victimCommitted = membershipControlService.commitVictim(
                    victim,
                    localAddress,
                    directTarget,
                    txId + ":victim:" + victim.nodeId()
            );

            if (!victimCommitted) {
                log("Victim commit failed for " + victim
                        + ". Failure detector/repair should fix this later.");
                continue;
            }

            connectionManager.addIfSpace(directTarget, "scaled join direct target");
            connectionManager.addIfSpace(victim, "scaled join evicted handover");

            log("Node " + localAddress.nodeId()
                    + " successfully connected with direct target " + directTarget
                    + " and handed over evicted node " + victim);
        }

        for (NodeAddress directTarget : plan.directTargets()) {
            if (connectionManager.size() >= maxNeighbors) {
                break;
            }

            connectionManager.addIfSpace(directTarget, "fallback direct target");
        }

        log("Scaled join complete. Current neighbors="
                + connectionManager.addresses());
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now() + "] " + message);
    }
}