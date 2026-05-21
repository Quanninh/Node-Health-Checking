package com.example.agent.node;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
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

        CompletableFuture<Optional<JoinAck>> join(NodeAddress bootstrapPeer, NodeAddress localAddress) {
                String json = """
                                {
                                  "type": "JOIN_REQUEST",
                                  "nodeId": "%s",
                                  "advertiseHost": "%s",
                                  "p2pPort": %d,
                                  "timestamp": "%s"
                                }
                                """.formatted(
                                localAddress.nodeId(),
                                P2pJson.escape(localAddress.host()),
                                localAddress.port(),
                                LocalDateTime.now());

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(bootstrapPeer.joinUri())
                                .timeout(Duration.ofSeconds(ackTimeoutSeconds * 2L))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();

                return httpClient
                                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .orTimeout(ackTimeoutSeconds * 2L, TimeUnit.SECONDS)
                                .thenApply(response -> {
                                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                                return Optional.<JoinAck>empty();
                                        }

                                        return Optional.of(parseJoinAck(response.body()));
                                })
                                .exceptionally(error -> Optional.empty());
        }

        CompletableFuture<Optional<JoinConfirmResult>> confirmJoin(NodeAddress selectedPeer, NodeAddress localAddress) {
                String json = """
                                {
                                  "type": "JOIN_CONFIRM",
                                  "nodeId": "%s",
                                  "advertiseHost": "%s",
                                  "p2pPort": %d,
                                  "timestamp": "%s"
                                }
                                """.formatted(
                                localAddress.nodeId(),
                                P2pJson.escape(localAddress.host()),
                                localAddress.port(),
                                LocalDateTime.now());

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(selectedPeer.joinConfirmUri())
                                .timeout(Duration.ofSeconds(ackTimeoutSeconds * 2L))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();

                return httpClient
                                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .orTimeout(ackTimeoutSeconds * 2L, TimeUnit.SECONDS)
                                .thenApply(response -> {
                                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                                return Optional.<JoinConfirmResult>empty();
                                        }

                                        return Optional.of(parseJoinConfirmResult(response.body(), selectedPeer));
                                })
                                .exceptionally(error -> Optional.empty());
        }

        CompletableFuture<Boolean> notifyNeighborRemove(NodeAddress removedPeer, NodeAddress localAddress) {
                String json = """
                                {
                                  "type": "NEIGHBOR_REMOVE",
                                  "nodeId": "%s",
                                  "advertiseHost": "%s",
                                  "p2pPort": %d,
                                  "timestamp": "%s"
                                }
                                """.formatted(
                                localAddress.nodeId(),
                                P2pJson.escape(localAddress.host()),
                                localAddress.port(),
                                LocalDateTime.now());

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(removedPeer.removeNeighborUri())
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

        CompletableFuture<Boolean> sendFailureEvent(NodeAddress peer, FailureEvent event) {
                HttpRequest request = HttpRequest.newBuilder()
                                .uri(peer.failureEventUri())
                                .timeout(Duration.ofSeconds(ackTimeoutSeconds))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(event.toJson()))
                                .build();

                // Send one failure event to one peer.
                // Returning false on error keeps gossip/broadcast best-effort:
                // one unreachable neighbor should not stop the whole node.
                return httpClient
                                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .orTimeout(ackTimeoutSeconds, TimeUnit.SECONDS)
                                .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                                .exceptionally(error -> false);
        }


        private JoinAck parseJoinAck(String json) {
                boolean accepted = P2pJson.booleanValue(json, "accepted");
                String responderNodeId = P2pJson.stringValue(json, "responderNodeId");
                String responderHost = P2pJson.stringValue(json, "responderHost");
                int responderPort = P2pJson.intValue(json, "responderPort");
                String suggestedNodeId = P2pJson.optionalStringValue(json, "suggestedNodeId");
                String suggestedHost = P2pJson.optionalStringValue(json, "suggestedHost");
                String reason = P2pJson.optionalStringValue(json, "reason");

                NodeAddress responder = new NodeAddress(responderNodeId, responderHost, responderPort);
                NodeAddress suggestedPeer = null;

                if (suggestedNodeId != null && suggestedHost != null) {
                        int suggestedPort = P2pJson.intValue(json, "suggestedPort");
                        suggestedPeer = new NodeAddress(suggestedNodeId, suggestedHost, suggestedPort);
                }

                return new JoinAck(accepted, responder, suggestedPeer, reason);
        }

        private JoinConfirmResult parseJoinConfirmResult(String json, NodeAddress selectedPeer) {
                boolean accepted = P2pJson.booleanValue(json, "accepted");
                String evictedNodeId = P2pJson.optionalStringValue(json, "evictedNodeId");
                String evictedHost = P2pJson.optionalStringValue(json, "evictedHost");
                String reason = P2pJson.optionalStringValue(json, "reason");

                NodeAddress evictedPeer = null;

                if (evictedNodeId != null && evictedHost != null) {
                        int evictedPort = P2pJson.intValue(json, "evictedPort");
                        evictedPeer = new NodeAddress(evictedNodeId, evictedHost, evictedPort);
                }

                return new JoinConfirmResult(accepted, selectedPeer, evictedPeer, reason);
        }
}
