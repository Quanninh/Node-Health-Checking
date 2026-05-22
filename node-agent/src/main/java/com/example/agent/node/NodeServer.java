package com.example.agent.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.agent.constant.Constant;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class NodeServer {

    private final String nodeId;
    private final String bindHost;
    private final int port;
    private final NodeClient nodeClient;
    private final HttpServer server;
    private volatile GossipService gossipService;

    NodeServer(String nodeId, String bindHost, int port, NodeClient nodeClient) throws IOException {
        this.nodeId = nodeId;
        this.bindHost = bindHost;
        this.port = port;
        this.nodeClient = nodeClient;

        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/ping", this::handlePing);
        this.server.createContext("/ping-req", this::handlePingReq);
        this.server.createContext("/gossip", this::handleGossip);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
    }

    void setGossipService(GossipService gossipService) {
        this.gossipService = gossipService;
    }

    void start() {
        server.start();

        System.out.println(
                "[" + Constant.NOW() + "] "
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
                """.formatted(nodeId, Constant.NOW());

        sendResponse(exchange, 200, responseJson);
    }

    private void handlePingReq(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        String targetNodeId = extractJsonValue(requestBody, "targetNodeId");
        String targetHost = extractJsonValue(requestBody, "targetHost");
        int targetPort = Integer.parseInt(extractJsonValue(requestBody, "targetPort"));

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
                Constant.NOW());

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

    private String extractJsonValue(String json, String fieldName) {
        Pattern stringPattern = Pattern.compile("\\\"" + fieldName + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher stringMatcher = stringPattern.matcher(json);

        if (stringMatcher.find()) {
            return URLDecoder.decode(stringMatcher.group(1), StandardCharsets.UTF_8);
        }

        Pattern numberPattern = Pattern.compile("\\\"" + fieldName + "\\\"\\s*:\\s*(\\d+)");
        Matcher numberMatcher = numberPattern.matcher(json);

        if (numberMatcher.find()) {
            return numberMatcher.group(1);
        }

        throw new IllegalArgumentException("Missing field in JSON: " + fieldName);
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
