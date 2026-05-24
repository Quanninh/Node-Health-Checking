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

    public RecoveryMessage receive(int port, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.receive(packet);

            String raw = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8);

            return RecoveryMessage.decode(raw);
        }
    }
}
