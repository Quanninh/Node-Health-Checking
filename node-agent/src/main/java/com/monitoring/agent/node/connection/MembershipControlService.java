package com.monitoring.agent.node.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

/**
 * Receives and sends ACKs for Discovery Messages.
 */
public final class MembershipControlService implements AutoCloseable {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final int controlPort;
    private final int bufferSize;

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private DatagramSocket serverSocket;

    public MembershipControlService(NodeAddress localAddress, ConnectionManager connectionManager, int controlPort,
            int bufferSize) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.controlPort = controlPort;
        this.bufferSize = bufferSize;
    }

    /**
     * Starts the membership UDP control server.
     * 
     * @throws SocketException
     */
    public void start() throws SocketException {
        serverSocket = new DatagramSocket(controlPort);
        running = true;
        serverExecutor.submit(this::serverLoop);

        Console.log("Membership UDP control server listening on UDP port " + controlPort);
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
     * Sends a discovery message reliably to the target node.
     * 
     * @param target           the target node address
     * @param discoveryMessage the discovery message
     * @return
     */
    private boolean sendReliableCommand(NodeAddress target, DiscoveryMessage discoveryMessage) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(700);

                byte[] bytes = discoveryMessage.encode().getBytes(StandardCharsets.UTF_8);

                DatagramPacket packet = new DatagramPacket(
                        bytes,
                        bytes.length,
                        InetAddress.getByName(target.host()),
                        target.port());

                socket.send(packet);

                byte[] buffer = new byte[bufferSize];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);

                socket.receive(responsePacket);

                String raw = new String(
                        responsePacket.getData(),
                        responsePacket.getOffset(),
                        responsePacket.getLength(),
                        StandardCharsets.UTF_8);

                DiscoveryMessage response = DiscoveryMessage.decode(raw);

                if (response.type() == DiscoveryMessageType.COMMIT_ACK
                        && discoveryMessage.transactionId().equals(response.transactionId())) {
                    Console.log("Commit ACK from " + target + " for txId=" + discoveryMessage.transactionId());
                    return true;
                }
            } catch (Exception exception) {
                Console.log("Commit attempt " + attempt + " failed for " + target
                        + ": " + exception.getMessage());
            }
        }

        return false;
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
     * Server loop.
     * 
     * 1. Receives a packet.
     * 2. Converts the packet into a discovery message.
     * 3. Check the type of the message and handle accordingly.
     * 4. Sends a COMMIT_ACK message back to the sender.
     */
    private void serverLoop() {
        while (running) {
            try {
                byte[] buffer = new byte[bufferSize];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                serverSocket.receive(packet);

                String raw = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8);

                DiscoveryMessage message = DiscoveryMessage.decode(raw);

                CommitResult result;

                if (null == message.type()) {
                    continue;
                } else
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
                            continue;
                        }
                    }

                sendCommitAck(packet.getAddress(), packet.getPort(), message.transactionId(), result.accepted());
            } catch (SocketException exception) {
                if (running) {
                    Console.log("Membership socket error: " + exception.getMessage());
                }
            } catch (IOException exception) {
                Console.log("Membership control error: " + exception.getMessage());
            }
        }
    }

    /**
     * Sends COMMIT_ACK discovery message.
     * 
     * @param address
     * @param port
     * @param txId     transaction ID
     * @param accepted whether the commit is accepted or not
     * @throws IOException
     */
    private void sendCommitAck(InetAddress address, int port, String txId, boolean accepted) throws IOException {
        DiscoveryMessage ack = new DiscoveryMessage(
                DiscoveryMessageType.COMMIT_ACK,
                txId,
                1,
                localAddress,
                true,
                0,
                0,
                List.of(),
                accepted ? "accepted" : "rejected",
                null);

        byte[] bytes = ack.encode().getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);

        serverSocket.send(packet);
    }

    @Override
    public void close() {
        running = false;

        if (serverSocket != null) {
            serverSocket.close();
        }

        serverExecutor.shutdownNow();
    }

}