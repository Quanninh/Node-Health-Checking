package com.monitoring.agent.node.recovery;

import java.util.List;
import java.util.UUID;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.node.connection.ConnectionManager;

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

    public void gossipDeficientNode(
            String repairEpoch,
            int ttl) {

        RecoveryMessage message =
                new RecoveryMessage(
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

    public void broadcast(RecoveryMessage message) {

        for (NodeAddress neighbor : connectionManager.neighborAddresses()) {
            try {
                udpService.send(neighbor, message);
            } catch (Exception ignored) {
            }
        }
    }
}