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

class PeerServer {

    private final String nodeId;
    private final String bindHost;
    private final int port;
    private final NodeAddress localAddress;
    private final NodeClient peerClient;
    private final NeighborDirectory neighborDirectory;
    private final HttpServer server;
    private final FailureEventLog failureEventLog;

    PeerServer(
            String nodeId,
            String bindHost,
            int port,
            NodeAddress localAddress,
            NodeClient peerClient,
            NeighborDirectory neighborDirectory,
            FailureEventLog failureEventLog) throws IOException {
        this.nodeId = nodeId;
        this.bindHost = bindHost;
        this.port = port;
        this.localAddress = localAddress;
        this.peerClient = peerClient;
        this.neighborDirectory = neighborDirectory;
        this.failureEventLog = failureEventLog;

        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/ping", this::handlePing);
        this.server.createContext("/ping-req", this::handlePingReq);
        this.server.createContext("/join", this::handleJoin);
        this.server.createContext("/join-confirm", this::handleJoinConfirm);
        this.server.createContext("/neighbor-remove", this::handleNeighborRemove);
        this.server.createContext("/failure-events", this::handleFailureEvents);
        // Phase 2: receives a failure event from another node.
        // This is the decentralized replacement for sending failure reports to a central server.
        this.server.createContext("/failure-event", this::handleFailureEvent);
        this.server.setExecutor(Executors.newFixedThreadPool(8));
        
    }

    void start() {
        server.start();

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "P2P server listening on "
                        + bindHost + ":" + port);
    }

    private void handlePing(HttpExchange exchange) throws IOException {
        // Allow OPTIONS preflight for browser/debug tools.
        // Normal node-to-node ping still uses POST.
        if (handleCorsPreflight(exchange)) {
            return;
        }

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
        // Allow OPTIONS preflight for browser/debug tools.
        // Normal indirect ping request still uses POST.
        if (handleCorsPreflight(exchange)) {
            return;
        }

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
    
    private void handleFailureEvents(HttpExchange exchange) throws IOException {
        // Browser dashboard may send an OPTIONS preflight request before GET.
        // If this is OPTIONS, answer it immediately and stop here.
        if (handleCorsPreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // Read-only observer endpoint.
        // This does not decide node status; it only exposes this node's local view.
        sendResponse(exchange, 200, failureEventLog.toJson());
    }

    private void handleFailureEvent(HttpExchange exchange) throws IOException {
        // Allows browsers/tools to preflight POST /failure-event safely.
        // Node-to-node Java HttpClient usually does not need this,
        // but it keeps the endpoint browser-compatible.
        if (handleCorsPreflight(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            FailureEvent event = FailureEvent.fromJson(requestBody);

            // Store the received event locally.
            // If the same event arrives again, FailureEventLog.add(...) returns false.
            boolean added = failureEventLog.add(event);

            if (added) {
                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "FAILURE_EVENT received and stored at Node "
                                + nodeId
                                + ": " + event.eventId()
                                + " | reporter=" + event.reporterNodeId()
                                + " | failed=" + event.failedNodeId()
                                + " | phi=" + String.format(java.util.Locale.US, "%.4f", event.phi())
                                + " | ttl=" + event.ttl()
                );

                // Phase 2:
                // If this is a new event, gossip it onward to this node's neighbors.
                // Duplicate events are ignored by FailureEventLog.add(...), and TTL prevents endless loops.
                forwardReceivedFailureEvent(event);
            } else {
                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "Duplicate FAILURE_EVENT ignored at Node "
                                + nodeId
                                + ": " + event.eventId()
                );
            }

            String responseJson = """
                    {
                    "type": "FAILURE_EVENT_ACK",
                    "stored": %s,
                    "receiverNodeId": "%s",
                    "eventId": "%s",
                    "timestamp": "%s"
                    }
                    """.formatted(
                    added,
                    P2pJson.escape(nodeId),
                    P2pJson.escape(event.eventId()),
                    LocalDateTime.now()
            );

            sendResponse(exchange, 200, responseJson);
        } catch (Exception exception) {
            String responseJson = """
                    {
                    "type": "ERROR",
                    "message": "%s",
                    "timestamp": "%s"
                    }
                    """.formatted(
                    P2pJson.escape(exception.getMessage()),
                    LocalDateTime.now()
            );

            sendResponse(exchange, 400, responseJson);
        }
    }

    private void forwardReceivedFailureEvent(FailureEvent event) {
        if (event.ttl() <= 0) {
            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "FAILURE_EVENT not forwarded at Node "
                            + nodeId
                            + " because TTL reached 0: "
                            + event.eventId()
            );
            return;
        }

        FailureEvent forwardedEvent = event.decreaseTtl();

        for (NodeAddress neighbor : neighborDirectory.addresses()) {
            // Do not send the event to the node that is reported as failed.
            if (neighbor.nodeId().equals(event.failedNodeId())) {
                continue;
            }

            // Do not send the event back to the original reporter.
            // This reduces useless traffic. Duplicate checks still protect us anyway.
            if (neighbor.nodeId().equals(event.reporterNodeId())) {
                continue;
            }

            peerClient.sendFailureEvent(neighbor, forwardedEvent)
                    .thenAccept(sent -> {
                        if (sent) {
                            System.out.println(
                                    "[" + LocalDateTime.now() + "] "
                                            + "Re-forwarded FAILURE_EVENT "
                                            + event.eventId()
                                            + " from Node "
                                            + nodeId
                                            + " to Node "
                                            + neighbor.nodeId()
                                            + " with ttl="
                                            + forwardedEvent.ttl()
                            );
                        } else {
                            System.out.println(
                                    "[" + LocalDateTime.now() + "] "
                                            + "Could not re-forward FAILURE_EVENT "
                                            + event.eventId()
                                            + " from Node "
                                            + nodeId
                                            + " to Node "
                                            + neighbor.nodeId()
                            );
                        }
                    });
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

        // Allow the browser dashboard to read this node's local API.
        // The dashboard may be opened from file:// or served from another port,
        // so the browser treats it as a different origin.
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }

    private boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }

        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        exchange.sendResponseHeaders(204, -1);
        exchange.close();

        return true;
    }

}
