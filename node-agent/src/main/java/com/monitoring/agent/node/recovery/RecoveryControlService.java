package com.monitoring.agent.node.recovery;

import java.util.List;
import java.util.UUID;

import com.monitoring.agent.node.NodeAddress;

public class RecoveryControlService {
    private final NodeAddress localAddress;

    public RecoveryControlService(
            NodeAddress localAddress) {

        this.localAddress = localAddress;
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

    public boolean requestNodeLock(
            NodeAddress target) {

        RecoveryMessage request =
                new RecoveryMessage(
                        RecoveryMessageType.NODE_LOCK,
                        UUID.randomUUID().toString(),
                        "",
                        localAddress,
                        localAddress,
                        target,
                        List.of(),
                        0,
                        System.currentTimeMillis(),
                        0);

        return sendReliableRequest(target, request);
    }

    public void releaseNodeLock(
            NodeAddress target) {

        RecoveryMessage unlock =
                new RecoveryMessage(
                        RecoveryMessageType.NODE_UNLOCK,
                        UUID.randomUUID().toString(),
                        "",
                        localAddress,
                        localAddress,
                        target,
                        List.of(),
                        0,
                        System.currentTimeMillis(),
                        0);

        send(target, unlock);
    }

    public boolean requestDirectRepair(
            NodeAddress target,
            String repairEpoch) {

        RecoveryMessage request =
                new RecoveryMessage(
                        RecoveryMessageType.REPAIR_REQUEST,
                        UUID.randomUUID().toString(),
                        repairEpoch,
                        localAddress,
                        localAddress,
                        target,
                        List.of(),
                        0,
                        System.currentTimeMillis(),
                        0);

        return sendReliableRequest(target, request);
    }

    public void broadcastRepairSuccess(
            NodeAddress a,
            NodeAddress b,
            String repairEpoch) {

        RecoveryMessage success =
                new RecoveryMessage(
                        RecoveryMessageType.REPAIR_SUCCESS,
                        UUID.randomUUID().toString(),
                        repairEpoch,
                        a,
                        a,
                        b,
                        List.of(),
                        1,
                        System.currentTimeMillis(),
                        0);

        broadcast(success);
    }

    private void broadcast(RecoveryMessage message) {
    }

    private void send(
            NodeAddress target,
            RecoveryMessage message) {
    }

    private boolean sendReliableRequest(
            NodeAddress target,
            RecoveryMessage request) {

        return true;
    }
}
