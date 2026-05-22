package com.example.agent.node;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.agent.constant.Constant;

public class NodeClient {

    private final String localNodeId;
    private final int ackTimeoutSeconds;
    private final HttpClient httpClient;

    public NodeClient(String localNodeId, int ackTimeoutSeconds) {
        this.localNodeId = localNodeId;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ackTimeoutSeconds))
                .build();
    }

    public CompletableFuture<Boolean> ping(NodeAddress targetNode) {
        String json = """
                {
                  "type": "PING",
                  "senderNodeId": "%s",
                  "targetNodeId": "%s",
                  "timestamp": "%s"
                }
                """.formatted(localNodeId, targetNode.nodeId(), Constant.NOW());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(targetNode.pingUri())
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

    public CompletableFuture<Boolean> pingReq(NodeAddress helperNode, NodeAddress targetNode) {
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
                helperNode.nodeId(),
                targetNode.nodeId(),
                targetNode.host(),
                targetNode.port(),
                Constant.NOW());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(helperNode.pingReqUri())
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

    public CompletableFuture<Void> sendGossipMessage(NodeAddress destinationNode, GossipMessage message) {
        String safeDetails = message.details() == null
                ? ""
                : message.details().replace("\\", "\\\\").replace("\"", "\\\"");

        String json = """
                {
                  "senderNodeId": "%s",
                  "messageId": "%s",
                  "sourceNodeId": "%s",
                  "subjectNodeId": "%s",
                  "messageType": "%s",
                  "incarnationNumber": %d,
                  "timestamp": %d,
                  "ttl": %d,
                  "details": "%s"
                }
                """.formatted(
                localNodeId,
                message.messageId(),
                message.sourceNodeId(),
                message.subjectNodeId(),
                message.messageType(),
                message.incarnationNumber(),
                message.timestamp(),
                message.ttl(),
                safeDetails);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(destinationNode.gossipUri())
                .timeout(Duration.ofSeconds(ackTimeoutSeconds * 2L))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ackTimeoutSeconds * 2L, TimeUnit.SECONDS)
                .thenAccept(response -> System.out.println(
                        "\n[" + Constant.NOW() + "] "
                                + "Gossip sent to " + destinationNode.nodeId()
                                + ". statusCode=" + response.statusCode()))
                .exceptionally(error -> {
                    System.out.println(
                            "\n[" + Constant.NOW() + "] "
                                    + "Could not send gossip to "
                                    + destinationNode.nodeId() + ": "
                                    + error.getMessage());
                    return null;
                });
    }
}
