package com.example.agent.node;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class NodeClient {

    private final String localNodeId;
    private final int ackTimeoutSeconds;
    private final HttpClient httpClient;

    NodeClient(String localNodeId, int ackTimeoutSeconds) {
        this.localNodeId = localNodeId;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ackTimeoutSeconds))
                .build();
    }

    CompletableFuture<Boolean> ping(NodeAddress peer) {
        String json = """
                {
                  "type": "PING",
                  "senderNodeId": "%s",
                  "targetNodeId": "%s",
                  "timestamp": "%s"
                }
                """.formatted(localNodeId, peer.nodeId(), LocalDateTime.now());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(peer.pingUri())
                .timeout(Duration.ofSeconds(ackTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ackTimeoutSeconds, TimeUnit.SECONDS)
                .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                .exceptionally(error -> false);
    }

    CompletableFuture<Boolean> pingReq(NodeAddress helper, NodeAddress target) {
        String json = """
                {
                  "type": "PING_REQ",
                  "senderNodeId": "%s",
                  "helperNodeId": "%s",
                  "targetNodeId": "%s",
                  "targetHost": "%s",
                  "targetPort": %d,
                  "timestamp": "%s"
                }
                """.formatted(
                localNodeId,
                helper.nodeId(),
                target.nodeId(),
                target.host(),
                target.port(),
                LocalDateTime.now());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(helper.pingReqUri())
                .timeout(Duration.ofSeconds(ackTimeoutSeconds * 2L))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ackTimeoutSeconds * 2L, TimeUnit.SECONDS)
                .thenApply(response -> response.statusCode() >= 200
                        && response.statusCode() < 300
                        && response.body().contains("\"ackReceived\": true"))
                .exceptionally(error -> false);
    }
}