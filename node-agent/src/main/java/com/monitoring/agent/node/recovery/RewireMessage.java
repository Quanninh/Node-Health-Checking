package com.monitoring.agent.node.recovery;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.monitoring.agent.node.NodeAddress;

public record RewireMessage(
        RecoveryMessageType type,
        String messageId,
        String recoveryId,
        NodeAddress sender,
        NodeAddress defA,
        NodeAddress defB,
        NodeAddress nodeC,
        NodeAddress nodeD,
        NodeAddress connectsTo,
        NodeAddress disconnectsFrom,
        List<NodeAddress> defANeighbors,
        List<NodeAddress> defBNeighbors,
        RewireStatus status,
        long timestamp) {

    private static final String FIELD_SEPARATOR = "&";
    private static final String KEY_VALUE_SEPARATOR = "=";
    private static final String LIST_SEPARATOR = ",";

    public static RewireMessage of(
            RecoveryMessageType type,
            String recoveryId,
            NodeAddress sender,
            NodeAddress defA,
            NodeAddress defB,
            NodeAddress nodeC,
            NodeAddress nodeD,
            NodeAddress connectsTo,
            NodeAddress disconnectsFrom,
            List<NodeAddress> defANeighbors,
            List<NodeAddress> defBNeighbors,
            RewireStatus status) {

        return new RewireMessage(
                type,
                UUID.randomUUID().toString(),
                recoveryId,
                sender,
                defA,
                defB,
                nodeC,
                nodeD,
                connectsTo,
                disconnectsFrom,
                safeList(defANeighbors),
                safeList(defBNeighbors),
                status,
                System.currentTimeMillis());
    }

    public String encode() {
        return "type=" + url(type.name())
                + FIELD_SEPARATOR + "messageId=" + url(messageId)
                + FIELD_SEPARATOR + "recoveryId=" + url(recoveryId)
                + FIELD_SEPARATOR + "sender=" + url(addressToString(sender))
                + FIELD_SEPARATOR + "defA=" + url(addressToString(defA))
                + FIELD_SEPARATOR + "defB=" + url(addressToString(defB))
                + FIELD_SEPARATOR + "nodeC=" + url(addressToString(nodeC))
                + FIELD_SEPARATOR + "nodeD=" + url(addressToString(nodeD))
                + FIELD_SEPARATOR + "connectsTo=" + url(addressToString(connectsTo))
                + FIELD_SEPARATOR + "disconnectsFrom=" + url(addressToString(disconnectsFrom))
                + FIELD_SEPARATOR + "defANeighbors=" + url(encodeList(defANeighbors))
                + FIELD_SEPARATOR + "defBNeighbors=" + url(encodeList(defBNeighbors))
                + FIELD_SEPARATOR + "status=" + url(status == null ? "" : status.name())
                + FIELD_SEPARATOR + "timestamp=" + timestamp;
    }

    public static RewireMessage decode(String raw) {
        String type = value(raw, "type");

        return new RewireMessage(
                RecoveryMessageType.valueOf(type),
                value(raw, "messageId"),
                value(raw, "recoveryId"),
                parseAddress(value(raw, "sender")),
                parseAddress(value(raw, "defA")),
                parseAddress(value(raw, "defB")),
                parseAddress(value(raw, "nodeC")),
                parseAddress(value(raw, "nodeD")),
                parseAddress(value(raw, "connectsTo")),
                parseAddress(value(raw, "disconnectsFrom")),
                decodeList(value(raw, "defANeighbors")),
                decodeList(value(raw, "defBNeighbors")),
                parseStatus(value(raw, "status")),
                Long.parseLong(value(raw, "timestamp")));
    }

    private static String value(String raw, String key) {
        for (String pair : raw.split(FIELD_SEPARATOR)) {
            int index = pair.indexOf(KEY_VALUE_SEPARATOR);
            if (index < 0) {
                continue;
            }

            String k = decodeUrl(pair.substring(0, index));
            String v = decodeUrl(pair.substring(index + 1));

            if (key.equals(k)) {
                return v;
            }
        }

        return "";
    }

    private static RewireStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return RewireStatus.valueOf(raw);
    }

    private static NodeAddress parseAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return NodeAddress.fromString(raw);
    }

    private static String addressToString(NodeAddress address) {
        return address == null ? "" : address.toString();
    }

    private static String encodeList(List<NodeAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return "";
        }

        return addresses.stream()
                .map(NodeAddress::toString)
                .reduce((a, b) -> a + LIST_SEPARATOR + b)
                .orElse("");
    }

    private static List<NodeAddress> decodeList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<NodeAddress> result = new ArrayList<>();

        for (String token : raw.split(LIST_SEPARATOR)) {
            if (!token.isBlank()) {
                result.add(NodeAddress.fromString(token));
            }
        }

        return result;
    }

    private static List<NodeAddress> safeList(List<NodeAddress> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}