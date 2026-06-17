package com.monitoring.agent.node.connection;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.JoinAck;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

public final class MulticastJoinCoordinator {

    private final NodeAddress localAddress;
    private final int maxNeighbors;
    private final ConnectionManager connectionManager;
    private final MulticastDiscoveryService discoveryService;
    private final MembershipControlService membershipControlService;
    private final JoinPlanner joinPlanner;

    public MulticastJoinCoordinator(
            NodeAddress localAddress,
            int maxNeighbors,
            ConnectionManager connectionManager,
            MulticastDiscoveryService discoveryService,
            MembershipControlService membershipControlService) {
        this.localAddress = localAddress;
        this.maxNeighbors = maxNeighbors;
        this.connectionManager = connectionManager;
        this.discoveryService = discoveryService;
        this.membershipControlService = membershipControlService;
        this.joinPlanner = new JoinPlanner(localAddress, maxNeighbors);
    }

    public void joinNetwork() {
        try {
            List<JoinAck> acks = discoveryService.discoverPeers();

            if (acks.isEmpty()) {
                Console.logInfo("No peers discovered -> FIRST NODE");
                connectionManager.setInNetwork(true);
                return;
            }

            JoinPlan plan = joinPlanner.createPlan(acks);

            if (plan.directTargets().isEmpty()) {
                Console.logInfo("No valid join plan. Node remains alone temporarily.");
                return;
            }

            joinHybridNetwork(plan);
        } catch (IOException exception) {
            Console.log("Join failed: " + exception.getMessage(), Constant.RED);
        }
    }

    /**
     * Join a "hybrid network". Connects with the direct targets, and tells the
     * direct targets to evicts some neighbors.
     * 
     * @param plan the join plan
     */
    private void joinHybridNetwork(JoinPlan plan) {
        String txId = UUID.randomUUID().toString();

        for (NodeAddress directTarget : plan.directTargets()) {
            // safety check, probably useless
            if (connectionManager.size() >= maxNeighbors) {
                break;
            }

            NodeAddress victim = plan.evictionByDirectTarget().get(directTarget);
            if (victim == null) {
                directConnect(txId, directTarget);
            } else {
                joinAndEvict(txId, directTarget, victim);
            }
        }

        Console.logSuccess("Hybrid join complete. Current neighbors=" + connectionManager.neighborIds());
        connectionManager.setInNetwork(!connectionManager.neighborAddresses().isEmpty());
    }

    private void directConnect(String txId, NodeAddress directTarget) {
        boolean committed = membershipControlService.commitSmallJoinTarget(
                directTarget, localAddress, txId + ":small:" + directTarget.nodeId());

        if (!committed) {
            Console.logError("Small-network commit failed for " + directTarget.nodeId() + committed);
            return;
        }

        connectionManager.addIfSpace(directTarget, "small-network committed join");
        Console.logSuccess("Direct connect: " + localAddress.nodeId() + " & " + directTarget.nodeId());
    }

    private void joinAndEvict(String txId, NodeAddress directTarget, NodeAddress victim) {
        boolean directCommitted = membershipControlService.commitDirectTarget(
                directTarget, localAddress, victim, txId + ":direct:" + directTarget.nodeId());

        if (!directCommitted) {
            Console.log("Direct target commit failed for " + directTarget, Constant.RED);
            return;
        }

        connectionManager.addIfSpace(directTarget, "scaled join direct target");
        Console.logSuccess(localAddress.nodeId() + " successfully connected with direct target " + directTarget
                + " after it deleted old edge to " + victim);

        boolean victimCommitted = membershipControlService.commitVictim(victim, localAddress, directTarget,
                txId + ":victim:" + victim.nodeId());
        if (!victimCommitted) {
            Console.logError("Victim commit failed for " + victim);
            return;
        }

        connectionManager.addIfSpace(victim, "scaled join evicted handover");
        Console.logSuccess(localAddress.nodeId() + " successfully + handed over evicted node " + victim);
    }

}
