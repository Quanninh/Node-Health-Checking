package com.monitoring.agent.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.monitoring.agent.constant.Constant;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.util.Console;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Node Server to receive HTTP requests and handles them.
 */
public class NodeServer {

    private final String nodeId;
    private final String bindHost;
    private final int port;
    private final NodeClient nodeClient;
    private final HttpServer server;
    private volatile GossipService gossipService;
    private ConnectionManager connectionManager = null;

    public NodeServer(String nodeId, String bindHost, int port, NodeClient nodeClient) throws IOException {
        this.nodeId = nodeId;
        this.bindHost = bindHost;
        this.port = port;
        this.nodeClient = nodeClient;

        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/ping", this::handlePing);
        this.server.createContext("/ping-req", this::handlePingReq);
        this.server.createContext("/gossip", this::handleGossip);
        this.server.setExecutor(Executors.newFixedThreadPool(10));
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * Sets the gossip service
     * 
     * @param gossipService the gossip service
     */
    public void setGossipService(GossipService gossipService) {
        this.gossipService = gossipService;
    }

    public void setConnectionManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Starts the node server to listen to HTTP requests.
     */
    public void start() {
        server.start();
        Console.log("Node server listening on " + bindHost + ":" + port, Constant.BG_GREEN);
    }

    /**
     * Handles PING requests. Sends back an ACK.
     * 
     * @param exchange the HTTP request
     * @throws IOException
     */
    private void handlePing(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            Console.log("Method not allowed");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        String senderNodeId = extractJsonValue(requestBody, "senderNodeId");

        String responseJson = """
                {
                  "type": "ACK",
                  "receiverNodeId": "%s",
                  "timestamp": "%s"
                }
                """.formatted(nodeId, LocalDateTime.now());

        int statusCode = (connectionManager != null && connectionManager.containsNode(senderNodeId)) ? 200 : 225;
        if (statusCode == 225) {
            Console.logWarning(senderNodeId + " is not in my neighbor list. sending code 225 to remove.");
        }

        sendResponse(exchange, statusCode, responseJson);
    }

    /**
     * Handles PING_REQ requests. Pings the requested target node and waits for the
     * result. Returns the result to the sender.
     * 
     * @param exchange the HTTP request
     * @throws IOException
     */
    private void handlePingReq(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            Console.log("Method not allowed");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        String targetNodeId = extractJsonValue(requestBody, "targetNodeId");
        String targetHost = extractJsonValue(requestBody, "targetHost");
        int targetPort = Integer.parseInt(extractJsonValue(requestBody, "targetPort"));

        NodeAddress targetNode = new NodeAddress(targetNodeId, targetHost, targetPort);

        int targetStatusCode = nodeClient.ping(targetNode).join();
        boolean ackReceived = targetStatusCode >= 200 && targetStatusCode < 300;

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

    /**
     * Receives a gossip.
     * 
     * @param exchange the gossip
     * @throws IOException
     * @see GossipService#receiveGossip(GossipMessage, String)
     * @see GossipService#forwardGossip(GossipMessage, String)
     */
    private void handleGossip(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            Console.log("Method not allowed");
            return;
        }

        if (gossipService == null) {
            sendResponse(exchange, 503,
                    "{\"error\":\"GossipService not ready\"}");
            Console.log("Gossip service not ready");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        sendResponse(exchange, 200, "{\"status\":\"gossip received\"}");

        GossipMessage message = GossipMessage.fromJson(requestBody);
        String senderNodeId = extractJsonValue(requestBody, "senderNodeId");

        gossipService.receiveGossip(message, senderNodeId);
    }

    /**
     * Extracts a JSON value from the json.
     * 
     * @param json      the json
     * @param fieldName the field
     * @return the value associated with the field
     */
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

    /**
     * Sends a response to the sender with a status code and a response body.
     * 
     * @param exchange     the HTTP request
     * @param statusCode   the status code
     * @param responseBody the response body
     * @throws IOException
     */
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
