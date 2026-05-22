package com.example.agent.node;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.agent.constant.Constant;

public class GossipService {

    private final String localNodeId;
    private final NeighborDirectory neighborDirectory;
    private final NodeClient nodeClient;
    private final int defaultTtl;
    private final Set<String> seenMessages;

    GossipService(
            String localNodeId,
            NeighborDirectory neighborDirectory,
            NodeClient nodeClient,
            int defaultTtl) {
        this.localNodeId = localNodeId;
        this.neighborDirectory = neighborDirectory;
        this.nodeClient = nodeClient;
        this.defaultTtl = defaultTtl;
        this.seenMessages = ConcurrentHashMap.newKeySet();
    }

    void gossipSuspect(NodeAddress targetNode) {
        GossipMessage message = createSuspectMessage(targetNode);
        System.out.println("\n[" + Constant.NOW() + "] "
                + "Created gossip message: type=SUSPECT, subjectNodeId="
                + targetNode.nodeId()
                + ", sourceNodeId=" + localNodeId);
        receiveGossip(message, localNodeId);
    }

    void gossipUnreachable(NodeAddress targetNode) {
        GossipMessage message = createUnreachableMessage(targetNode);
        System.out.println("\n[" + Constant.NOW() + "] "
                + "Created gossip message: type=UNREACHABLE, subjectNodeId="
                + targetNode.nodeId()
                + ", sourceNodeId=" + localNodeId);
        receiveGossip(message, localNodeId);
    }

    void gossipAlive(NodeAddress targetNode) {
        GossipMessage message = createAliveMessage(targetNode);
        receiveGossip(message, localNodeId);
    }

    void gossipJoin(String subjectNodeId, int incarnationNumber) {
        GossipMessage message = createMessage(
                subjectNodeId,
                GossipMessageType.JOIN,
                incarnationNumber,
                "Node " + subjectNodeId + " joined/rejoined.");
        receiveGossip(message, localNodeId);
    }

    GossipMessage createUnreachableMessage(NodeAddress targetNode) {
        return createMessage(
                targetNode.nodeId(),
                GossipMessageType.UNREACHABLE,
                neighborDirectory.incarnationNumber(targetNode.nodeId()),
                "Node " + targetNode.nodeId() + " is UNREACHABLE.");
    }

    GossipMessage createSuspectMessage(NodeAddress targetNode) {
        return createMessage(
                targetNode.nodeId(),
                GossipMessageType.SUSPECT,
                neighborDirectory.incarnationNumber(targetNode.nodeId()),
                "Node " + targetNode.nodeId() + " is SUSPECTED.");
    }

    GossipMessage createAliveMessage(NodeAddress targetNode) {
        return createMessage(
                targetNode.nodeId(),
                GossipMessageType.ALIVE,
                neighborDirectory.incarnationNumber(targetNode.nodeId()),
                "Node " + targetNode.nodeId() + " is ALIVE.");
    }

    private GossipMessage createMessage(
            String subjectNodeId,
            GossipMessageType messageType,
            int incarnationNumber,
            String details) {
        return new GossipMessage(
                UUID.randomUUID().toString(),
                localNodeId,
                subjectNodeId,
                messageType,
                incarnationNumber,
                System.currentTimeMillis(),
                defaultTtl,
                details);
    }

    void receiveGossip(GossipMessage message, String senderNodeId) {
        if (message == null) {
            return;
        }

        if (seenMessages.contains(message.messageId())) {
            System.out.println(
                    "\n[" + Constant.NOW() + "] "
                            + "Duplicate gossip message ignored: " + message.messageId());
            return;
        }

        seenMessages.add(message.messageId());

        System.out.println(
                "\n[" + Constant.NOW() + "] "
                        + "Received gossip message type=" + message.messageType()
                        + ", subjectNodeId=" + message.subjectNodeId()
                        + ", sourceNodeId=" + message.sourceNodeId()
                        + ", senderNodeId=" + senderNodeId
                        + ", ttl=" + message.ttl());

        applyGossipMessage(message);

        if (message.ttl() > 0) {
            forwardGossip(message.decrementTtl(), senderNodeId);
        }
    }

    void forwardGossip(GossipMessage message, String senderNodeId) {
        if (message.ttl() <= 0) {
            return;
        }

        System.out.println("\n[" + Constant.NOW() + "] "
                + "Forwarding gossip message type=" + message.messageType()
                + ", subjectNodeId=" + message.subjectNodeId()
                + ", ttl=" + message.ttl());

        for (NodeAddress neighborNode : neighborDirectory.neighborList()) {
            if (neighborNode.nodeId().equals(localNodeId)) {
                continue;
            }

            if (neighborNode.nodeId().equals(senderNodeId)) {
                continue;
            }

            if (neighborDirectory.getStatus(neighborNode.nodeId()) == NodeStatus.UNREACHABLE) {
                continue;
            }

            sendGossipMessage(neighborNode, message);
        }
    }

    void applyGossipMessage(GossipMessage message) {
        neighborDirectory.applyGossipStatus(
                message.subjectNodeId(),
                message.messageType(),
                message.incarnationNumber());
    }

    void sendGossipMessage(NodeAddress destinationNode, GossipMessage message) {
        System.out.println("\n[" + Constant.NOW() + "] "
                + "Sending gossip message type=" + message.messageType()
                + ", subjectNodeId=" + message.subjectNodeId()
                + " to " + destinationNode.nodeId());
        nodeClient.sendGossipMessage(destinationNode, message);
    }
}
