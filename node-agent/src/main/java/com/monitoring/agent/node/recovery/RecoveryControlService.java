package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.time.Instant;
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
    private final NetworkTopologyCache topologyCache;
    private final RecoveryUDPService udpService;
    private final RewiringCoordinator rewiringCoordinator;

    public RecoveryControlService(
            NodeAddress localAddress,
            ConnectionManager connectionManager,
            NetworkTopologyCache topologyCache,
            RecoveryUDPService udpService,
            RewiringCoordinator rewiringCoordinator) {
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;
        this.topologyCache = topologyCache;
        this.udpService = udpService;
        this.rewiringCoordinator = rewiringCoordinator;
    }

    public boolean gossipSelfIfDeficient(String reason) {
        if (connectionManager.getHealthState() != HealthState.DEFICIENT) {
            return false;
        }

        String repairEpoch = localAddress.nodeId() + "-" + System.currentTimeMillis();
        topologyCache.markDeficient(new DeficientNodeRecord(
                localAddress,
                connectionManager.size(),
                repairEpoch,
                Instant.now(),
                0));

        Console.log("[RECOVERY] Local node is DEFICIENT after " + reason
                + ". Gossiping deficient state.", Constant.BG_YELLOW);
        gossipSelfDeficient(repairEpoch, Constant.DEFAULT_GOSSIP_TTL);
        attemptWithKnownDeficientNodes();
        return true;
    }

    private void attemptWithKnownDeficientNodes() {
        for (DeficientNodeRecord record : topologyCache.getDeficientNodeRecords()) {
            if (!record.nodeId().equals(localAddress.nodeId())) {
                rewiringCoordinator.onDeficientNodeDiscovered(record);
            }
        }
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
                connectionManager.neighborAddresses(),
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
    public void broadcast(RecoveryMessage message) {
        for (NodeAddress neighbor : connectionManager.neighborAddresses()) {
            try {
                udpService.send(neighbor, message);
                Console.log("Sent DEFICIENT message [" + message + "] to " + neighbor + " success",
                        Constant.BG_CYAN + Constant.BOLD);
            } catch (IOException e) {
                Console.log(
                        "Failed to send message [" + message + "] to " + neighbor + " because " + e.getMessage(),
                        Constant.RED);
            }
        }
    }
}
