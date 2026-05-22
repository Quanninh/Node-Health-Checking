package com.monitoring.agent.node;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.monitoring.agent.constant.Constant;

/**
 * Reports the status of the node to the dashboard.
 */
public class DashboardReporter {

    /** The node that this reporter is in. */
    private final String localNodeId;
    private final String dashboardUrl;
    private final HttpClient httpClient;

    public DashboardReporter(String localNodeId, String dashboardUrl) {
        this.localNodeId = localNodeId;
        this.dashboardUrl = removeTrailingSlash(dashboardUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Reports to the dashboard that the current node is alive.
     * 
     * @param advertiseHost ip address for self-advertisement address
     * @param p2pPort       port for self-advertisement address
     * @return
     */
    public CompletableFuture<Void> reportSelfAlive(String advertiseHost, int p2pPort) {
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
                .thenAccept(response -> System.out.println("[" + Constant.NOW() + "] " + Constant.CYAN
                        + "Registered self with dashboard. Status: " + response.statusCode() + Constant.RESET))
                .exceptionally(error -> {
                    System.out.println(
                            "[" + Constant.NOW() + "] " + Constant.RED + "Could not register with dashboard: "
                                    + error.getMessage() + Constant.RESET);
                    return null;
                });
    }

    /**
     * Reports to the dashboard that another node failed.
     * 
     * @param failedNode the failed node
     * @param phi        phi calculations for the failed node
     * @return
     */
    public CompletableFuture<Void> reportFailure(NodeAddress failedNode, double phi, double threshold) {
        String message = "Node " + localNodeId
                + " classifies Node " + failedNode.nodeId()
                + " as UNREACHABLE by phi threshold. phi=" + String.format("%.4f", phi) + ", threshold="
                + String.format("%.4f", threshold)
                + ". If this node comes back, it must rejoin as a new node.";

        String json = String.format(
                Locale.US, """
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
                message,
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
                .thenAccept(response -> System.out.println("\n[" + Constant.NOW() + "] " + Constant.PURPLE
                        + "UNREACHABLE report sent to dashboard. Status: " + response.statusCode() + Constant.RESET))
                .exceptionally(error -> {
                    System.out.println(
                            "\n[" + Constant.NOW() + "] " + Constant.RED
                                    + "Could not report unreachable node to dashboard: "
                                    + error.getMessage() + Constant.RESET);
                    return null;
                });
    }

    /**
     * Removes trailing forward slashes from URLs
     * 
     * @param value the URL
     * @return the formatted URL
     */
    private String removeTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }

        return value;
    }
}
