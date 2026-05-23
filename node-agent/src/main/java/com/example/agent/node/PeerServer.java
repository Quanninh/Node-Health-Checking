package com.example.agent.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class PeerServer {

    private final String nodeId;
    private final String bindHost;
    private final int port;
    private final NodeClient peerClient;
    private final HttpServer server;

    PeerServer(
            String nodeId,
            String bindHost,
            int port,
            NodeClient peerClient
    ) throws IOException {
        this.nodeId = nodeId;
        this.bindHost = bindHost;
        this.port = port;
        this.peerClient = peerClient;

        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/ping", this::handlePing);
        this.server.createContext("/ping-req", this::handlePingReq);
        this.server.setExecutor(Executors.newFixedThreadPool(8));
    }

    void start() {
        server.start();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "HTTP P2P failure-detection server listening on "
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

        NodeAddress target = new NodeAddress(targetNodeId, targetHost, targetPort);

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

    private void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String responseBody
    ) throws IOException {
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }
}