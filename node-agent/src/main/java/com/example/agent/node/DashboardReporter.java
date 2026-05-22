package com.example.agent.node;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

class DashboardReporter {

        private final String localNodeId;
        private final String dashboardUrl;
        private final HttpClient httpClient;

        DashboardReporter(String localNodeId, String dashboardUrl) {
                this.localNodeId = localNodeId;
                this.dashboardUrl = removeTrailingSlash(dashboardUrl);
                this.httpClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build();
        }

        CompletableFuture<Void> reportSelfAlive(String advertiseHost, int p2pPort) {
                String json = """
                                {
                                  "id": "%s",
                                  "ipAddress": "%s:%d",
                                  "status": "UP"
                                }
                                """.formatted(localNodeId, advertiseHost, p2pPort);

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(dashboardUrl + "/heartbeat"))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();

                return httpClient
                                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .thenAccept(response -> System.out.println(
                                                "[" + LocalDateTime.now() + "] "
                                                                + "Registered self with dashboard. Status: "
                                                                + response.statusCode()))
                                .exceptionally(error -> {
                                        System.out.println(
                                                        "[" + LocalDateTime.now() + "] "
                                                                        + "Could not register with dashboard: "
                                                                        + error.getMessage());
                                        return null;
                                });
        }

        CompletableFuture<Void> reportFailure(NodeAddress failedNode, double phi, double threshold) {
                String message = String.format(
                                Locale.US,
                                "Node %s classifies Node %s as UNREACHABLE by phi threshold. phi=%.4f, threshold=%.4f. If this node comes back, it must rejoin as a new node.",
                                localNodeId,
                                failedNode.nodeId(),
                                phi,
                                threshold);

                // This report is sent to the centralized Spring Boot observability server.
                // The health checking decision is still made locally by this node.
                String json = String.format(
                                Locale.US,
                                """
                                                {
                                                  "reporterNodeId": "%s",
                                                  "failedNodeId": "%s",
                                                  "message": "%s",
                                                  "phi": %.6f,
                                                  "threshold": %.6f,
                                                  "status": "UNREACHABLE",
                                                  "timestamp": "%s"
                                                }
                                                """,
                                localNodeId,
                                failedNode.nodeId(),
                                escapeJson(message),
                                phi,
                                threshold,
                                LocalDateTime.now());

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(dashboardUrl + "/failure-report"))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();

                return httpClient
                                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .thenAccept(response -> System.out.println(
                                                "[" + LocalDateTime.now() + "] "
                                                                + "UNREACHABLE report sent to dashboard. Status: "
                                                                + response.statusCode()))
                                .exceptionally(error -> {
                                        System.out.println(
                                                        "[" + LocalDateTime.now() + "] "
                                                                        + "Could not report unreachable node to dashboard: "
                                                                        + error.getMessage());
                                        return null;
                                });
        }

        // Backward-compatible overload in case some old code still calls
        // reportFailure(node, phi).
        CompletableFuture<Void> reportFailure(NodeAddress failedNode, double phi) {
                return reportFailure(failedNode, phi, 5.0);
        }

        private String removeTrailingSlash(String value) {
                if (value.endsWith("/")) {
                        return value.substring(0, value.length() - 1);
                }

                return value;
        }

        private String escapeJson(String value) {
                if (value == null) {
                        return "";
                }

                return value
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"");
        }
}