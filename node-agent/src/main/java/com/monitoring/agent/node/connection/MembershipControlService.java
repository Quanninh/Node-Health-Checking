package com.monitoring.agent.node.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.transport.UdpCoordinator;
import com.monitoring.agent.node.transport.UdpEnvelope;
import com.monitoring.agent.node.transport.UdpPacketType;
import com.monitoring.agent.util.Console;

/**
 * Receives and sends ACKs for Discovery Messages using UdpCoordinator.
 * 
 * This service manages both incoming commands (via consumer callback) and
 * outgoing
 * commands with response waiting (via CompletableFutures).
 */
public final class MembershipControlService implements AutoCloseable {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final UdpCoordinator udpCoordinator;

    // Map to track pending responses by transaction ID
    private final ConcurrentHashMap<String, CompletableFuture<DiscoveryMessage>> pendingResponses = new ConcurrentHashMap<>();

    public MembershipControlService(NodeAddress localAddress, ConnectionManager connectionManager,
            UdpCoordinator udpCoordinator) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.udpCoordinator = udpCoordinator;
    }

    /**
     * Starts the membership control service by registering the consumer callback.
     */
    public void start() {
        udpCoordinator.registerMembershipConsumer(this::handleMembershipPacket);
        Console.log("Membership Control Service started");
    }

    /**
     * Sends a COMMIT_DIRECT discovery message.
     * 
     * @param directTarget the target node
     * @param joiningNode  the joining node
     * @param evictedNode  the evicted node
     * @param txId         transaction ID
     * @return the commit result
     * @see ConnectionManager#applyDirectTargetCommit(String, NodeAddress, String)
     */
    public boolean commitDirectTarget(NodeAddress directTarget, NodeAddress joiningNode, NodeAddress evictedNode,
            String txId) {
        DiscoveryMessage command = new DiscoveryMessage(
                DiscoveryMessageType.COMMIT_DIRECT,
                txId,
                1,
                joiningNode,
                false,
                0,
                0,
                List.of(),
                directTarget.nodeId(),
                evictedNode == null ? null : evictedNode.nodeId());

        return sendReliableCommand(directTarget, command);
    }

    /**
     * Sends a COMMIT_VICTIM message.
     * 
     * @param victim          the evicted node
     * @param joiningNode     the joining node
     * @param oldDirectTarget the old direct target node
     * @param txId            transaction ID
     * @return the commit result
     */
    public boolean commitVictim(NodeAddress victim, NodeAddress joiningNode, NodeAddress oldDirectTarget, String txId) {
        DiscoveryMessage command = new DiscoveryMessage(
                DiscoveryMessageType.COMMIT_VICTIM,
                txId,
                1,
                joiningNode,
                false,
                0,
                0,
                List.of(),
                oldDirectTarget.nodeId(),
                victim.nodeId());

        return sendReliableCommand(victim, command);
    }

    /**
     * Commits a SMALL_JOIN message.
     * 
     * @param target
     * @param joiningNode
     * @param txId
     * @return
     */
    public boolean commitSmallJoinTarget(NodeAddress target, NodeAddress joiningNode, String txId) {
        DiscoveryMessage command = new DiscoveryMessage(
                DiscoveryMessageType.COMMIT_SMALL_JOIN,
                txId,
                1,
                joiningNode,
                false,
                0,
                0,
                List.of(),
                target.nodeId(),
                null);

        return sendReliableCommand(target, command);
    }

    /**
     * Handles incoming membership packets from the UdpCoordinator.
     * Processes COMMIT commands and sends back ACKs.
     * 
     * @param envelope the UDP envelope containing the discovery message
     */
    private void handleMembershipPacket(UdpEnvelope envelope) {
        try {
            DiscoveryMessage message = DiscoveryMessage.decode(envelope.payload());

            if (message.type() == null) {
                return;
            }

            // Check if this is a response to a pending request
            if (message.type() == DiscoveryMessageType.COMMIT_ACK) {
                CompletableFuture<DiscoveryMessage> future = pendingResponses.remove(message.transactionId());
                if (future != null) {
                    future.complete(message);
                }
                return;
            }

            // Handle incoming commands
            CommitResult result;
            switch (message.type()) {
                case COMMIT_SMALL_JOIN -> result = connectionManager.applySmallJoinCommit(
                        message.transactionId(),
                        message.sender());
                case COMMIT_DIRECT -> result = connectionManager.applyDirectTargetCommit(
                        message.transactionId(),
                        message.sender(),
                        message.evictedNodeId());
                case COMMIT_VICTIM -> result = connectionManager.applyEvictedNodeCommit(
                        message.transactionId(),
                        message.sender(),
                        message.directTargetId());
                default -> {
                    return;
                }
            }

            sendCommitAck(message.sender(), message.transactionId(), result.accepted());

        } catch (Exception ex) {
            System.getLogger(MembershipControlService.class.getName())
                    .log(System.Logger.Level.ERROR, "Error handling membership packet: " + ex.getMessage(), ex);
        }
    }

    /**
     * Sends a discovery message reliably to the target node and waits for an ACK.
     * 
     * @param target           the target node address
     * @param discoveryMessage the discovery message
     * @return true if ACK received and accepted, false otherwise
     */
    private boolean sendReliableCommand(NodeAddress target, DiscoveryMessage discoveryMessage) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // Create a future to wait for the response
                CompletableFuture<DiscoveryMessage> responseFuture = new CompletableFuture<>();
                pendingResponses.put(discoveryMessage.transactionId(), responseFuture);

                // Send the command via UDP using a temporary socket
                byte[] bytes = discoveryMessage.encode().getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                        bytes,
                        bytes.length,
                        InetAddress.getByName(target.host()),
                        target.port());

                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.send(packet);
                }

                // Wait for the response with a timeout
                DiscoveryMessage response = responseFuture.get(700, TimeUnit.MILLISECONDS);

                if (response.type() == DiscoveryMessageType.COMMIT_ACK
                        && discoveryMessage.transactionId().equals(response.transactionId())) {
                    boolean accepted = Boolean.parseBoolean(response.directTargetId());
                    Console.log("Commit ACK from " + target + " for txId=" + discoveryMessage.transactionId()
                            + " status=" + response.directTargetId() + " accepted=" + accepted);
                    return accepted;
                }
            } catch (Exception exception) {
                Console.log("Commit attempt " + attempt + " failed for " + target
                        + ": " + exception.getMessage());
                pendingResponses.remove(discoveryMessage.transactionId());
            }
        }

        return false;
    }

    /**
     * Sends a COMMIT_ACK discovery message to the sender.
     * 
     * @param recipient the node to send the ACK to
     * @param txId      transaction ID
     * @param accepted  whether the commit is accepted or not
     * @throws IOException if sending fails
     */
    private void sendCommitAck(NodeAddress recipient, String txId, boolean accepted) throws IOException {
        DiscoveryMessage ack = new DiscoveryMessage(
                DiscoveryMessageType.COMMIT_ACK,
                txId,
                1,
                localAddress,
                true,
                0,
                0,
                List.of(),
                accepted ? "true" : "false",
                null);

        String encodedMessage = ack.encode();
        udpCoordinator.send(recipient.host(), recipient.port(), UdpPacketType.MEMBERSHIP, encodedMessage);
    }

    @Override
    public void close() {
        // Cleanup is handled by UdpCoordinator
        pendingResponses.clear();
    }

}