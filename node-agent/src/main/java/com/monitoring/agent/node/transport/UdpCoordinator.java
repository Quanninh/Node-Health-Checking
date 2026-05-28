package com.monitoring.agent.node.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

/**
 * Central coordinator for UDP communication.
 * 
 * Manages a single DatagramSocket and dispatches incoming packets to registered
 * consumers
 * based on packet type. Provides a centralized send method for all services.
 */
public class UdpCoordinator implements AutoCloseable {

    private final int port;
    private final int bufferSize;
    private final ExecutorService executorService;
    private final ExecutorService membershipExecutor;
    private final ExecutorService recoveryExecutor;
    private final ExecutorService rewiringExecutor;

    private volatile boolean running;
    private DatagramSocket socket;

    // Consumers for different packet types
    private volatile Consumer<UdpEnvelope> membershipConsumer;
    private volatile Consumer<UdpEnvelope> recoveryConsumer;
    private volatile Consumer<UdpEnvelope> rewiringConsumer;

    public UdpCoordinator(int port, int bufferSize) {
        this.port = port;
        this.bufferSize = bufferSize;
        this.executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "UDP-Coordinator-" + port);
            thread.setDaemon(false);
            Console.log("New UDP-Coordinator thread created.");
            return thread;
        });
        this.membershipExecutor = newSingleProtocolExecutor("UDP-Membership-" + port);
        this.recoveryExecutor = newSingleProtocolExecutor("UDP-Recovery-" + port);
        this.rewiringExecutor = newSingleProtocolExecutor("UDP-Rewiring-" + port);
    }

    private ExecutorService newSingleProtocolExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(false);
            Console.log("New UDP-Coordinator thread created " + threadName);
            return thread;
        });
    }

    /**
     * Starts the UDP coordinator, initializing the socket and receive loop.
     * 
     * @throws SocketException if socket initialization fails
     */
    public void start() throws SocketException {
        socket = new DatagramSocket(port);
        running = true;
        executorService.submit(this::receiveLoop);
        Console.log("UDP Coordinator started on port " + port, Constant.GREEN);
    }

    /**
     * Registers a consumer for MEMBERSHIP packets.
     * 
     * @param consumer the consumer function
     */
    public void registerMembershipConsumer(Consumer<UdpEnvelope> consumer) {
        this.membershipConsumer = consumer;
    }

    /**
     * Registers a consumer for RECOVERY packets.
     * 
     * @param consumer the consumer function
     */
    public void registerRecoveryConsumer(Consumer<UdpEnvelope> consumer) {
        this.recoveryConsumer = consumer;
    }

    /**
     * Registers a consumer for REWIRING packets.
     * 
     * @param consumer the consumer function
     */
    public void registerRewiringConsumer(Consumer<UdpEnvelope> consumer) {
        this.rewiringConsumer = consumer;
    }

    /**
     * Sends a UDP packet to the target address.
     * 
     * @param targetHost the target host
     * @param targetPort the target port
     * @param type       the packet type
     * @param payload    the packet payload
     * @throws IOException if sending fails
     */
    public void send(NodeAddress localAddress, NodeAddress target, UdpPacketType type,
            String payload) throws IOException {
        byte[] bytes = UdpPacket.encode(type, payload);

        Console.log("Sending to " + target.toString() + " type " + type + " payload: " + payload);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                InetAddress.getByName(target.host()),
                target.port());

        Console.logPacket(Constant.NOW(), localAddress.nodeId(), target.nodeId(), type.toString(), payload);

        // Create a temporary socket to send (avoids blocking the main socket)
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            sendSocket.send(packet);
        }
    }

    /**
     * Main receive loop that listens for incoming packets and dispatches to
     * consumers.
     */
    private void receiveLoop() {
        while (running) {
            try {
                byte[] buffer = new byte[bufferSize];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);

                // Decode the UDP envelope
                UdpEnvelope envelope = UdpPacket.decode(packet.getData(), packet.getOffset(), packet.getLength());

                submit(envelope);
            } catch (SocketException ex) {
                if (running) {
                    Console.log("UDP Socket error: " + ex.getMessage(), Constant.RED);
                }
            } catch (IOException ex) {
                if (running) {
                    Console.log("UDP receive error: " + ex.getMessage(), Constant.RED);
                }
            } catch (Exception ex) {
                if (running) {
                    Console.log("Unexpected error in UDP receive loop: " + ex.getMessage(), Constant.RED);
                }
            }
        }
    }

    private void submit(UdpEnvelope envelope) {
        Console.log("RECEIVED: " + envelope.payload());
        if (envelope.istype(UdpPacketType.MEMBERSHIP)) {
            membershipExecutor.submit(() -> dispatch(envelope, membershipConsumer));
        } else if (envelope.istype(UdpPacketType.RECOVERY)) {
            recoveryExecutor.submit(() -> dispatch(envelope, recoveryConsumer));
        } else if (envelope.istype(UdpPacketType.REWIRING)) {
            rewiringExecutor.submit(() -> dispatch(envelope, rewiringConsumer));
        }
    }

    private void dispatch(UdpEnvelope envelope, Consumer<UdpEnvelope> consumer) {
        try {
            if (consumer != null) {
                consumer.accept(envelope);
            }
        } catch (Exception ex) {
            Console.log("UDP dispatch error: " + ex.getMessage(), Constant.RED);
        }
    }

    @Override
    public void close() throws Exception {
        running = false;

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        executorService.shutdownNow();
        membershipExecutor.shutdownNow();
        recoveryExecutor.shutdownNow();
        rewiringExecutor.shutdownNow();
    }
}
