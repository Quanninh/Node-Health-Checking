import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class NodeAgent {

    private static final int DEFAULT_GOSSIP_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_ACK_TIMEOUT_SECONDS = 10;

    public static void main(String[] args) throws IOException {
        AgentConfig config = AgentConfig.fromArgs(args);

        PeerDirectory peerDirectory = new PeerDirectory(config.peers());
        DashboardReporter dashboardReporter = new DashboardReporter(
                config.nodeId(),
                config.dashboardUrl()
        );

        PeerServer peerServer = new PeerServer(
                config.nodeId(),
                config.bindHost(),
                config.p2pPort()
        );

        PeerClient peerClient = new PeerClient(
                config.nodeId(),
                config.ackTimeoutSeconds()
        );

        FailureDetector failureDetector = new FailureDetector(
                config.nodeId(),
                peerDirectory,
                peerClient,
                dashboardReporter,
                config.gossipIntervalSeconds()
        );

        peerServer.start();

        dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

        failureDetector.start();

        printStartupInfo(config);
    }

    private static void printStartupInfo(AgentConfig config) {
        System.out.println("====================================");
        System.out.println("Node Agent Started");
        System.out.println("Node ID          : " + config.nodeId());
        System.out.println("Bind Address     : " + config.bindHost() + ":" + config.p2pPort());
        System.out.println("Advertise Address: " + config.advertiseHost() + ":" + config.p2pPort());
        System.out.println("Dashboard URL    : " + config.dashboardUrl());
        System.out.println("Peers            : " + config.peers());
        System.out.println("Gossip interval  : " + config.gossipIntervalSeconds() + " seconds");
        System.out.println("ACK timeout      : " + config.ackTimeoutSeconds() + " seconds");
        System.out.println("====================================");
    }

    // =========================================================
    // CONFIG
    // =========================================================

    record AgentConfig(
            String nodeId,
            String bindHost,
            String advertiseHost,
            int p2pPort,
            String dashboardUrl,
            List<PeerAddress> peers,
            int gossipIntervalSeconds,
            int ackTimeoutSeconds
    ) {

        static AgentConfig fromArgs(String[] args) {
            Map<String, String> values = parseArgs(args);

            String nodeId = values.getOrDefault(
                    "--node-id",
                    "node-" + UUID.randomUUID().toString().substring(0, 8)
            );

            String bindHost = values.getOrDefault("--bind-host", "127.0.0.1");
            String advertiseHost = values.getOrDefault("--advertise-host", bindHost);

            int p2pPort = Integer.parseInt(values.getOrDefault("--p2p-port", "9001"));

            String dashboardUrl = values.getOrDefault(
                    "--dashboard-url",
                    "http://localhost:6789/api"
            );

            int gossipIntervalSeconds = Integer.parseInt(values.getOrDefault(
                    "--gossip-interval-seconds",
                    String.valueOf(DEFAULT_GOSSIP_INTERVAL_SECONDS)
            ));

            int ackTimeoutSeconds = Integer.parseInt(values.getOrDefault(
                    "--ack-timeout-seconds",
                    String.valueOf(DEFAULT_ACK_TIMEOUT_SECONDS)
            ));

            List<PeerAddress> peers = parsePeers(values.getOrDefault("--peers", ""));

            return new AgentConfig(
                    nodeId,
                    bindHost,
                    advertiseHost,
                    p2pPort,
                    dashboardUrl,
                    peers,
                    gossipIntervalSeconds,
                    ackTimeoutSeconds
            );
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> values = new ConcurrentHashMap<>();

            for (int i = 0; i < args.length; i++) {
                String current = args[i];

                if (current.startsWith("--")) {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        values.put(current, args[i + 1]);
                        i++;
                    } else {
                        values.put(current, "true");
                    }
                }
            }

            return values;
        }

        private static List<PeerAddress> parsePeers(String rawPeers) {
            List<PeerAddress> peers = new ArrayList<>();

            if (rawPeers == null || rawPeers.isBlank()) {
                return peers;
            }

            String[] peerTokens = rawPeers.split(",");

            for (String token : peerTokens) {
                String trimmed = token.trim();

                if (trimmed.isBlank()) {
                    continue;
                }

                peers.add(PeerAddress.from(trimmed));
            }

            return peers;
        }
    }

    // =========================================================
    // PEER ADDRESS
    // Format: B@127.0.0.1:9002
    // =========================================================

    record PeerAddress(String nodeId, String host, int port) {

        static PeerAddress from(String value) {
            String[] idAndAddress = value.split("@");

            if (idAndAddress.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid peer format. Expected nodeId@host:port but got: " + value
                );
            }

            String nodeId = idAndAddress[0];

            String[] hostAndPort = idAndAddress[1].split(":");

            if (hostAndPort.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid peer address. Expected host:port but got: " + idAndAddress[1]
                );
            }

            String host = hostAndPort[0];
            int port = Integer.parseInt(hostAndPort[1]);

            return new PeerAddress(nodeId, host, port);
        }

        URI pingUri() {
            return URI.create("http://" + host + ":" + port + "/ping");
        }

        @Override
        public String toString() {
            return nodeId + "@" + host + ":" + port;
        }
    }

    // =========================================================
    // PEER STATUS
    // =========================================================

    enum PeerStatus {
        ALIVE,
        FAILED,
        UNKNOWN
    }

    static class PeerState {

        private final PeerAddress peerAddress;
        private volatile PeerStatus status;
        private volatile LocalDateTime lastAckTime;
        private volatile LocalDateTime lastFailureTime;

        PeerState(PeerAddress peerAddress) {
            this.peerAddress = peerAddress;
            this.status = PeerStatus.UNKNOWN;
        }

        void markAlive() {
            this.status = PeerStatus.ALIVE;
            this.lastAckTime = LocalDateTime.now();
        }

        void markFailed() {
            this.status = PeerStatus.FAILED;
            this.lastFailureTime = LocalDateTime.now();
        }

        PeerStatus status() {
            return status;
        }

        @Override
        public String toString() {
            return "PeerState{" +
                    "peerAddress=" + peerAddress +
                    ", status=" + status +
                    ", lastAckTime=" + lastAckTime +
                    ", lastFailureTime=" + lastFailureTime +
                    '}';
        }
    }

    // =========================================================
    // PEER DIRECTORY
    // Keeps local peer list and sequential cursor.
    // After completing one full pass, shuffles peers randomly.
    // =========================================================

    static class PeerDirectory {

        private final List<PeerAddress> peers;
        private final Map<String, PeerState> peerStates;
        private int nextIndex = 0;

        PeerDirectory(List<PeerAddress> peers) {
            this.peers = new ArrayList<>(peers);
            this.peerStates = new ConcurrentHashMap<>();

            for (PeerAddress peer : peers) {
                peerStates.put(peer.nodeId(), new PeerState(peer));
            }
        }

        synchronized Optional<PeerAddress> nextPeer() {
            if (peers.isEmpty()) {
                return Optional.empty();
            }

            if (nextIndex >= peers.size()) {
                Collections.shuffle(peers);
                nextIndex = 0;

                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "Completed one full peer cycle. Shuffled peer list: "
                                + peers
                );
            }

            PeerAddress selectedPeer = peers.get(nextIndex);
            nextIndex++;

            return Optional.of(selectedPeer);
        }

        void markAlive(String nodeId) {
            PeerState state = peerStates.get(nodeId);

            if (state != null) {
                state.markAlive();
            }
        }

        void markFailed(String nodeId) {
            PeerState state = peerStates.get(nodeId);

            if (state != null) {
                state.markFailed();
            }
        }

        PeerStatus getStatus(String nodeId) {
            PeerState state = peerStates.get(nodeId);

            if (state == null) {
                return PeerStatus.UNKNOWN;
            }

            return state.status();
        }

        List<PeerState> states() {
            return new ArrayList<>(peerStates.values());
        }
    }

    // =========================================================
    // P2P SERVER
    // Receives pings from other nodes and replies with ACK.
    // =========================================================

    static class PeerServer {

        private final String nodeId;
        private final String bindHost;
        private final int port;
        private final HttpServer server;

        PeerServer(String nodeId, String bindHost, int port) throws IOException {
            this.nodeId = nodeId;
            this.bindHost = bindHost;
            this.port = port;

            this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            this.server.createContext("/ping", this::handlePing);
            this.server.setExecutor(Executors.newFixedThreadPool(4));
        }

        void start() {
            server.start();

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "P2P server listening on "
                            + bindHost + ":" + port
            );
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

        private void sendResponse(
                HttpExchange exchange,
                int statusCode,
                String responseBody
        ) throws IOException {

            byte[] responseBytes = responseBody.getBytes();

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);

            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBytes);
            }
        }
    }

    // =========================================================
    // P2P CLIENT
    // Sends ping to another node and waits for ACK.
    // =========================================================

    static class PeerClient {

        private final String localNodeId;
        private final int ackTimeoutSeconds;
        private final HttpClient httpClient;

        PeerClient(String localNodeId, int ackTimeoutSeconds) {
            this.localNodeId = localNodeId;
            this.ackTimeoutSeconds = ackTimeoutSeconds;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(ackTimeoutSeconds))
                    .build();
        }

        CompletableFuture<Boolean> ping(PeerAddress peer) {
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
    }

    // =========================================================
    // DASHBOARD REPORTER
    // Sends reports to centralized server.
    // The server is not detecting failure.
    // It only receives reports from nodes.
    // =========================================================

    static class DashboardReporter {

        private final String localNodeId;
        private final String dashboardUrl;
        private final HttpClient httpClient;

        DashboardReporter(String localNodeId, String dashboardUrl) {
            this.localNodeId = localNodeId;
            this.dashboardUrl = removeTrailingSlash(dashboardUrl);
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        CompletableFuture<Void> reportSelfAlive(String advertiseHost, int p2pPort) {
            String json = """
                    {
                      "id": "%s",
                      "ipAddress": "%s:%d",
                      "status": "UP"
                    }
                    """.formatted(localNodeId, advertiseHost, p2pPort);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboardUrl + "/heartbeat"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> System.out.println(
                            "[" + LocalDateTime.now() + "] "
                                    + "Registered self with dashboard. Status: "
                                    + response.statusCode()
                    ))
                    .exceptionally(error -> {
                        System.out.println(
                                "[" + LocalDateTime.now() + "] "
                                        + "Could not register with dashboard: "
                                        + error.getMessage()
                        );
                        return null;
                    });
        }

        CompletableFuture<Void> reportFailure(PeerAddress failedPeer) {
            String message = "Node " + localNodeId
                    + " finds out Node " + failedPeer.nodeId()
                    + " has failed";

            String json = """
                    {
                      "reporterNodeId": "%s",
                      "failedNodeId": "%s",
                      "message": "%s",
                      "timestamp": "%s"
                    }
                    """.formatted(
                    localNodeId,
                    failedPeer.nodeId(),
                    message,
                    LocalDateTime.now()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboardUrl + "/failure-report"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> System.out.println(
                            "[" + LocalDateTime.now() + "] "
                                    + "Failure report sent to dashboard. Status: "
                                    + response.statusCode()
                    ))
                    .exceptionally(error -> {
                        System.out.println(
                                "[" + LocalDateTime.now() + "] "
                                        + "Could not report failure to dashboard: "
                                        + error.getMessage()
                        );
                        return null;
                    });
        }

        private String removeTrailingSlash(String value) {
            if (value.endsWith("/")) {
                return value.substring(0, value.length() - 1);
            }

            return value;
        }
    }

    // =========================================================
    // FAILURE DETECTOR
    // Sequential simplified SWIM-style heartbeat.
    // =========================================================

    static class FailureDetector {

        private final String localNodeId;
        private final PeerDirectory peerDirectory;
        private final PeerClient peerClient;
        private final DashboardReporter dashboardReporter;
        private final int gossipIntervalSeconds;
        private final ScheduledExecutorService scheduler;

        FailureDetector(
                String localNodeId,
                PeerDirectory peerDirectory,
                PeerClient peerClient,
                DashboardReporter dashboardReporter,
                int gossipIntervalSeconds
        ) {
            this.localNodeId = localNodeId;
            this.peerDirectory = peerDirectory;
            this.peerClient = peerClient;
            this.dashboardReporter = dashboardReporter;
            this.gossipIntervalSeconds = gossipIntervalSeconds;
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        void start() {
            scheduler.scheduleAtFixedRate(
                    this::runOneProbeSafely,
                    0,
                    gossipIntervalSeconds,
                    TimeUnit.SECONDS
            );
        }

        private void runOneProbeSafely() {
            try {
                runOneProbe();
            } catch (Exception exception) {
                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "Failure detector error: "
                                + exception.getMessage()
                );
            }
        }

        private void runOneProbe() {
            Optional<PeerAddress> selectedPeer = peerDirectory.nextPeer();

            if (selectedPeer.isEmpty()) {
                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "No peers configured. Nothing to ping."
                );
                return;
            }

            PeerAddress peer = selectedPeer.get();

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Node " + localNodeId
                            + " pings Node " + peer.nodeId()
                            + " at " + peer.host() + ":" + peer.port()
            );

            peerClient.ping(peer).thenAccept(ackReceived -> {
                if (ackReceived) {
                    peerDirectory.markAlive(peer.nodeId());

                    System.out.println(
                            "[" + LocalDateTime.now() + "] "
                                    + "ACK received from Node "
                                    + peer.nodeId()
                    );
                } else {
                    handleFailedPeer(peer);
                }

                printLocalPeerStates();
            });
        }

        private void handleFailedPeer(PeerAddress failedPeer) {
            PeerStatus previousStatus = peerDirectory.getStatus(failedPeer.nodeId());

            peerDirectory.markFailed(failedPeer.nodeId());

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Node " + localNodeId
                            + " finds out Node "
                            + failedPeer.nodeId()
                            + " has failed"
            );

            if (previousStatus != PeerStatus.FAILED) {
                dashboardReporter.reportFailure(failedPeer);
            }
        }

        private void printLocalPeerStates() {
            System.out.println("----- Local Peer States at Node " + localNodeId + " -----");

            for (PeerState state : peerDirectory.states()) {
                System.out.println(state);
            }

            System.out.println("---------------------------------------------------------");
        }
    }
}