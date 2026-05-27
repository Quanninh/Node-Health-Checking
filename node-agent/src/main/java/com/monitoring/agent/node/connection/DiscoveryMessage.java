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

/**
 * Represents a discovery message.
 *
 * <p>
 * Data flow:
 *
 * <ol>
 * <li>Joining node sends broadcast JOIN_REQUEST to all nodes in the
 * network.</li>
 * <li>Nodes in the network send back JOIN_ACK to the joining node.</li>
 * <li>Joining node sends COMMIT to chosen future neighbors.</li>
 * <li>Nodes receiving COMMIT establish the connection and send COMMIT_ACK.</li>
 * </ol>
 *
 * @param directTargetId for COMMIT_ACK, this is a boolean
 */
public record DiscoveryMessage(
        DiscoveryMessageType type,
        String transactionId,
        long sequence,
        NodeAddress sender,
        boolean isInNetwork,
        int replyPort,
        long neighborVersion,
        List<NodeAddress> neighbors,
        String directTargetId,
        String evictedNodeId) {

    private static final String FIELD_SEPARATOR = "&";
    private static final String KEY_VALUE_PAIR_SEPARATOR = "=";
    private static final String NEIGHBOR_LIST_SEPARATOR = ",";

    /**
     * Encodes the message into a string.
     * 
     * @return the encoded message
     */
    public String encode() {
        Map<String, String> values = new LinkedHashMap<>();

        values.put("type", type.toString());
        values.put("txId", transactionId);
        values.put("sequence", Long.toString(sequence));
        values.put("nodeId", sender.nodeId());
        values.put("host", sender.host());
        values.put("port", Integer.toString(sender.port()));
        values.put("isInNetwork", String.valueOf(isInNetwork));
        values.put("replyPort", Integer.toString(replyPort));
        values.put("neighborVersion", Long.toString(neighborVersion));
        values.put("directTargetId", nullToEmpty(directTargetId));
        values.put("evictedNodeId", nullToEmpty(evictedNodeId));
        values.put("neighbors", encodeNeighbors(neighbors));

        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            // Only put the FIELD_SEPARATOR between entries, not before the first one.
            if (!builder.isEmpty()) {
                builder.append(FIELD_SEPARATOR);
            }

            builder.append(url(entry.getKey()))
                    .append(KEY_VALUE_PAIR_SEPARATOR)
                    .append(url(entry.getValue()));
        }

        return builder.toString();
    }

    /**
     * Decodes a message.
     * 
     * @param raw the raw message
     * @return the discovery message
     */
    public static DiscoveryMessage decode(String raw) {
        Map<String, String> values = new HashMap<>();

        for (String pair : raw.split(FIELD_SEPARATOR)) {
            int index = pair.indexOf(KEY_VALUE_PAIR_SEPARATOR);

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
                DiscoveryMessageType.valueOf(required(values, "type")),
                required(values, "txId"),
                Long.parseLong(required(values, "sequence")),
                sender,
                Boolean.parseBoolean(required(values, "isInNetwork")),
                Integer.parseInt(values.getOrDefault("replyPort", "0")),
                Long.parseLong(values.getOrDefault("neighborVersion", "0")),
                decodeNeighbors(values.getOrDefault("neighbors", "")),
                emptyToNull(values.get("directTargetId")),
                emptyToNull(values.get("evictedNodeId")));
    }

    /**
     * Encodes a list of neighbors into a string.
     * 
     * @param neighbors the list of neighbors
     * @return the encoded string
     */
    private static String encodeNeighbors(List<NodeAddress> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) {
            return "";
        }

        return neighbors.stream()
                .map(NodeAddress::toString)
                .reduce((a, b) -> a + NEIGHBOR_LIST_SEPARATOR + b)
                .orElse("");
    }

    /**
     * Decodes a string into a list of neighbors.
     * 
     * @param raw the string
     * @return the list of neighbors
     */
    private static List<NodeAddress> decodeNeighbors(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<NodeAddress> result = new ArrayList<>();

        for (String token : raw.split(NEIGHBOR_LIST_SEPARATOR)) {
            if (!token.isBlank()) {
                result.add(NodeAddress.fromString(token));
            }
        }

        return result;
    }

    /**
     * Encode a string into URL format, i.e. no special characters.
     * 
     * @param value the normal string
     * @return the URL-formatted string
     */
    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Decode a string from URL format, i.e. no special characters, into normal
     * strings.
     * 
     * @param value the string in URL format
     * @return the normal string
     */
    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Makes sure that the required key-value pair actually exists in the map.
     * 
     * @param values the map
     * @param key    the key
     * @return the value if it exists
     * @throws IllegalArgmentException if the key-value pair doesn't exist in the
     *                                 map
     */
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);

        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }

        return value;
    }

    /**
     * Considers null as empty strings.
     * 
     * @param value the string
     * @return an empty string if the string is empty, otherwise the original string
     *         is returned
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Consider empty strings as null.
     * 
     * @param value the string
     * @return null if the string is empty or null, otherwise the original string is
     *         returned
     */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public String toString() {
        return "DiscoveryMessage[" +
                "type=" + type +
                ", txId=" + transactionId +
                ", seq=" + sequence +
                ", sender=" + sender.nodeId() +
                ", isInNetwork=" + isInNetwork +
                ", neighbors=" + neighbors +
                ", directTargetId=" + directTargetId +
                ", evictedNodeId=" + evictedNodeId +
                ']';
    }

}