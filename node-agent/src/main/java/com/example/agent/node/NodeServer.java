package com.example.agent.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class NodeServer {
    private final String nodeId;
    private final String bindHost;
    private final int port;
    private final NodeClient peerClient;
    private final HttpServer server;
    private volatile GossipService gossipService;

    PeerServer(String nodeId, String bindHost, int port, NodeClient peerClient) throws IOException {
        this.nodeId = nodeId;
        this.bindHost = bindHost;
        this.port = port;
        this.peerClient = peerClient;

        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/ping", this::handlePing);
        this.server.createContext("/ping-req", this::handlePingReq);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
    }

    void setGossipService(GossipService gossipService) {
        this.gossipService = gossipService;
    }

    void start() {
        server.start();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Node server listening on "
                        + bindHost + ":" + port);
    }

    private void handlePing(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String responseJson = """
                {
                  "type": "ACK",
                  "receiverNodeId": "%s",
                  "timestamp": "%s"
                }
                """.formatted(nodeId, LocalDateTime.now());

        sendResponse(exchange, 200, responseJson);
    }

    private void handlePingReq(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        String targetNodeId = P2pJson.stringValue(requestBody, "targetNodeId");
        String targetHost = P2pJson.stringValue(requestBody, "targetHost");
        int targetPort = P2pJson.intValue(requestBody, "targetPort");

        NodeAddress targetNode = new NodeAddress(targetNodeId, targetHost, targetPort);

        boolean ackReceived = nodeClient.ping(targetNode).join();

        String responseJson = """
                {
                  "type": "PING_REQ_RESULT",
                  "helperNodeId": "%s",
                  "targetNodeId": "%s",
                  "ackReceived": %s,
                  "timestamp": "%s"
                }
                """.formatted(
                nodeId,
                targetNodeId,
                ackReceived,
                LocalDateTime.now());

        sendResponse(exchange, 200, responseJson);
    }

    private void handleGossip(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        if (gossipService == null) {
            sendResponse(exchange, 503, "{\"error\":\"GossipService not ready\"}");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        GossipMessage message = GossipMessage.fromJson(requestBody);
        String senderNodeId = extractJsonValue(requestBody, "senderNodeId");

        gossipService.receiveGossip(message, senderNodeId);

        sendResponse(exchange, 200, "{\"status\":\"gossip received\"}");
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            NodeAddress joiningNode = parseJoiningNode(requestBody);

            Optional<NodeAddress> suggestedPeer = neighborDirectory.randomNeighborForRedistribution();

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "JOIN_REQUEST received from " + joiningNode
                            + ". suggestedPeer=" + suggestedPeer.orElse(null));

            String responseJson = joinAckJson(true, suggestedPeer.orElse(null), "candidate accepted; confirmation required");
            sendResponse(exchange, 200, responseJson);
        } catch (Exception exception) {
            sendResponse(exchange, 400, joinAckJson(false, null, exception.getMessage()));
        }
    }

    private void handleJoinConfirm(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            NodeAddress joiningNode = parseJoiningNode(requestBody);

            NeighborUpdateResult result = neighborDirectory.addOrReplaceForJoin(joiningNode);

            if (result.evictedPeer() != null) {
                peerClient.notifyNeighborRemove(result.evictedPeer(), localAddress);
            }

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "JOIN_CONFIRM from " + joiningNode
                            + ". accepted=" + result.accepted()
                            + ", evictedPeer=" + result.evictedPeer()
                            + ", reason=" + result.reason());

            sendResponse(exchange, 200, joinConfirmJson(result));
        } catch (Exception exception) {
            NeighborUpdateResult result = new NeighborUpdateResult(false, null, null, exception.getMessage());
            sendResponse(exchange, 400, joinConfirmJson(result));
        }
    }

    private void handleNeighborRemove(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            NodeAddress removingNode = parseJoiningNode(requestBody);
            boolean removed = neighborDirectory.removeNeighbor(removingNode.nodeId());

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "NEIGHBOR_REMOVE received from " + removingNode
                            + ". removed=" + removed);

            String responseJson = """
                    {
                      "type": "NEIGHBOR_REMOVE_ACK",
                      "removed": %s,
                      "timestamp": "%s"
                    }
                    """.formatted(removed, LocalDateTime.now());

            sendResponse(exchange, 200, responseJson);
        } catch (Exception exception) {
            sendResponse(exchange, 400, "{\"type\":\"ERROR\",\"message\":\"" + P2pJson.escape(exception.getMessage()) + "\"}");
        }
    }

    private NodeAddress parseJoiningNode(String requestBody) {
        String joiningNodeId = P2pJson.stringValue(requestBody, "nodeId");
        String joiningHost = P2pJson.stringValue(requestBody, "advertiseHost");
        int joiningPort = P2pJson.intValue(requestBody, "p2pPort");

        return new NodeAddress(joiningNodeId, joiningHost, joiningPort);
    }

    private String joinAckJson(boolean accepted, NodeAddress suggestedPeer, String reason) {
        String suggestedPart = """
                  "suggestedNodeId": null,
                  "suggestedHost": null,
                  "suggestedPort": 0,
                """;

        if (suggestedPeer != null) {
            suggestedPart = """
                  "suggestedNodeId": "%s",
                  "suggestedHost": "%s",
                  "suggestedPort": %d,
                """.formatted(
                    P2pJson.escape(suggestedPeer.nodeId()),
                    P2pJson.escape(suggestedPeer.host()),
                    suggestedPeer.port());
        }

        return """
                {
                  "type": "JOIN_ACK",
                  "accepted": %s,
                  "responderNodeId": "%s",
                  "responderHost": "%s",
                  "responderPort": %d,
                %s  "reason": "%s",
                  "timestamp": "%s"
                }
                """.formatted(
                accepted,
                P2pJson.escape(localAddress.nodeId()),
                P2pJson.escape(localAddress.host()),
                localAddress.port(),
                suggestedPart,
                P2pJson.escape(reason),
                LocalDateTime.now());
    }

    private String joinConfirmJson(NeighborUpdateResult result) {
        String evictedPart = """
                  "evictedNodeId": null,
                  "evictedHost": null,
                  "evictedPort": 0,
                """;

        if (result.evictedPeer() != null) {
            evictedPart = """
                  "evictedNodeId": "%s",
                  "evictedHost": "%s",
                  "evictedPort": %d,
                """.formatted(
                    P2pJson.escape(result.evictedPeer().nodeId()),
                    P2pJson.escape(result.evictedPeer().host()),
                    result.evictedPeer().port());
        }

        return """
                {
                  "type": "JOIN_CONFIRM_RESULT",
                  "accepted": %s,
                %s  "reason": "%s",
                  "timestamp": "%s"
                }
                """.formatted(
                result.accepted(),
                evictedPart,
                P2pJson.escape(result.reason()),
                LocalDateTime.now());
    }

    private void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String responseBody) throws IOException {

        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }
}
