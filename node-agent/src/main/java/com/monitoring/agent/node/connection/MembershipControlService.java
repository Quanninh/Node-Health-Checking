package com.monitoring.agent.node.connection;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.monitoring.agent.constant.Constant;
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

    private static final Duration DIRECT_COMMIT_RESULT_TTL = Duration.ofSeconds(60);

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final UdpCoordinator udpCoordinator;

    // Map to track pending responses by transaction ID
    private final ConcurrentHashMap<String, CompletableFuture<DiscoveryMessage>> pendingResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<CommitResult>> directCommitResults = new ConcurrentHashMap<>();
    private final ExecutorService directCommitExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Membership-Direct-Commit");
        thread.setDaemon(false);
        return thread;
    });
    private final ScheduledExecutorService directCommitCleanupExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "Membership-Direct-Commit-Cleanup");
                thread.setDaemon(false);
                return thread;
            });

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
        Console.log("Membership Control Service started", Constant.GREEN);
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
     * Sends a COMMIT_DELETE message.
     *
     * @param victim the node that should delete the direct target
     * @param txId   transaction ID
     * @return the commit result
     */
    public boolean commitDelete(NodeAddress victim, String txId) {
        DiscoveryMessage command = new DiscoveryMessage(
                DiscoveryMessageType.COMMIT_DELETE,
                txId,
                1,
                localAddress,
                false,
                0,
                0,
                List.of(),
                localAddress.nodeId(),
                victim.nodeId());

        return sendReliableCommand(victim, command, DiscoveryMessageType.COMMIT_DELETE_ACK);
    }

    /**
     * Sends a COMMIT_VICTIM message.
     * 
     * @param victim          the evicted node
     * @param joiningNode     the joining node
     * @param oldDirectTarget the old direct target node, NOT USED ANYMORE
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
                Console.log("Invalid message: " + message);
                return;
            }

            Console.log("[MEMBERSHIP] Received " + message.type() + " txId=" + message.transactionId() + " from "
                    + message.sender());

            // Check if this is a response to a pending request
            if (message.type() == DiscoveryMessageType.COMMIT_ACK
                    || message.type() == DiscoveryMessageType.COMMIT_DELETE_ACK) {
                CompletableFuture<DiscoveryMessage> future = pendingResponses.remove(message.transactionId());
                if (future != null) {
                    future.complete(message);
                }
                Console.log("Message is a " + message.type() + ". Resolved");
                return;
            }

            // Handle incoming commands
            CommitResult result;
            DiscoveryMessageType ackType = DiscoveryMessageType.COMMIT_ACK;
            switch (message.type()) {
                case COMMIT_SMALL_JOIN -> result = connectionManager.applySmallJoinCommit(
                        message.transactionId(),
                        message.sender());
                case COMMIT_DIRECT -> {
                    handleDirectCommitAsync(message);
                    return;
                }
                case COMMIT_VICTIM -> result = connectionManager.applyEvictedNodeCommit(
                        message.transactionId(),
                        message.sender());
                case COMMIT_DELETE -> {
                    result = connectionManager.applyDeleteCommit(
                            message.transactionId(),
                            message.directTargetId());
                    ackType = DiscoveryMessageType.COMMIT_DELETE_ACK;
                }
                default -> {
                    Console.log(
                            "Message not COMMIT_SMALL_JOIN, COMMIT_DIRECT, COMMIT_VICTIM, COMMIT_DELETE, but rather "
                                    + message.type(),
                            Constant.PURPLE);
                    return;
                }
            }

            sendCommitAck(message.sender(), message.transactionId(), result.accepted(), ackType);

        } catch (IOException ex) {
            Console.log("Error handling membership packet; " + ex.getMessage(), Constant.RED);
        }
    }

    private void handleDirectCommitAsync(DiscoveryMessage message) {
        CompletableFuture<CommitResult> resultFuture = directCommitResults.computeIfAbsent(
                message.transactionId(),
                ignored -> createDirectCommitFuture(message));

        resultFuture.thenAccept(result -> {
            try {
                sendCommitAck(
                        message.sender(),
                        message.transactionId(),
                        result.accepted(),
                        DiscoveryMessageType.COMMIT_ACK);
            } catch (IOException exception) {
                Console.log("Failed to send async COMMIT_ACK txId=" + message.transactionId()
                        + " to " + message.sender().nodeId()
                        + ": " + exception.getMessage(), Constant.RED);
            }
        });
    }

    private CompletableFuture<CommitResult> createDirectCommitFuture(DiscoveryMessage message) {
        CompletableFuture<CommitResult> resultFuture = CompletableFuture
                .supplyAsync(() -> handleDirectCommit(message), directCommitExecutor)
                .exceptionally(exception -> {
                    Console.log("Direct commit failed for txId=" + message.transactionId()
                            + ": " + exception.getMessage(), Constant.RED);
                    return new CommitResult(false, "direct commit failed");
                });

        resultFuture.whenComplete((result, exception) -> scheduleDirectCommitResultCleanup(
                message.transactionId(),
                resultFuture));

        return resultFuture;
    }

    private void scheduleDirectCommitResultCleanup(
            String transactionId,
            CompletableFuture<CommitResult> resultFuture) {
        directCommitCleanupExecutor.schedule(
                () -> {
                    boolean removed = directCommitResults.remove(transactionId, resultFuture);
                    if (removed) {
                        Console.log("Expired cached direct commit result txId=" + transactionId);
                    }
                },
                DIRECT_COMMIT_RESULT_TTL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Method used to handle COMMIT_DIRECT received from a joining node. This basically works like this: 
     * 1. The target node asks the evicted node to delete their contact
     * 2. Upon the success of the above step begins the target to add the new node to its list
     * This ensures in the worst case, when new node can add the target but fail to add the evicted one, we only have two incomplete nodes(which can be resolved via rewiring)
     * Most importantly, this flow ensures eventual completeness
     * @param message the message received by the target node
     * @return the commit result of the operation: failed if the target node fails to break its connection with the evicted node
     */
    private CommitResult handleDirectCommit(DiscoveryMessage message) {
        String evictedNodeId = message.evictedNodeId();
        // this is kinda a unnecessary check
        if (evictedNodeId == null || evictedNodeId.isBlank()) {
            return connectionManager.applyDirectTargetCommit(
                    message.transactionId(),
                    message.sender(),
                    evictedNodeId);
        }

        NodeAddress victim = connectionManager.neighborAddresses().stream()
                .filter(neighbor -> evictedNodeId.equals(neighbor.nodeId()))
                .findFirst()
                .orElse(null);

        if (victim == null) {
            Console.log("Direct commit victim " + evictedNodeId + " is not a current neighbor");
            return new CommitResult(false, "direct commit victim is not a current neighbor");
        }

        boolean deleteCommitted = commitDelete(
                victim,
                message.transactionId() + ":delete:" + evictedNodeId);

        if (!deleteCommitted) {
            Console.log("Delete commit failed for victim " + victim.nodeId(), Constant.RED);
            return new CommitResult(false, "delete commit failed");
        }

        connectionManager.remove(evictedNodeId, "delete commit acknowledged by victim");

        return connectionManager.applyDirectTargetCommit(
                message.transactionId(),
                message.sender(),
                evictedNodeId);
    }

    /**
     * Sends a discovery message reliably to the target node and waits for an ACK.
     * 
     * @param target           the target node address
     * @param discoveryMessage the discovery message
     * @return true if ACK received and accepted, false otherwise
     */
    private boolean sendReliableCommand(NodeAddress target, DiscoveryMessage discoveryMessage) {
        return sendReliableCommand(target, discoveryMessage, DiscoveryMessageType.COMMIT_ACK);
    }

    private boolean sendReliableCommand(
            NodeAddress target,
            DiscoveryMessage discoveryMessage,
            DiscoveryMessageType expectedAckType) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // Create a future to wait for the response
                CompletableFuture<DiscoveryMessage> responseFuture = new CompletableFuture<>();
                pendingResponses.put(discoveryMessage.transactionId(), responseFuture);

                // Send command via UdpCoordinator (payload is wrapped in UdpEnvelope)
                Console.log("[MEMBERSHIP] Sending " + discoveryMessage.type()
                        + " txId=" + discoveryMessage.transactionId() + " to " + target.nodeId());
                udpCoordinator.send(localAddress, target, UdpPacketType.MEMBERSHIP, discoveryMessage.encode());

                // Wait for the response with a timeout
                DiscoveryMessage response = responseFuture.get(700, TimeUnit.MILLISECONDS);

                if (response.type() == expectedAckType
                        && discoveryMessage.transactionId().equals(response.transactionId())) {
                    boolean accepted = Boolean.parseBoolean(response.directTargetId());
                    Console.log(response.type() + " from " + target + " for txId=" + discoveryMessage.transactionId()
                            + " status=" + response.directTargetId() + " accepted=" + accepted);
                    return accepted;
                }
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException exception) {
                Console.log("Commit attempt " + attempt + " failed for " + target
                        + ": " + exception.getClass().getSimpleName()
                        + " - " + exception.getMessage(), Constant.BG_RED);
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
        sendCommitAck(recipient, txId, accepted, DiscoveryMessageType.COMMIT_ACK);
    }

    private void sendCommitAck(NodeAddress recipient, String txId, boolean accepted, DiscoveryMessageType ackType)
            throws IOException {
        DiscoveryMessage ack = new DiscoveryMessage(
                ackType,
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
        Console.log("[MEMBERSHIP] Sending " + ackType + " txId=" + txId
                + " accepted=" + accepted
                + " to " + recipient.nodeId());
        udpCoordinator.send(localAddress, recipient, UdpPacketType.MEMBERSHIP, encodedMessage);
    }

    @Override
    public void close() {
        // Cleanup is handled by UdpCoordinator
        pendingResponses.clear();
        directCommitResults.clear();
        directCommitExecutor.shutdownNow();
        directCommitCleanupExecutor.shutdownNow();
    }

}
