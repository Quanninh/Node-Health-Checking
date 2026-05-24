package com.monitoring.agent.node.connection;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
                                Console.log("No peers discovered. Node starts as the first node.");
                                connectionManager.setInNetwork(true);
                                return;
                        }

                        JoinPlan plan = joinPlanner.createPlan(acks);

                        if (plan.directTargets().isEmpty()) {
                                Console.log("No valid join plan. Node remains alone temporarily.");
                                return;
                        }

                        joinHybridNetwork(plan);

                        // if (acks.size() <= maxNeighbors) {
                        // joinSmallNetwork(plan);
                        // return;
                        // }

                        // joinScaledNetwork(plan);
                        // } catch (IOException exception) {
                        // joinScaledNetwork(plan);
                } catch (Exception exception) {
                        exception.printStackTrace();
                        ;
                        Console.log("Join failed: " + exception.getMessage());
                }
        }

        private void joinSmallNetwork(JoinPlan plan) {
                String txId = UUID.randomUUID().toString();

                for (NodeAddress peer : plan.directTargets()) {
                        if (connectionManager.size() >= maxNeighbors) {
                                break;
                        }

                        boolean committed = membershipControlService.commitSmallJoinTarget(
                                        peer,
                                        localAddress,
                                        txId + ":small:" + peer.nodeId());

                        if (!committed) {
                                Console.log("Small-network commit failed for " + peer
                                                + ". Not adding it locally to avoid one-way neighbor state.");
                                continue;
                        }

                        connectionManager.addIfSpace(peer, "small-network committed join");

                        Console.log("Small-network bidirectional join established between "
                                        + localAddress.nodeId()
                                        + " and "
                                        + peer.nodeId());
                }

                Console.log("Small-network join complete. Current neighbors="
                                + connectionManager.neighborAddresses());
                connectionManager.setInNetwork(!connectionManager.neighborAddresses().isEmpty());
        }

        private void joinHybridNetwork(JoinPlan plan) {
                String txId = UUID.randomUUID().toString();

                for (NodeAddress directTarget : plan.directTargets()) {
                        // safety check, probably useless
                        if (connectionManager.size() >= maxNeighbors) {
                                break;
                        }

                        NodeAddress victim = plan.evictionByDirectTarget().get(directTarget);

                        // join without evicting
                        if (victim == null) {
                                boolean committed = membershipControlService.commitSmallJoinTarget(
                                                directTarget,
                                                localAddress,
                                                txId + ":small:" + directTarget.nodeId());

                                if (!committed) {
                                        Console.log("Small-network commit failed for "
                                                        + directTarget + committed, Constant.BG_BLUE);
                                        continue;
                                }

                                connectionManager.addIfSpace(
                                                directTarget,
                                                "small-network committed join");

                                Console.log("Small-network bidirectional join established between "
                                                + localAddress.nodeId()
                                                + " and "
                                                + directTarget.nodeId());

                                continue;
                        }

                        // join and evict
                        boolean directCommitted = membershipControlService.commitDirectTarget(
                                        directTarget,
                                        localAddress,
                                        victim,
                                        txId + ":direct:" + directTarget.nodeId());

                        if (!directCommitted) {
                                Console.log("Direct target commit failed for "
                                                + directTarget);
                                continue;
                        }

                        boolean victimCommitted = membershipControlService.commitVictim(
                                        victim,
                                        localAddress,
                                        directTarget,
                                        txId + ":victim:" + victim.nodeId());

                        if (!victimCommitted) {
                                Console.log("Victim commit failed for "
                                                + victim
                                                + ". Failure detector/repair should fix this later.");
                                continue;
                        }

                        connectionManager.addIfSpace(
                                        directTarget,
                                        "scaled join direct target");

                        connectionManager.addIfSpace(
                                        victim,
                                        "scaled join evicted handover");

                        Console.log("Node "
                                        + localAddress.nodeId()
                                        + " successfully connected with direct target "
                                        + directTarget
                                        + " and handed over evicted node "
                                        + victim);
                }

                Console.log("Hybrid join complete. Current neighbors="
                                + connectionManager.neighborAddresses());

                connectionManager.setInNetwork(
                                !connectionManager.neighborAddresses().isEmpty());
        }

        // NOTE: remember to handle node failures
        private void joinScaledNetwork(JoinPlan plan) {
                String txId = UUID.randomUUID().toString();

                for (Map.Entry<NodeAddress, NodeAddress> entry : plan.evictionByDirectTarget().entrySet()) {
                        NodeAddress directTarget = entry.getKey();
                        NodeAddress victim = entry.getValue();

                        boolean directCommitted = membershipControlService.commitDirectTarget(
                                        directTarget,
                                        localAddress,
                                        victim,
                                        txId + ":direct:" + directTarget.nodeId());

                        if (!directCommitted) {
                                Console.log("Direct target commit failed for " + directTarget);
                                continue;
                        }

                        boolean victimCommitted = membershipControlService.commitVictim(
                                        victim,
                                        localAddress,
                                        directTarget,
                                        txId + ":victim:" + victim.nodeId());

                        if (!victimCommitted) {
                                Console.log("Victim commit failed for " + victim
                                                + ". Failure detector/repair should fix this later.");
                                continue;
                        }

                        connectionManager.addIfSpace(directTarget, "scaled join direct target");
                        connectionManager.addIfSpace(victim, "scaled join evicted handover");

                        Console.log("Node " + localAddress.nodeId()
                                        + " successfully connected with direct target " + directTarget
                                        + " and handed over evicted node " + victim);
                }

                // for (NodeAddress directTarget : plan.directTargets()) {
                // if (connectionManager.size() >= maxNeighbors) {
                // break;
                // }

                // connectionManager.addIfSpace(directTarget, "fallback direct target");
                // }

                Console.log("Scaled join complete. Current neighbors="
                                + connectionManager.neighborAddresses());
                connectionManager.setInNetwork(!connectionManager.neighborAddresses().isEmpty());
        }

}