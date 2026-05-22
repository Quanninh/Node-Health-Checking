package com.monitoring.agent.node;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.constant.Constant;

/**
 * Gossips or receives gossips about the status of a target node.
 */
public class GossipService {

    private final String localNodeId;
    private final NeighborDirectory neighborDirectory;
    private final NodeClient nodeClient;
    private final int defaultTtl;
    private final Set<String> seenMessages;

    public GossipService(String localNodeId, NeighborDirectory neighborDirectory, NodeClient nodeClient,
            int defaultTtl) {
        this.localNodeId = localNodeId;
        this.neighborDirectory = neighborDirectory;
        this.nodeClient = nodeClient;
        this.defaultTtl = defaultTtl;
        this.seenMessages = ConcurrentHashMap.newKeySet();
    }

    /**
     * Gossips that the target node is suspected to be dead.
     * 
     * @param targetNode the suspected target node
     */
    public void gossipSuspect(NodeAddress targetNode) {
        GossipMessage message = createSuspectMessage(targetNode);
        System.out.println("\n[" + Constant.NOW() + "] "
                + "Created gossip message: type=SUSPECT, subjectNodeId="
                + targetNode.nodeId()
                + ", sourceNodeId=" + localNodeId);
        receiveGossip(message, localNodeId);
    }

    /**
     * Gossips that the target node is unreachable.
     * 
     * @param targetNode the unreachable target node
     */
    public void gossipUnreachable(NodeAddress targetNode) {
        GossipMessage message = createUnreachableMessage(targetNode);
        System.out.println("\n[" + Constant.NOW() + "] "
                + "Created gossip message: type=UNREACHABLE, subjectNodeId="
                + targetNode.nodeId()
                + ", sourceNodeId=" + localNodeId);
        receiveGossip(message, localNodeId);
    }

    /**
     * Gossips that the target node is alive.
     * 
     * @param targetNode the alive target node
     */
    public void gossipAlive(NodeAddress targetNode) {
        GossipMessage message = createAliveMessage(targetNode);
        receiveGossip(message, localNodeId);
    }

    private void gossipJoin(String subjectNodeId, int incarnationNumber) {
        GossipMessage message = createMessage(
                subjectNodeId,
                GossipMessageType.JOIN,
                incarnationNumber,
                "Node " + subjectNodeId + " joined/rejoined.");
        receiveGossip(message, localNodeId);
    }

    /**
     * Creates an UNREACHABLE message with
     * {@link #createMessage(String, GossipMessageType, int, String)}.
     * 
     * @param targetNode
     * @return the UNREACHABLE gossip message
     */
    private GossipMessage createUnreachableMessage(NodeAddress targetNode) {
        return createMessage(
                targetNode.nodeId(),
                GossipMessageType.UNREACHABLE,
                neighborDirectory.incarnationNumber(targetNode.nodeId()),
                "Node " + targetNode.nodeId() + " is UNREACHABLE.");
    }

    /**
     * Creates a SUSPECT message with
     * {@link #createMessage(String, GossipMessageType, int, String)}.
     * 
     * @param targetNode
     * @return the SUSPECT gossip message
     */
    private GossipMessage createSuspectMessage(NodeAddress targetNode) {
        return createMessage(
                targetNode.nodeId(),
                GossipMessageType.SUSPECTED,
                neighborDirectory.incarnationNumber(targetNode.nodeId()),
                "Node " + targetNode.nodeId() + " is SUSPECTED.");
    }

    /**
     * Creates an ALIVE message with
     * {@link #createMessage(String, GossipMessageType, int, String)}.
     * 
     * @param targetNode
     * @return the ALIVE gossip message
     */
    private GossipMessage createAliveMessage(NodeAddress targetNode) {
        return createMessage(
                targetNode.nodeId(),
                GossipMessageType.ALIVE,
                neighborDirectory.incarnationNumber(targetNode.nodeId()),
                "Node " + targetNode.nodeId() + " is ALIVE.");
    }

    /**
     * Creates a gossip message
     * 
     * @param subjectNodeId     the target node
     * @param messageType       message type
     * @param incarnationNumber incarnation number
     * @param details           extra details
     * @return
     */
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

    /**
     * Receives a gossip message. Will ignore duplicated gossip messages. Otherwise,
     * add the message to the list of seen messages. Messages will be processed by
     * {@link #applyGossipMessage(GossipMessage)}.
     * 
     * @param message      the gossip message
     * @param senderNodeId sender node id
     */
    public void receiveGossip(GossipMessage message, String senderNodeId) {
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

        System.out.println("\n[" + Constant.NOW() + "] "
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

    /**
     * Forwards a given gossip message to all its neighbors.
     * 
     * @param message      the gossip message
     * @param senderNodeId the sender of the original gossip message
     */
    private void forwardGossip(GossipMessage message, String senderNodeId) {
        if (message.ttl() <= 0) {
            return;
        }

        System.out.println("\n[" + Constant.NOW() + "] "
                + "Forwarding gossip message type=" + message.messageType()
                + ", subjectNodeId=" + message.subjectNodeId()
                + ", ttl=" + message.ttl());

        for (NodeAddress neighborNode : neighborDirectory.neighborList()) {
            if (neighborNode.nodeId().equals(senderNodeId) || neighborNode.nodeId().equals(localNodeId)) {
                continue;
            }

            if (neighborDirectory.getStatus(neighborNode.nodeId()) == NodeStatus.UNREACHABLE) {
                continue;
            }

            sendGossipMessage(neighborNode, message);
        }
    }

    /**
     * Stores the information received from the gossip message into the neighbor
     * directory.
     * 
     * @param message the gossip message
     * @see NeighborDirectory
     */
    private void applyGossipMessage(GossipMessage message) {
        neighborDirectory.applyGossipStatus(
                message.subjectNodeId(),
                message.messageType(),
                message.incarnationNumber());
    }

    /**
     * Sends a gossip message to the destination node.
     * 
     * @param destinationNode the destination
     * @param message         the gossip message
     */
    private void sendGossipMessage(NodeAddress destinationNode, GossipMessage message) {
        System.out.println("\n[" + Constant.NOW() + "] "
                + "Sending gossip message type=" + message.messageType()
                + ", subjectNodeId=" + message.subjectNodeId()
                + " to " + destinationNode.nodeId());
        nodeClient.sendGossipMessage(destinationNode, message);
    }

}
