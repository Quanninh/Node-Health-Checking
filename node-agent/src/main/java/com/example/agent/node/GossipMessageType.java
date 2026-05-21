package com.example.agent.node;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum GossipMessageType {
    SUSPECT,
    UNREACHABLE,
    ALIVE,
    JOIN,
    LEAVE
}

record GossipMessage(
        String messageId,
        String sourceNodeId,
        String subjectNodeId,
        GossipMessageType messageType,
        int incarnationNumber,
        long timestamp,
        int ttl,
        String details) {

    String toJson() {
        return """
                {
                  \"messageId\": \"%s\",
                  \"sourceNodeId\": \"%s\",
                  \"subjectNodeId\": \"%s\",
                  \"messageType\": \"%s\",
                  \"incarnationNumber\": %d,
                  \"timestamp\": %d,
                  \"ttl\": %d,
                  \"details\": \"%s\"
                }
                """.formatted(
                escapeJson(messageId),
                escapeJson(sourceNodeId),
                escapeJson(subjectNodeId),
                messageType,
                incarnationNumber,
                timestamp,
                ttl,
                escapeJson(details));
    }

    GossipMessage decrementTtl() {
        return new GossipMessage(
                messageId,
                sourceNodeId,
                subjectNodeId,
                messageType,
                incarnationNumber,
                timestamp,
                ttl - 1,
                details);
    }

    static GossipMessage fromJson(String json) {
        return new GossipMessage(
                extractString(json, "messageId"),
                extractString(json, "sourceNodeId"),
                extractString(json, "subjectNodeId"),
                GossipMessageType.valueOf(extractString(json, "messageType")),
                Integer.parseInt(extractNumber(json, "incarnationNumber")),
                Long.parseLong(extractNumber(json, "timestamp")),
                Integer.parseInt(extractNumber(json, "ttl")),
                extractString(json, "details"));
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractString(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + fieldName + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
        }

        throw new IllegalArgumentException("Missing string field in gossip JSON: " + fieldName);
    }

    private static String extractNumber(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + fieldName + "\\\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalArgumentException("Missing number field in gossip JSON: " + fieldName);
    }
}