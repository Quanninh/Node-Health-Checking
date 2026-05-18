package com.example.agent.node;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class PeerClient {

    private final String localNodeId;
    private final int ackTimeoutSeconds;
    private final HttpClient httpClient;

    PeerClient(String localNodeId, int ackTimeoutSeconds) {
        this.localNodeId = localNodeId;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ackTimeoutSeconds))
                .build();
    }

    CompletableFuture<Boolean> ping(PeerAddress peer) {
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
}
