package com.monitoring.agent.node.connection;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.monitoring.agent.node.NodeAddress;

public record DiscoveryMessage(
        String type,
        String txId,
        long sequence,
        NodeAddress sender,
        int replyPort,
        long neighborVersion,
        List<NodeAddress> neighbors,
        String directTargetId,
        String evictedNodeId) {

    public String encode() {
        Map<String, String> values = new LinkedHashMap<>();

        values.put("type", type);
        values.put("txId", txId);
        values.put("sequence", Long.toString(sequence));
        values.put("nodeId", sender.nodeId());
        values.put("host", sender.host());
        values.put("port", Integer.toString(sender.port()));
        values.put("replyPort", Integer.toString(replyPort));
        values.put("neighborVersion", Long.toString(neighborVersion));
        values.put("directTargetId", nullToEmpty(directTargetId));
        values.put("evictedNodeId", nullToEmpty(evictedNodeId));
        values.put("neighbors", encodeNeighbors(neighbors));

        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append("&");
            }

            builder.append(url(entry.getKey()))
                    .append("=")
                    .append(url(entry.getValue()));
        }

        return builder.toString();
    }

    static DiscoveryMessage decode(String raw) {
        Map<String, String> values = new HashMap<>();

        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');

            if (index < 0) {
                continue;
            }

            String key = decodeUrl(pair.substring(0, index));
            String value = decodeUrl(pair.substring(index + 1));
            values.put(key, value);
        }

        NodeAddress sender = new NodeAddress(
                required(values, "nodeId"),
                required(values, "host"),
                Integer.parseInt(required(values, "port")));

        return new DiscoveryMessage(
                required(values, "type"),
                required(values, "txId"),
                Long.parseLong(required(values, "sequence")),
                sender,
                Integer.parseInt(values.getOrDefault("replyPort", "0")),
                Long.parseLong(values.getOrDefault("neighborVersion", "0")),
                decodeNeighbors(values.getOrDefault("neighbors", "")),
                emptyToNull(values.get("directTargetId")),
                emptyToNull(values.get("evictedNodeId")));
    }

    private static String encodeNeighbors(List<NodeAddress> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) {
            return "";
        }

        return neighbors.stream()
                .map(NodeAddress::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static List<NodeAddress> decodeNeighbors(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<NodeAddress> result = new ArrayList<>();

        for (String token : raw.split(",")) {
            if (!token.isBlank()) {
                result.add(NodeAddress.fromString(token));
            }
        }

        return result;
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);

        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }

        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}