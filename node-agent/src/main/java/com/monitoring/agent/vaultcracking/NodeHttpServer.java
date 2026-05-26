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

        /**
         * Fixed thread pool for parallel cracking jobs.
         */
        this.crackingExecutor =
                Executors.newFixedThreadPool(
                        Runtime.getRuntime().availableProcessors()
                );
    }

    /**
     * Starts HTTP server.
     */
    public void start() throws Exception {

        server = HttpServer.create(
                new InetSocketAddress(port),
                0
        );

        server.createContext(
                "/node/crack",
                this::handleCrackRequest
        );

        /**
         * HTTP request handling thread pool.
         */
        server.setExecutor(
                Executors.newCachedThreadPool()
        );

        server.start();

        Console.println(
                "Cracking HTTP server started on port "
                        + port
        );
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
                "Cracking HTTP server stopped"
        );
    }

    /**
     * Handles POST /node/crack
     */
    private void handleCrackRequest(HttpExchange exchange) {

        try {

            if (!"POST".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {

                exchange.sendResponseHeaders(
                        405,
                        -1
                );

                return;
            }

            String requestBody =
                    new String(
                            exchange.getRequestBody()
                                    .readAllBytes()
                    );

            CrackingRequest request =
                    mapper.readValue(
                            requestBody,
                            CrackingRequest.class
                    );

            Console.println(
                    "Received cracking task:"
            );

            Console.println(
                    "Hash         : "
                            + request.getHash()
            );

            Console.println(
                    "Range Start  : "
                            + request.getRangeStart()
            );

            Console.println(
                    "Range End    : "
                            + request.getRangeEnd()
            );

            /**
             * Submit cracking work asynchronously.
             */
            crackingExecutor.submit(() -> {

                try {

                    long startTime =
                            System.currentTimeMillis();

                    PasswordCracker cracker =
                            new PasswordCracker(
                                    request.getHash()
                            );

                    PasswordCracker.CrackResult result =
                            cracker.crackRange(
                                    request.getRangeStart(),
                                    request.getRangeEnd()
                            );

                    CrackingResponse response =
                            new CrackingResponse(
                                    result.found,
                                    result.password,
                                    buildNodeId(),
                                    System.currentTimeMillis()
                                            - startTime
                            );

                    sendResponse(response);

                    if (result.found) {

                        Console.println(
                                "PASSWORD FOUND: "
                                        + result.password
                        );

                    } else {

                        Console.println(
                                "Password not found in assigned range"
                        );
                    }

                } catch (Exception e) {

                    e.printStackTrace();

                    try {
                        exchange.sendResponseHeaders(
                                500,
                                -1
                        );
                    } catch (Exception ignored) {
                    }
                }
            });

        } catch (Exception e) {

            e.printStackTrace();

            try {
                exchange.sendResponseHeaders(
                        500,
                        -1
                );
            } catch (Exception ignored) {
            }
        }
    }

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
        }
    }

    /**
     * Generates unique node identifier.
     */
    private String buildNodeId() {

        return "node-" + port;
    }
}