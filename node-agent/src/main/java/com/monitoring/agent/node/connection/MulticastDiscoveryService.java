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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.DiscoveryConfig;
import com.monitoring.agent.node.JoinAck;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

/**
 * Uses multicast to send out join requests to nodes, and received
 * acknowledgements from nodes in network.
 */
public final class MulticastDiscoveryService implements AutoCloseable {

    private final NodeAddress localAddress;
    private final DiscoveryConfig config;
    private final ConnectionManager connectionManager;

    private final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> seenAcks = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private MulticastSocket multicastSocket;

    public MulticastDiscoveryService(NodeAddress localAddress, DiscoveryConfig config,
            ConnectionManager connectionManager) {
        this.localAddress = localAddress;
        this.config = config;
        this.connectionManager = connectionManager;
    }

    /**
     * Starts multicast network joining process.
     * 
     * @throws IOException
     */
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

        Console.log("Joined multicast discovery group " + config.multicastGroup().getHostAddress() + ":"
                + config.multicastPort() + " on interface " + config.networkInterface().getName(), Constant.YELLOW);
    }

    /**
     * Sends out join requests and received the ACKs.
     * 
     * @return list of JOIN_ACKs
     * @throws IOException
     */
    @SuppressWarnings("SleepWhileInLoop")
    public List<JoinAck> discoverPeers() throws IOException {
        String txId = UUID.randomUUID().toString();

        List<JoinAck> allAcksReceived = new ArrayList<>();

        try (DatagramSocket replySocket = new DatagramSocket(0)) {
            replySocket.setSoTimeout((int) config.retryInterval().toMillis());

            int replyPort = replySocket.getLocalPort();

            for (int attempt = 1; attempt <= config.retryCount(); attempt++) {
                sendJoinRequest(txId, attempt, replyPort);

                List<JoinAck> collected = collectReplies(replySocket, txId, config.retryInterval());
                allAcksReceived.addAll(collected);

                int randomBackoffTime = ThreadLocalRandom.current().nextInt(1000, 5001);
                Thread.sleep(randomBackoffTime);
            }

            return allAcksReceived;
        } catch (InterruptedException e) {
            Console.log("Thred sleep interrupted", Constant.RED);
        }

        return new ArrayList<>();
    }

    /**
     * Collects replies from the socket until the timing window ends or the socket
     * timed out.
     * 
     * @param replySocket the datagram socket
     * @param txId        transaction id
     * @param window      timing window
     * @return list of replies (ACKs)
     * @see JoinAck
     */
    private List<JoinAck> collectReplies(DatagramSocket replySocket, String txId, Duration window) {
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

                if (message.type() != DiscoveryMessageType.JOIN_ACK) {
                    Console.log("Not JOIN_ACK received, discarded.", Constant.PURPLE);
                    continue;
                }

                if (!txId.equals(message.transactionId())) {
                    Console.log("Wrong txId received, discarded.", Constant.PURPLE);
                    continue;
                }

                if (message.sender().nodeId().equals(localAddress.nodeId())) {
                    Console.log("Sender = Myself, discarded.", Constant.PURPLE);
                    continue;
                }

                String key = message.transactionId() + ":" + message.sender().nodeId();

                // `add` returns false if key already exist in set
                if (!seenAcks.add(key)) {
                    Console.log("Duplicate KEY received, discarded.", Constant.PURPLE);
                    continue;
                }

                JoinAck ack = new JoinAck(message.transactionId(), message.sender(), message.isInNetwork(),
                        message.neighborVersion(), message.neighbors());

                repliesByNodeId.putIfAbsent(ack.responder().nodeId(), ack);

                Console.log("Received JOIN_ACK from " + ack.responder()
                        + " with neighbors=" + ack.responderNeighbors());
            } catch (SocketTimeoutException ignored) {
                Console.log("Socket timeout.", Constant.BLUE);
                break;
            } catch (IOException exception) {
                Console.log("Ignored invalid discovery reply: " + exception.getMessage(), Constant.RED);
            }
        }

        return new ArrayList<>(repliesByNodeId.values());
    }

    /**
     * Send JOIN_REQUEST multicast message to the port.
     * 
     * @param txId      transaction ID
     * @param sequence  sequence ID
     * @param replyPort
     * @throws IOException
     */
    private void sendJoinRequest(String txId, long sequence, int replyPort) throws IOException {
        DiscoveryMessage message = new DiscoveryMessage(
                DiscoveryMessageType.JOIN_REQUEST,
                txId,
                sequence,
                localAddress,
                false,
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

        Console.log("Sent multicast JOIN_REQUEST txId=" + txId + ", sequence=" + sequence,
                Constant.CYAN);
    }

    /**
     * Continuously receiving packets for discovery message.
     */
    private void receiveLoop() {
        while (running) {
            try {
                byte[] buffer = new byte[config.packetBufferSize()];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                multicastSocket.receive(packet); // receive here on a different port compared to MembershipControl and
                                                 // Recovery

                String raw = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8);

                // Console.log("Received by Multicast Discovery Service. Raw message: " + raw,
                // Constant.BG_PURPLE);

                DiscoveryMessage message = DiscoveryMessage.decode(raw);

                if (message.type() == DiscoveryMessageType.JOIN_REQUEST) {
                    handleJoinRequest(packet.getAddress(), message);
                }
            } catch (SocketException exception) {
                if (running) {
                    Console.log("Discovery socket error: " + exception.getMessage());
                }
            } catch (IOException exception) {
                Console.log("Discovery receive error: " + exception.getMessage());
            }
        }
    }

    /**
     * Responds to a join request with a JOIN_ACK.
     * 
     * @param senderAddress
     * @param request       the discovery message (join request)
     * @throws IOException
     */
    private void handleJoinRequest(InetAddress senderAddress, DiscoveryMessage request) throws IOException {
        if (request.sender().nodeId().equals(localAddress.nodeId())) {
            return;
        }

        Snapshot snapshot = connectionManager.takeSnapshot();

        DiscoveryMessage joinAck = new DiscoveryMessage(
                DiscoveryMessageType.JOIN_ACK,
                request.transactionId(),
                request.sequence(),
                localAddress,
                connectionManager.isInNetwork(),
                0,
                snapshot.version(),
                snapshot.neighbors(),
                null,
                null);

        byte[] bytes = joinAck.encode().getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                senderAddress,
                request.replyPort());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(packet);
        }

        Console.log("Sent JOIN_ACK to " + request.sender() + " for txId=" + request.transactionId() + " with neighbors="
                + snapshot.neighbors());
    }

    @Override
    public void close() {
        running = false;

        if (multicastSocket != null) {
            multicastSocket.close();
        }

        receiverExecutor.shutdownNow();
    }

}