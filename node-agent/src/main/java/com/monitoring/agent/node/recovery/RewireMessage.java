package com.monitoring.agent.node.recovery;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.NodeAddress;

/**
 * A rewire message.
 * 
 * @param type            the recovery message type
 * @param messageId       the message ID
 * @param recoveryId      the recovery ID
 * @param sender          the sender node
 * @param defA            the deficient node A
 * @param defB            the deficient node B
 * @param nodeC           the node C neighbor of A
 * @param nodeD           the node D neighbor of C
 * @param connectsTo      the node that the receiver should connect to
 * @param disconnectsFrom the node that the receiver should disconnect from
 * @param defANeighbors   neighbors of A
 * @param defBNeighbors   neighbors of B
 * @param status          the rewire status
 * @param timestamp       the timestamp (ms)
 */
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

    /**
     * Returns a rewire message with parameters.
     * 
     * @param type            message type
     * @param recoveryId      recovery ID
     * @param sender          the sender node
     * @param defA            the deficient node A
     * @param defB            the deficient node B
     * @param nodeC           the node C neighbor of A
     * @param nodeD           the node D neighbor of C
     * @param connectsTo      the node that the receiver should connect to
     * @param disconnectsFrom the node that the receiver should disconnect from
     * @param defANeighbors   neighbors of A
     * @param defBNeighbors   neighbors of B
     * @param status          the rewire status
     * @return the rewire message
     */
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

        return new RewireMessage(type, UUID.randomUUID().toString(), recoveryId, sender, defA, defB, nodeC, nodeD,
                connectsTo, disconnectsFrom, safeList(defANeighbors), safeList(defBNeighbors), status,
                System.currentTimeMillis());
    }

    /**
     * Encodes the rewire message into a string.
     * 
     * @return the encoded message
     */
    public String encode() {
        return "type=" + url(type.name())
                + Constant.FIELD_SEPARATOR + "messageId=" + url(messageId)
                + Constant.FIELD_SEPARATOR + "recoveryId=" + url(recoveryId)
                + Constant.FIELD_SEPARATOR + "sender=" + url(addressToString(sender))
                + Constant.FIELD_SEPARATOR + "defA=" + url(addressToString(defA))
                + Constant.FIELD_SEPARATOR + "defB=" + url(addressToString(defB))
                + Constant.FIELD_SEPARATOR + "nodeC=" + url(addressToString(nodeC))
                + Constant.FIELD_SEPARATOR + "nodeD=" + url(addressToString(nodeD))
                + Constant.FIELD_SEPARATOR + "connectsTo=" + url(addressToString(connectsTo))
                + Constant.FIELD_SEPARATOR + "disconnectsFrom=" + url(addressToString(disconnectsFrom))
                + Constant.FIELD_SEPARATOR + "defANeighbors=" + url(encodeList(defANeighbors))
                + Constant.FIELD_SEPARATOR + "defBNeighbors=" + url(encodeList(defBNeighbors))
                + Constant.FIELD_SEPARATOR + "status=" + url(status == null ? "" : status.name())
                + Constant.FIELD_SEPARATOR + "timestamp=" + timestamp;
    }

    /**
     * Decodes the string into a rewire message.
     * 
     * @param raw the raw string
     * @return the rewire message
     */
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

    /**
     * Gets the value of the key from the raw message.
     * 
     * @param raw the raw message
     * @param key the key
     * @return the value
     */
    private static String value(String raw, String key) {
        for (String pair : raw.split(Constant.FIELD_SEPARATOR)) {
            int index = pair.indexOf(Constant.KEY_VALUE_SEPARATOR);
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

    /**
     * Turns a string into a rewire status.
     * 
     * @param raw the string
     * @return the rewire status
     */
    private static RewireStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return RewireStatus.valueOf(raw);
    }

    /**
     * Turns a string into a node address.
     * 
     * @param raw the string
     * @return the node address
     */
    private static NodeAddress parseAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return NodeAddress.fromString(raw);
    }

    /**
     * Turns a node address into a string.
     * 
     * @param address the node address
     * @return the string
     */
    private static String addressToString(NodeAddress address) {
        return address == null ? "" : address.toString();
    }

    /**
     * Encodes a list of node addresses into raw string.
     * 
     * @param addresses the list of node addresses
     * @return the raw string
     */
    private static String encodeList(List<NodeAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return "";
        }

        return addresses.stream()
                .map(NodeAddress::toString)
                .reduce((a, b) -> a + Constant.LIST_SEPARATOR + b)
                .orElse("");
    }

    /**
     * Decodes the list of node addresses from the raw string.
     * 
     * @param raw the raw string
     * @return the list of node addresses
     */
    private static List<NodeAddress> decodeList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<NodeAddress> result = new ArrayList<>();

        for (String token : raw.split(Constant.LIST_SEPARATOR)) {
            if (!token.isBlank()) {
                result.add(NodeAddress.fromString(token));
            }
        }

        return result;
    }

    /**
     * Returns a list safely.
     * 
     * @param input the list of node addresses
     * @return the safe list of node addresses
     */
    private static List<NodeAddress> safeList(List<NodeAddress> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    /**
     * URL format.
     * 
     * @param value
     * @return
     */
    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Decode URL format.
     * 
     * @param value
     * @return
     */
    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}