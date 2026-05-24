package com.monitoring.agent.node.transport;

import java.nio.charset.StandardCharsets;

public final class UdpPacket {

    private UdpPacket() {
    }

    public static byte[] encode(UdpPacketType type, String payload) {
        return UdpEnvelope.wrap(type, payload)
                .encode()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static UdpEnvelope decode(byte[] data, int offset, int length) {
        String raw = new String(data, offset, length, StandardCharsets.UTF_8);
        return UdpEnvelope.decode(raw);
    }
}