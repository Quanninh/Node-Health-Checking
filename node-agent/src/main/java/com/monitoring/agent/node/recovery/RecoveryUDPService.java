package com.monitoring.agent.node.recovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import com.monitoring.agent.node.NodeAddress;

public class RecoveryUDPService {

    private final int bufferSize;

    public RecoveryUDPService(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public void send(
            NodeAddress target,
            RecoveryMessage message)
            throws IOException {

        byte[] bytes =
                message.encode()
                        .getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet =
                new DatagramPacket(
                        bytes,
                        bytes.length,
                        InetAddress.getByName(target.host()),
                        target.port());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(packet);
        }
    }
}
