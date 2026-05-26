package com.monitoring.agent.vaultcracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitoring.agent.util.Console;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.http.HttpClient;


/**
 * Lightweight HTTP server running INSIDE NodeAgent.
 *
 * Endpoint:
 * POST /node/crack
 */
public class NodeHttpServer {

<<<<<<< HEAD
        private final int port;
=======
    private final int port;

    /**
     * Worker pool for cracking tasks.
     */
    private final ExecutorService crackingExecutor;

    private final ObjectMapper mapper;

    private HttpServer server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final String springResultUrl =
                "http://localhost:6789/node/result";

    public NodeHttpServer(int port) {
        this.port = port;
        this.mapper = new ObjectMapper();
>>>>>>> 57f383c900e2aaae6d9d668a33a3939f62d30aec

        /**
         * Worker pool for cracking tasks.
         */
        private final ExecutorService crackingExecutor;

        private final ObjectMapper mapper;

        private HttpServer server;

        public NodeHttpServer(int port) {
                this.port = port;
                this.mapper = new ObjectMapper();

                /**
                 * Fixed thread pool for parallel cracking jobs.
                 */
                this.crackingExecutor = Executors.newFixedThreadPool(
                                Runtime.getRuntime().availableProcessors());
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

<<<<<<< HEAD
                        CrackingRequest request = mapper.readValue(
                                        requestBody,
                                        CrackingRequest.class);
=======
                    sendResponse(response);
>>>>>>> 57f383c900e2aaae6d9d668a33a3939f62d30aec

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

                                        sendResponse(
                                                        exchange,
                                                        response);

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

                                        try {
                                                exchange.sendResponseHeaders(
                                                                500,
                                                                -1);
                                        } catch (Exception ignored) {
                                        }
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

<<<<<<< HEAD
        /**
         * Sends JSON response back to server.
         */
        private void sendResponse(
                        HttpExchange exchange,
                        CrackingResponse response) {

                try {

                        String json = mapper.writeValueAsString(
                                        response);

                        exchange.getResponseHeaders()
                                        .add(
                                                        "Content-Type",
                                                        "application/json");

                        exchange.sendResponseHeaders(
                                        200,
                                        json.getBytes().length);

                        OutputStream outputStream = exchange.getResponseBody();

                        outputStream.write(
                                        json.getBytes());

                        outputStream.flush();

                        outputStream.close();

                } catch (Exception e) {

                        e.printStackTrace();
                }
=======
    /**
     * Sends JSON response back to server.
     */
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
                                "Sent result to Spring Boot: " + response.getNodeId()
                        );
                        });

        } catch (Exception e) {
                e.printStackTrace();
>>>>>>> 57f383c900e2aaae6d9d668a33a3939f62d30aec
        }

        /**
         * Generates unique node identifier.
         */
        private String buildNodeId() {

                return "node-" + port;
        }
}
