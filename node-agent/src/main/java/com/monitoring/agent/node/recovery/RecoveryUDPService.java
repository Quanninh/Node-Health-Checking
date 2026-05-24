package com.monitoring.agent.node.recovery;

import java.io.IOException;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.node.transport.UdpCoordinator;
import com.monitoring.agent.node.transport.UdpEnvelope;
import com.monitoring.agent.node.transport.UdpPacketType;
import com.monitoring.agent.util.Console;

/**
 * Sends and receives recovery messages using UDP through the UdpCoordinator.
 * 
 * This service registers a consumer callback with the UdpCoordinator and
 * processes
 * incoming recovery packets. It delegates all socket operations to
 * UdpCoordinator.
 */
public class RecoveryUDPService implements AutoCloseable {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final NetworkTopologyCache networkTopologyCache;
    private final UdpCoordinator udpCoordinator;

    public RecoveryUDPService(NodeAddress localAddress, NetworkTopologyCache networkTopologyCache,
            ConnectionManager connectionManager, UdpCoordinator udpCoordinator) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.networkTopologyCache = networkTopologyCache;
        this.udpCoordinator = udpCoordinator;
    }

    /**
     * Registers the recovery message consumer with the UdpCoordinator.
     */
    public void start() {
        udpCoordinator.registerRecoveryConsumer(this::handleRecoveryPacket);
        Console.log("Recovery UDP Service started");
    }

    /**
     * Handles incoming recovery packets from the UdpCoordinator.
     * 
     * @param envelope the UDP envelope containing the recovery message
     */
    private void handleRecoveryPacket(UdpEnvelope envelope) {
        try {
            RecoveryMessage message = RecoveryMessage.decode(envelope.payload());

            if (message.type() == null) {
                return;
            }

            switch (message.type()) {
                case DEFICIENT -> handleDeficient(message);
                case DEFICIENT_ACK -> Console.log("Received DEFICIENT_ACK. Doing nothing with it.");
                default -> throw new AssertionError();
            }
        } catch (Exception ex) {
            System.getLogger(RecoveryUDPService.class.getName())
                    .log(System.Logger.Level.ERROR, "Error handling recovery packet: " + ex.getMessage(), ex);
        }
    }

    /**
     * Handles DEFICIENT message type.
     * 
     * @param message the recovery message
     */
    private void handleDeficient(RecoveryMessage message) {
        Console.log("Received DEFICIENT. Working on it.", Constant.BG_PURPLE);
        networkTopologyCache.markDeficient(message.subject());

        // Send DEFICIENT_ACK
        try {
            send(message.sender(),
                    new RecoveryMessage(RecoveryMessageType.DEFICIENT_ACK, message.messageId(),
                            message.repairEpoch(), localAddress, message.subject(), message.target(),
                            message.neighbors(), Constant.DEFAULT_GOSSIP_TTL,
                            System.currentTimeMillis(), 0));
        } catch (IOException e) {
            Console.log("Failed to send DEFICIENT_ACK to " + message.sender() + ": " + e.getMessage(), Constant.RED);
        }

        if (message.ttl() > 0) {
            // Gossip to neighbors
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
    }

    /**
     * Sends a recovery message to the target node using the UdpCoordinator.
     * 
     * @param target  the target node
     * @param message the recovery message
     * @throws IOException if sending fails
     */
    public void send(NodeAddress target, RecoveryMessage message) throws IOException {
        Console.log("Before sending: " + message + " to " + target.toString(), Constant.BG_GREEN);

        String encodedMessage = message.encode();
        udpCoordinator.send(target.host(), target.port(), UdpPacketType.RECOVERY, encodedMessage);

        Console.log("After sending: " + message + " to " + target.toString(), Constant.BG_GREEN);
    }

    @Override
    public void close() throws Exception {
        // Cleanup is handled by UdpCoordinator
    }

}
