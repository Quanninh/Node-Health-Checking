package com.monitoring.agent.node.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.monitoring.agent.node.DiscoveryConfig;
import com.monitoring.agent.node.JoinAck;
import com.monitoring.agent.node.NodeAddress;

public final class MulticastDiscoveryService implements AutoCloseable {

    private final NodeAddress localAddress;
    private final DiscoveryConfig config;
    private final ConnectionManager connectionManager;

    private final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> seenAcks = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private MulticastSocket multicastSocket;

    public MulticastDiscoveryService(
            NodeAddress localAddress,
            DiscoveryConfig config,
            ConnectionManager connectionManager) {
        this.localAddress = localAddress;
        this.config = config;
        this.connectionManager = connectionManager;
    }

    public void startResponder() throws IOException {
        multicastSocket = new MulticastSocket(null);
        multicastSocket.setReuseAddress(true);
        multicastSocket.bind(new InetSocketAddress(config.multicastPort()));
        multicastSocket.setNetworkInterface(config.networkInterface());
        multicastSocket.setTimeToLive(1);

        InetSocketAddress groupAddress = new InetSocketAddress(config.multicastGroup(), config.multicastPort());

        multicastSocket.joinGroup(groupAddress, config.networkInterface());

        running = true;

        receiverExecutor.submit(this::receiveLoop);

        log("Joined multicast discovery group "
                + config.multicastGroup().getHostAddress()
                + ":" + config.multicastPort()
                + " on interface "
                + config.networkInterface().getName());
    }

    List<JoinAck> discoverPeers() throws IOException {
        String txId = UUID.randomUUID().toString();

        try (DatagramSocket replySocket = new DatagramSocket(0)) {
            replySocket.setSoTimeout((int) config.retryInterval().toMillis());

            int replyPort = replySocket.getLocalPort();

            for (int attempt = 1; attempt <= config.retryCount(); attempt++) {
                sendJoinRequest(txId, attempt, replyPort);
                List<JoinAck> collected = collectReplies(replySocket, txId, config.retryInterval());
                if (!collected.isEmpty()) {
                    return collected;
                }
                Thread.sleep(2000);
            }

            // return collectReplies(replySocket, txId, config.collectionWindow());
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return new ArrayList<JoinAck>();
    }

    private List<JoinAck> collectReplies(
            DatagramSocket replySocket,
            String txId,
            Duration window) {
        long deadline = System.nanoTime() + window.toNanos();
        Map<String, JoinAck> repliesByNodeId = new LinkedHashMap<>();

        while (System.nanoTime() < deadline) {
            try {
                byte[] buffer = new byte[config.packetBufferSize()];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                replySocket.receive(packet);

                String raw = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8);

                DiscoveryMessage message = DiscoveryMessage.decode(raw);

                log(message.txId() + message.type());
                if (!"JOIN_ACK".equals(message.type())) {
                    log("Join ACK received");
                    continue;
                }

                if (!txId.equals(message.txId())) {
                    log("wrong txId received");
                    continue;
                }

                if (message.sender().nodeId().equals(localAddress.nodeId())) {
                    continue;
                }

                String key = message.txId() + ":" + message.sender().nodeId();

                // add returns false if key already exist in set
                if (!seenAcks.add(key)) {
                    log("Duplicate KEY received");
                    continue;
                }

                JoinAck ack = new JoinAck(
                        message.txId(),
                        message.sender(),
                        message.neighborVersion(),
                        message.neighbors());

                repliesByNodeId.putIfAbsent(ack.responder().nodeId(), ack);

                log("Received JOIN_ACK from " + ack.responder()
                        + " with neighbors=" + ack.responderNeighbors());
            } catch (SocketTimeoutException ignored) {
                log("Socket timeout");
                break;
                // return new ArrayList<>(repliesByNodeId.values());
            } catch (Exception exception) {
                log("Ignored invalid discovery reply: " + exception.getMessage());
            }
        }

        return new ArrayList<>(repliesByNodeId.values());
    }

    private void sendJoinRequest(
            String txId,
            long sequence,
            int replyPort) throws IOException {
        DiscoveryMessage message = new DiscoveryMessage(
                "JOIN_REQUEST",
                txId,
                sequence,
                localAddress,
                replyPort,
                0,
                List.of(),
                null,
                null);

        byte[] bytes = message.encode().getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                config.multicastGroup(),
                config.multicastPort());

        multicastSocket.send(packet);

        log("Sent multicast JOIN_REQUEST txId=" + txId + ", sequence=" + sequence);
    }

    private void receiveLoop() {
        while (running) {
            try {
                byte[] buffer = new byte[config.packetBufferSize()];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                multicastSocket.receive(packet);

                String raw = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8);

                DiscoveryMessage message = DiscoveryMessage.decode(raw);

                if ("JOIN_REQUEST".equals(message.type())) {
                    handleJoinRequest(packet.getAddress(), message);
                }
            } catch (SocketException exception) {
                if (running) {
                    log("Discovery socket error: " + exception.getMessage());
                }
            } catch (Exception exception) {
                log("Discovery receive error: " + exception.getMessage());
            }
        }
    }

    private void handleJoinRequest(
            InetAddress senderAddress,
            DiscoveryMessage request) throws IOException {
        if (request.sender().nodeId().equals(localAddress.nodeId())) {
            return;
        }

        Snapshot snapshot = connectionManager.takeSnapshot();

        DiscoveryMessage ack = new DiscoveryMessage(
                "JOIN_ACK",
                request.txId(),
                request.sequence(),
                localAddress,
                0,
                snapshot.version(),
                snapshot.neighbors(),
                null,
                null);

        byte[] bytes = ack.encode().getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                senderAddress,
                request.replyPort());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(packet);
        }

        log("Sent JOIN_ACK to " + request.sender()
                + " for txId=" + request.txId()
                + " with neighbors=" + snapshot.neighbors());
    }

    @Override
    public void close() {
        running = false;

        if (multicastSocket != null) {
            multicastSocket.close();
        }

        receiverExecutor.shutdownNow();
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now() + "] " + message);
    }
}