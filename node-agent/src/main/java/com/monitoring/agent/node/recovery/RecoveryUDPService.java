package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.util.Console;

/**
 * Sends the recovery message to the target node using UDP.
 */
public class RecoveryUDPService {

    private final int port;
    private final int bufferSize;
    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final NetworkTopologyCache networkTopologyCache;

    private volatile boolean running;
    private DatagramSocket serverSocket;

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

    public RecoveryUDPService(NodeAddress localAddress, NetworkTopologyCache networkTopologyCache,
            ConnectionManager connectionManager,
            int port, int bufferSize) {
        this.port = port;
        this.bufferSize = bufferSize;
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.networkTopologyCache = networkTopologyCache;
    }

    public void start() throws SocketException {
        serverSocket = new DatagramSocket(port);
        running = true;
        serverExecutor.submit(this::receiveLoop);

        Console.log("Recovery UDP Service server listening on UDP port " + port);
    }

    private void receiveLoop() {
        while (running) {
            try {
                RecoveryMessage message = receive(port, bufferSize);

                if (message.type() == null) {
                    continue;
                } else {
                    switch (message.type()) {
                        case DEFICIENT -> {
                            networkTopologyCache.markDeficient(message.subject().nodeId());
                            send(message.sender(),
                                    new RecoveryMessage(RecoveryMessageType.DEFICIENT_ACK, message.messageId(),
                                            message.repairEpoch(), localAddress, message.subject(), message.target(),
                                            message.neighbors(), Constant.DEFAULT_GOSSIP_TTL,
                                            System.currentTimeMillis(), 0));
                            for (NodeAddress neighbor : connectionManager.neighborAddresses()) {
                                try {
                                    send(neighbor,
                                            new RecoveryMessage(message.type(), message.messageId(),
                                                    message.repairEpoch(), message.sender(), message.subject(),
                                                    message.target(), message.neighbors(),
                                                    Math.max(message.ttl() - 1, 0),
                                                    System.currentTimeMillis(), message.incarnation()));

                                    Console.log(
                                            "Gossiping DEFICIENT message [" + message + "] to " + neighbor + " success",
                                            Constant.BG_CYAN + Constant.BOLD);
                                } catch (IOException e) {
                                    Console.log(
                                            "Failed to gossip message [" + message + "] to " + neighbor + " because "
                                                    + e.getMessage(),
                                            Constant.RED);
                                }
                            }
                        }
                        case DEFICIENT_ACK -> {
                            Console.log("Received DEFICIENT_ACK. Doing nothing with it.");
                        }
                        default -> throw new AssertionError();
                    }
                }
            } catch (IOException ex) {
                System.getLogger(RecoveryUDPService.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }

    /**
     * Sends the recovery message to the target node using UDP.
     * 
     * @param target  the target node
     * @param message the recovery message
     * @throws IOException
     */
    public void send(NodeAddress target, RecoveryMessage message) throws IOException {
        Console.log("Before sending: " + message + " to " + target.toString(), Constant.BG_GREEN);
        byte[] bytes = message.encode()
                .getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                InetAddress.getByName(target.host()),
                target.port());

        // serverSocket.send(packet);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(packet);
        }
        Console.log("After sending: " + message + " to " + target.toString(), Constant.BG_GREEN);
    }

    public RecoveryMessage receive(int port, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.receive(packet);

            String raw = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8);

            Console.log("Received by Recovery UDP Service. Raw message: " + raw, Constant.BG_PURPLE);

            return RecoveryMessage.decode(raw);
        }
    }

}
