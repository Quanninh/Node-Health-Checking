package com.monitoring.agent.node;

import java.util.Locale;

import com.monitoring.agent.constant.Constant;

/**
 * Represents one failure detection event.
 *
 * Important:
 * This does NOT mean "the whole system agrees this node is dead".
 * It only means one reporter node classified another node as unreachable.
 *
 * Phase 1:
 * - stored locally by the detecting node.
 *
 * Phase 2:
 * - sent to neighbors and stored by receiving nodes.
 */
@Deprecated
public record FailureEvent(
        String eventId,
        String reporterNodeId,
        String failedNodeId,
        String failedHost,
        int failedPort,
        String status,
        double phi,
        double threshold,
        String timestamp,
        String message,
        int ttl) {

    private static final int DEFAULT_TTL = 3;

    static FailureEvent unreachable(
            String reporterNodeId,
            NodeAddress failedPeer,
            double phi,
            double threshold) {

        String now = Constant.NOW();

        String eventId = reporterNodeId
                + "->"
                + failedPeer.nodeId()
                + "@"
                + now;

        // Use Locale.US so decimal numbers use "." instead of ",".
        // JSON numeric values require dot-style decimals.
        String message = String.format(
                Locale.US,
                "Node %s classifies Node %s as UNREACHABLE. phi=%.4f, threshold=%.4f",
                reporterNodeId,
                failedPeer.nodeId(),
                phi,
                threshold);

        return new FailureEvent(
                eventId,
                reporterNodeId,
                failedPeer.nodeId(),
                failedPeer.host(),
                failedPeer.port(),
                NodeStatus.UNREACHABLE.name(),
                phi,
                threshold,
                now,
                message,
                DEFAULT_TTL);
    }

    /**
     * Creates a copy of the same event with TTL reduced by 1.
     *
     * This is used when forwarding an event to neighbors.
     * TTL prevents infinite broadcast loops.
     */
    FailureEvent decreaseTtl() {
        return new FailureEvent(
                eventId,
                reporterNodeId,
                failedNodeId,
                failedHost,
                failedPort,
                status,
                phi,
                threshold,
                timestamp,
                message,
                Math.max(0, ttl - 1));
    }

    /**
     * Parse a failure event received from another node.
     *
     * This keeps the project dependency-free for now.
     * We are still using the small P2pJson helper instead of adding Jackson.
     */
    static FailureEvent fromJson(String json) {
        String eventId = P2pJson.stringValue(json, "eventId");
        String reporterNodeId = P2pJson.stringValue(json, "reporterNodeId");
        String failedNodeId = P2pJson.stringValue(json, "failedNodeId");
        String failedHost = P2pJson.stringValue(json, "failedHost");
        int failedPort = P2pJson.intValue(json, "failedPort");
        String status = P2pJson.stringValue(json, "status");

        double phi = P2pJson.doubleValue(json, "phi");
        double threshold = P2pJson.doubleValue(json, "threshold");

        String timestamp = P2pJson.stringValue(json, "timestamp");

        String message = P2pJson.stringValue(json, "message");

        int ttl = P2pJson.intValue(json, "ttl");

        return new FailureEvent(
                eventId,
                reporterNodeId,
                failedNodeId,
                failedHost,
                failedPort,
                status,
                phi,
                threshold,
                timestamp,
                message,
                ttl);
    }

    /**
     * Manual JSON output keeps this change minimal.
     * Locale.US is required so JSON numbers use "." decimals.
     */
    String toJson() {
        return String.format(
                Locale.US,
                """
                        {
                          "eventId": "%s",
                          "reporterNodeId": "%s",
                          "failedNodeId": "%s",
                          "failedHost": "%s",
                          "failedPort": %d,
                          "status": "%s",
                          "phi": %.6f,
                          "threshold": %.6f,
                          "timestamp": "%s",
                          "message": "%s",
                          "ttl": %d
                        }
                        """,
                P2pJson.escape(eventId),
                P2pJson.escape(reporterNodeId),
                P2pJson.escape(failedNodeId),
                P2pJson.escape(failedHost),
                failedPort,
                P2pJson.escape(status),
                phi,
                threshold,
                timestamp,
                P2pJson.escape(message),
                ttl);
    }
}