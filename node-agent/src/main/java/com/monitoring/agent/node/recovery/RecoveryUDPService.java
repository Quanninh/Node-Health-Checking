package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final RewiringCoordinator rewiringCoordinator;
    private final Set<String> seenMessages = ConcurrentHashMap.newKeySet();

    public RecoveryUDPService(NodeAddress localAddress, NetworkTopologyCache networkTopologyCache,
            ConnectionManager connectionManager, UdpCoordinator udpCoordinator,
            RewiringCoordinator rewiringCoordinator) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.networkTopologyCache = networkTopologyCache;
        this.udpCoordinator = udpCoordinator;
        this.rewiringCoordinator = rewiringCoordinator;
    }

    /**
     * Registers the recovery message consumer with the UdpCoordinator.
     */
    public void start() {
        udpCoordinator.registerRecoveryConsumer(this::handleRecoveryPacket);
        Console.log("Recovery UDP Service started", Constant.GREEN);
    }

    /**
     * If the current node is SUFFICIENT, returns false.
     * <p>
     * If the current node is DEFICIENT, self gossip then attempt recovery with
     * known deficient nodes.
     * 
     * @param reason
     * @return
     */
    public boolean gossipSelfIfDeficient(String reason) {
        connectionManager.refreshHealthState();

        if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
            Console.log("Bro i'm sufficient ^.^");
            return false;
        }

        String repairEpoch = localAddress.nodeId() + "-" + System.currentTimeMillis();
        networkTopologyCache.markDeficient(new DeficientNodeRecord(
                localAddress,
                connectionManager.size(),
                repairEpoch,
                Instant.now(),
                0));

        Console.log("[RECOVERY] Local node is DEFICIENT cuz " + reason
                + ". Gossiping deficient state.", Constant.PURPLE);
        gossipSelfDeficient(repairEpoch, Constant.DEFAULT_GOSSIP_TTL);
        attemptWithKnownDeficientNodes();
        return true;
    }

    private void attemptWithKnownDeficientNodes() {
        for (DeficientNodeRecord record : networkTopologyCache.getDeficientNodeRecords()) {
            if (!record.nodeId().equals(localAddress.nodeId())) {
                rewiringCoordinator.onDeficientNodeDiscovered(record);
            }
        }
    }

    public void gossipSelfDeficient(String repairEpoch, int ttl) {
        RecoveryMessage message = new RecoveryMessage(
                RecoveryMessageType.DEFICIENT,
                UUID.randomUUID().toString(),
                repairEpoch,
                localAddress,
                localAddress,
                null,
                connectionManager.neighborAddresses(),
                ttl,
                System.currentTimeMillis(),
                0);

        broadcastToNeighbors(message);
    }

    public void broadcastToNeighbors(RecoveryMessage message) {
        for (NodeAddress neighbor : connectionManager.neighborAddresses()) {
            try {
                send(neighbor, message);
                Console.log("Sent DEFICIENT message [" + message.messageId() + "] to " + neighbor + " success",
                        Constant.CYAN);
            } catch (IOException e) {
                Console.log("Failed to send message [" + message.messageId() + "] to " + neighbor + " cuz "
                        + e.getMessage(),
                        Constant.RED);
            }
        }
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
                Console.log("Invalid message " + message.toString());
                return;
            }

            switch (message.type()) {
                case DEFICIENT -> handleDeficient(message);
                case DEFICIENT_ACK -> Console.log("Received DEFICIENT_ACK. Doing nothing with it.");
                default -> {
                }
            }
        } catch (Exception ex) {
            Console.log("Error handling recovery packet: " + ex.getMessage(), Constant.RED);
        }
    }

    /**
     * Handles DEFICIENT message type.
     * 
     * @param message the recovery message
     */
    private void handleDeficient(RecoveryMessage message) {
        if (!seenMessages.add(message.messageId())) {
            Console.log("I'VE PLAYED THESE GAMES BEFORE jk seen message -> ignore");
            return;
        }

        Console.log("Received DEFICIENT. Working on it.", Constant.BG_PINK);

        DeficientNodeRecord record = new DeficientNodeRecord(
                message.subject(),
                message.neighbors().size(),
                message.repairEpoch(),
                Instant.ofEpochMilli(message.timestamp()),
                message.incarnation());

        networkTopologyCache.markDeficient(record);
        rewiringCoordinator.onDeficientNodeDiscovered(record);

        // Send DEFICIENT_ACK
        try {
            send(message.sender(),
                    new RecoveryMessage(RecoveryMessageType.DEFICIENT_ACK, message.messageId(),
                            message.repairEpoch(), localAddress, message.subject(), message.target(),
                            message.neighbors(), Constant.DEFAULT_GOSSIP_TTL,
                            System.currentTimeMillis(), 0));
        } catch (IOException e) {
            Console.log("Failed to send DEFICIENT_ACK to " + message.sender().nodeId() + ": " + e.getMessage(),
                    Constant.RED);
        }

        // Don't gossip if message is about me and I'm healthy
        if (message.ttl() > 0 && !(message.subject() == localAddress
                && connectionManager.getHealthState() == HealthState.SUFFICIENT)) {
            // Gossip to neighbors
            for (NodeAddress neighbor : connectionManager.neighborAddresses()) {
                if (neighbor.nodeId().equals(localAddress.nodeId())
                        || neighbor.nodeId().equals(message.sender().nodeId())) {
                    continue;
                }

                try {
                    send(neighbor, new RecoveryMessage(message.type(), message.messageId(),
                            message.repairEpoch(), localAddress, message.subject(),
                            message.target(), message.neighbors(),
                            Math.max(message.ttl() - 1, 0),
                            System.currentTimeMillis(), message.incarnation()));

                    Console.log(
                            "Gossiping DEFICIENT message [" + message.messageId() + "] to " + neighbor.nodeId()
                                    + " success?",
                            Constant.CYAN + Constant.BOLD);
                } catch (IOException e) {
                    Console.logError("Failed to gossip message [" + message.messageId() + "] to " + neighbor.nodeId()
                            + " because " + e.getMessage());
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
        String encodedMessage = message.encode();
        udpCoordinator.send(localAddress, target, UdpPacketType.RECOVERY, encodedMessage);
    }

    @Override
    public void close() throws Exception {
        // Cleanup is handled by UdpCoordinator
    }

}
