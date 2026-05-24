package com.monitoring.agent.node.recovery;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.monitoring.agent.node.NodeAddress;

public record RecoveryMessage(
        RecoveryMessageType type,
        String messageId,
        String repairEpoch,
        NodeAddress sender,
        NodeAddress subject,
        NodeAddress target,
        List<NodeAddress> neighbors,
        int ttl,
        long timestamp,
        int incarnation
) {
        private static final String FIELD_SEPARATOR = "&";
    private static final String KEY_VALUE_SEPARATOR = "=";

    public String encode() {
        Map<String, String> values = new LinkedHashMap<>();

        values.put("type", type.name());
        values.put("messageId", messageId);
        values.put("repairEpoch", repairEpoch);
        values.put("sender", sender.toString());
        values.put("subject", subject == null ? "" : subject.toString());
        values.put("target", target == null ? "" : target.toString());
        values.put("ttl", Integer.toString(ttl));
        values.put("timestamp", Long.toString(timestamp));
        values.put("incarnation", Integer.toString(incarnation));

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

    public static RecoveryMessage decode(String raw) {
        Map<String, String> values = new HashMap<>();

        for (String pair : raw.split(FIELD_SEPARATOR)) {
            int index = pair.indexOf(KEY_VALUE_SEPARATOR);

            if (index < 0) {
                continue;
            }

            String key = decodeUrl(pair.substring(0, index));
            String value = decodeUrl(pair.substring(index + 1));

            values.put(key, value);
        }

        return new RecoveryMessage(
                RecoveryMessageType.valueOf(values.get("type")),
                values.get("messageId"),
                values.getOrDefault("repairEpoch", ""),
                NodeAddress.fromString(values.get("sender")),
                values.get("subject").isBlank() ? null : NodeAddress.fromString(values.get("subject")),
                values.get("target").isBlank() ? null : NodeAddress.fromString(values.get("target")),
                List.of(),
                Integer.parseInt(values.getOrDefault("ttl", "0")),
                Long.parseLong(values.getOrDefault("timestamp", "0")),
                Integer.parseInt(values.getOrDefault("incarnation", "0")));
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

}