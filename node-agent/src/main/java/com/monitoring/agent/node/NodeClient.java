package com.monitoring.agent.node;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.util.Console;

/**
 * Sends messages to other nodes. Includes: Ping, Ping request, Gossip.
 */
public class NodeClient {

    private final String localNodeId;
    private final int ackTimeoutSeconds;
    private final HttpClient httpClient;
    private ConnectionManager connectionManager;

    public NodeClient(String localNodeId, int ackTimeoutSeconds) {
        this.localNodeId = localNodeId;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ackTimeoutSeconds))
                .build();
    }

    public void setConnectionManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Sends a PING using HTTP to the target node and waits for the response.
     * 
     * @param targetNode the target node
     * @return when the response arrives, true if status code is success
     */
    public CompletableFuture<Boolean> ping(NodeAddress targetNode) {
        String json = """
                {
                  "type": "PING",
                  "senderNodeId": "%s",
                  "targetNodeId": "%s",
                  "timestamp": "%s"
                }
                """.formatted(localNodeId, targetNode.nodeId(), LocalDateTime.now());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(targetNode.pingUri())
                .timeout(Duration.ofSeconds(ackTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ackTimeoutSeconds, TimeUnit.SECONDS)
                .thenApply(response -> {
                    if (response.statusCode() == 225) {
                        connectionManager.remove(targetNode.nodeId(),
                                "The ping target should not be a neighbor of " + localNodeId);
                        // return false;
                    }

                    return response.statusCode() >= 200 && response.statusCode() < 300;
                })
                .exceptionally(error -> false);
    }

    /**
     * Sends a PING_REQ using HTTP to the helper node to ask the helper node to ping
     * the target node and waits for the response.
     * 
     * @param helperNode the helper node
     * @param targetNode the target node
     * @return when the response arrives, true if status code is success
     */
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
                LocalDateTime.now());

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

    /**
     * Sends a gossip message to the target node.
     * 
     * @param targetNode the target node
     * @param message    the gossip message
     * @return nothing
     */
    public CompletableFuture<Void> sendGossipMessage(NodeAddress targetNode, GossipMessage message) {
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
                .uri(targetNode.gossipUri())
                .timeout(Duration.ofSeconds(ackTimeoutSeconds * 2L))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ackTimeoutSeconds * 2L, TimeUnit.SECONDS)
                .thenAccept(response -> Console.log("Gossip sent to " + targetNode.nodeId()
                        + ". statusCode=" + response.statusCode()))
                .exceptionally(error -> {
                    Console.log("Could not send gossip to " + targetNode.nodeId() + ": " + error.getMessage(),
                            Constant.RED);
                    return null;
                });
    }
}
