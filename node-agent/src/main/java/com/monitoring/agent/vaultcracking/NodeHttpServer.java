package com.monitoring.agent.vaultcracking;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitoring.agent.util.Console;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lightweight HTTP server running INSIDE NodeAgent.
 *
 * Endpoint:
 * POST /node/crack
 */
public class NodeHttpServer {

        private final int port;

        /**
         * Worker pool for cracking tasks.
         */
        private final ExecutorService crackingExecutor;

        private final ObjectMapper mapper;

        private HttpServer server;
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final String nodeId;

        private final String springResultUrl = "http://localhost:6789/api/node/result";

        public NodeHttpServer(String nodeID, int port) {
                this.nodeId = nodeID;
                this.port = port;
                this.mapper = new ObjectMapper();

                /**
                 * Fixed thread pool for parallel cracking jobs.
                 */
                this.crackingExecutor = Executors.newFixedThreadPool(
                                Runtime.getRuntime().availableProcessors());
        }

        public String getNodeID() {
                return nodeId;
        }

        public int getPort() {
                return server.getAddress().getPort();
        }

        /**
         * Starts HTTP server.
         */
        public void start() throws Exception {

                server = HttpServer.create(
                                new InetSocketAddress(port),
                                0);

                server.createContext(
                                "/node/crack",
                                this::handleCrackRequest);

                /**
                 * HTTP request handling thread pool.
                 */
                server.setExecutor(
                                Executors.newCachedThreadPool());

                server.start();

                Console.println(
                                "Cracking HTTP server started on port "
                                                + port);
        }

        /**
         * Stops server gracefully.
         */
        public void stop() {

                if (server != null) {
                        server.stop(1);
                }

                crackingExecutor.shutdownNow();

                Console.println(
                                "Cracking HTTP server stopped");
        }

        /**
         * Handles POST /node/crack
         */
        private void handleCrackRequest(HttpExchange exchange) {

                try {

                        if (!"POST".equalsIgnoreCase(
                                        exchange.getRequestMethod())) {

                                exchange.sendResponseHeaders(
                                                405,
                                                -1);

                                return;
                        }

                        String requestBody = new String(
                                        exchange.getRequestBody()
                                                        .readAllBytes());

                        CrackingRequest request = mapper.readValue(
                                        requestBody,
                                        CrackingRequest.class);
                        // sendResponse(response);
                        Console.log(
                                        "Received cracking task:");

                        Console.log(
                                        "Hash         : "
                                                        + request.getHash());

                        Console.log(
                                        "Range Start  : "
                                                        + request.getRangeStart());

                        Console.log(
                                        "Range End    : "
                                                        + request.getRangeEnd());

                        exchange.sendResponseHeaders(202, -1);

                        /**
                         * Submit cracking work asynchronously.
                         */
                        crackingExecutor.submit(() -> {

                                try {

                                        long startTime = System.currentTimeMillis();

                                        Console.log(
                                                        "Started cracking range "
                                                                        + request.getRangeStart()
                                                                        + " -> "
                                                                        + request.getRangeEnd());

                                        PasswordCracker cracker = new PasswordCracker(
                                                        request.getHash());

                                        PasswordCracker.CrackResult result = cracker.crackRange(
                                                        request.getRangeStart(),
                                                        request.getRangeEnd());

                                        CrackingResponse response = new CrackingResponse(
                                                        result.found,
                                                        result.password,
                                                        buildNodeId(),
                                                        System.currentTimeMillis()
                                                                        - startTime);

                                        sendResponse(response);

                                        if (result.found) {

                                                Console.log(
                                                                "PASSWORD FOUND: "
                                                                                + result.password);

                                        } else {

                                                Console.log(
                                                                "Password not found in assigned range. Time taken: "
                                                                                + (System.currentTimeMillis()
                                                                                                - startTime)
                                                                                + " ms");
                                        }

                                } catch (Exception e) {

                                        e.printStackTrace();
                                }
                        });

                } catch (Exception e) {

                        e.printStackTrace();

                        try {
                                exchange.sendResponseHeaders(
                                                500,
                                                -1);
                        } catch (Exception ignored) {
                        }
                }
        }

        private void sendResponse(CrackingResponse response) {

                try {
                        String json = mapper.writeValueAsString(response);

                        HttpRequest request = HttpRequest.newBuilder()
                                        .uri(URI.create(springResultUrl))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(json))
                                        .build();

                        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                        .thenAccept(res -> {
                                                Console.println(
                                                                "Sent result to Spring Boot: " + response.getNodeId());
                                        });

                } catch (Exception e) {
                        e.printStackTrace();
                }
        }

        /**
         * Generates unique node identifier.
         */
        private String buildNodeId() {

                return nodeId;
        }
}
