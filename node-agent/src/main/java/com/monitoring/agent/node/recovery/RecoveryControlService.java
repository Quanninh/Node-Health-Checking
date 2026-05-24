package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.util.Console;

/**
 * Detects if the local node is deficient and broadcasts that information to
 * the system.
 */
public class RecoveryControlService {

    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final RecoveryUDPService udpService;

    public RecoveryControlService(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            RecoveryUDPService udpService) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.udpService = udpService;
    }

    /**
     * Creates a Recovery Message DEFICIENT, detailing that the current local node
     * is deficient, then broadcasts the message.
     * 
     * @param repairEpoch the repair epoch?
     * @param ttl         time to live
     */
    public void gossipSelfDeficient(String repairEpoch, int ttl) {
        RecoveryMessage message = new RecoveryMessage(
                RecoveryMessageType.DEFICIENT,
                UUID.randomUUID().toString(),
                repairEpoch,
                localAddress,
                localAddress,
                null,
                List.of(),
                ttl,
                System.currentTimeMillis(),
                0);

        broadcast(message);
    }

    /**
     * Broadcasts a recovery message to its neighbors.
     * 
     * @param message the recovery message
     * @see RecoveryUDPService#send(NodeAddress, RecoveryMessage)
     */
    private void broadcast(RecoveryMessage message) {
        for (NodeAddress neighbor : connectionManager.neighborAddresses()) {
            try {
                udpService.send(neighbor, message);
            } catch (IOException e) {
                Console.log(
                        "Failed to send message [" + message + "] to " + neighbor + " because " + e.getMessage(),
                        Constant.RED);
            }
        }
    }
}