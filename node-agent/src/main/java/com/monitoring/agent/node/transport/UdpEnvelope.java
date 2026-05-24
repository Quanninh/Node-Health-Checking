package com.monitoring.agent.node.transport;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record UdpEnvelope(
        String protocol,
        int version,
        UdpPacketType type,
        String payload) {
    
    // Verify if this packet belongs to the project
    private static final String PROTOCOL = "NODE HEALTH CHECKING";
    private static final int VERSION = 1;

    private static final String FIELD_SEPARATOR = "&";
    private static final String KEY_VALUE_SEPARATOR = "=";

    public static UdpEnvelope wrap(UdpPacketType type, String payload) {
        return new UdpEnvelope(
                PROTOCOL,
                VERSION,
                type,
                payload);
    }

    public String encode() {
        Map<String, String> values = new LinkedHashMap<>();

        values.put("protocol", protocol);
        values.put("version", Integer.toString(version));
        values.put("type", type.name());
        values.put("payload", payload);

        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(FIELD_SEPARATOR);
            }

            builder.append(url(entry.getKey()))
                    .append(KEY_VALUE_SEPARATOR)
                    .append(url(entry.getValue()));
        }

        return builder.toString();
    }

    public static UdpEnvelope decode(String raw) {
        Map<String, String> values = new LinkedHashMap<>();

        for (String pair : raw.split(FIELD_SEPARATOR)) {
            int index = pair.indexOf(KEY_VALUE_SEPARATOR);

            if (index < 0) {
                continue;
            }

            String key = decodeUrl(pair.substring(0, index));
            String value = decodeUrl(pair.substring(index + 1));

            values.put(key, value);
        }

        String protocol = required(values, "protocol");

        if (!PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("Unknown UDP protocol: " + protocol);
        }

        int version = Integer.parseInt(required(values, "version"));

        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported UDP protocol version: " + version);
        }

        return new UdpEnvelope(
                protocol,
                version,
                UdpPacketType.valueOf(required(values, "type")),
                required(values, "payload"));
    }

    public boolean istype(UdpPacketType expectedtype) {
        return type == expectedtype;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);

        if (value == null) {
            throw new IllegalArgumentException("Missing UDP envelope field: " + key);
        }

        return value;
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}