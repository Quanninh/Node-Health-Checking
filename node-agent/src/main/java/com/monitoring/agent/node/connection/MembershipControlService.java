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

public final class MembershipControlService implements AutoCloseable {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final int controlPort;
    private final int bufferSize;

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private DatagramSocket serverSocket;

    public MembershipControlService(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            int controlPort,
            int bufferSize) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.controlPort = controlPort;
        this.bufferSize = bufferSize;
    }

    public void start() throws SocketException {
        serverSocket = new DatagramSocket(controlPort);
        running = true;
        serverExecutor.submit(this::serverLoop);

        Console.log("Membership UDP control server listening on UDP port " + controlPort);
    }

    boolean commitDirectTarget(
            NodeAddress directTarget,
            NodeAddress joiningNode,
            NodeAddress evictedNode,
            String txId) {
        DiscoveryMessage command = new DiscoveryMessage(
                "COMMIT_DIRECT",
                txId,
                1,
                joiningNode,
                0,
                0,
                List.of(),
                directTarget.nodeId(),
                evictedNode == null ? null : evictedNode.nodeId());

        return sendReliableCommand(directTarget, command);
    }

    boolean commitVictim(
            NodeAddress victim,
            NodeAddress joiningNode,
            NodeAddress oldDirectTarget,
            String txId) {
        DiscoveryMessage command = new DiscoveryMessage(
                "COMMIT_VICTIM",
                txId,
                1,
                joiningNode,
                0,
                0,
                List.of(),
                oldDirectTarget.nodeId(),
                victim.nodeId());

        return sendReliableCommand(victim, command);
    }

    private boolean sendReliableCommand(
            NodeAddress target,
            DiscoveryMessage command) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(700);

                byte[] bytes = command.encode().getBytes(StandardCharsets.UTF_8);

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

                if ("COMMIT_ACK".equals(response.type())
                        && command.txId().equals(response.txId())) {
                    Console.log("Commit ACK from " + target + " for txId=" + command.txId());
                    return true;
                }
            } catch (Exception exception) {
                Console.log("Commit attempt " + attempt + " failed for " + target
                        + ": " + exception.getMessage());
            }
        }

        return false;
    }

    boolean commitSmallJoinTarget(
        NodeAddress target,
        NodeAddress joiningNode,
        String txId
    ) {
        DiscoveryMessage command = new DiscoveryMessage(
                "COMMIT_SMALL_JOIN",
                txId,
                1,
                joiningNode,
                0,
                0,
                List.of(),
                target.nodeId(),
                null
        );

        return sendReliableCommand(target, command);
    }

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

                if ("COMMIT_SMALL_JOIN".equals(message.type())) {
                    result = connectionManager.applySmallJoinCommit(
                            message.txId(),
                            message.sender()
                    );
                } else if ("COMMIT_DIRECT".equals(message.type())) {
                    result = connectionManager.applyDirectTargetCommit(
                            message.txId(),
                            message.sender(),
                            message.evictedNodeId()
                    );
                } else if ("COMMIT_VICTIM".equals(message.type())) {
                    result = connectionManager.applyEvictedNodeCommit(
                            message.txId(),
                            message.sender(),
                            message.directTargetId()
                    );
                } else {
                    continue;
                }

                sendCommitAck(packet.getAddress(), packet.getPort(), message.txId(), result.accepted());
            } catch (SocketException exception) {
                if (running) {
                    Console.log("Membership socket error: " + exception.getMessage());
                }
            } catch (Exception exception) {
                Console.log("Membership control error: " + exception.getMessage());
            }
        }
    }

    private void sendCommitAck(
            InetAddress address,
            int port,
            String txId,
            boolean accepted) throws IOException {
        DiscoveryMessage ack = new DiscoveryMessage(
                "COMMIT_ACK",
                txId,
                1,
                localAddress,
                0,
                0,
                List.of(),
                accepted ? "accepted" : "rejected",
                null);

        byte[] bytes = ack.encode().getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                address,
                port);

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