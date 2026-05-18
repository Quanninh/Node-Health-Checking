package com.example.agent.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class PeerServer {

    private final String nodeId;
    private final String bindHost;
    private final int port;
    private final PeerClient peerClient;
    private final HttpServer server;

    PeerServer(String nodeId, String bindHost, int port, PeerClient peerClient) throws IOException {
        this.nodeId = nodeId;
        this.bindHost = bindHost;
        this.port = port;
        this.peerClient = peerClient;

        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/ping", this::handlePing);
        this.server.createContext("/ping-req", this::handlePingReq);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
    }

    void start() {
        server.start();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "P2P server listening on "
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

        String targetNodeId = extractJsonValue(requestBody, "targetNodeId");
        String targetHost = extractJsonValue(requestBody, "targetHost");
        int targetPort = Integer.parseInt(extractJsonValue(requestBody, "targetPort"));

        PeerAddress target = new PeerAddress(targetNodeId, targetHost, targetPort);

        boolean ackReceived = peerClient.ping(target).join();

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
