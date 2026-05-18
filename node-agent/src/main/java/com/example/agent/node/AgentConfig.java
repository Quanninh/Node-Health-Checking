package com.example.agent.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static com.example.agent.constant.Constant.*;

public record AgentConfig(
        String nodeId,
        String bindHost,
        String advertiseHost,
        int p2pPort,
        String dashboardUrl,
        List<PeerAddress> peers,
        int gossipIntervalSeconds,
        int ackTimeoutSeconds,
        int kHelpers,
        int phiWindowSize,
        double warningThreshold,
        double suspectedThreshold,
        double unreachableThreshold,
        double minStdDeviation,
        double minProbability) {

    static AgentConfig fromArgs(String[] args) {
        Map<String, String> values = parseArgs(args);

        String nodeId = values.getOrDefault(
                "--node-id",
                "node-" + UUID.randomUUID().toString().substring(0, 8));

        String bindHost = values.getOrDefault("--bind-host", "127.0.0.1");
        String advertiseHost = values.getOrDefault("--advertise-host", bindHost);

        int p2pPort = Integer.parseInt(values.getOrDefault("--p2p-port", "9001"));

        String dashboardUrl = values.getOrDefault(
                "--dashboard-url",
                "http://localhost:6789/api");

        int gossipIntervalSeconds = Integer.parseInt(values.getOrDefault(
                "--gossip-interval-seconds",
                String.valueOf(DEFAULT_GOSSIP_INTERVAL_SECONDS)));

        int ackTimeoutSeconds = Integer.parseInt(values.getOrDefault(
                "--ack-timeout-seconds",
                String.valueOf(DEFAULT_ACK_TIMEOUT_SECONDS)));

        int kHelpers = Integer.parseInt(values.getOrDefault(
                "--k-helpers",
                String.valueOf(DEFAULT_K_HELPERS)));

        int phiWindowSize = Integer.parseInt(values.getOrDefault(
                "--phi-window-size",
                String.valueOf(DEFAULT_PHI_WINDOW_SIZE)));

        double warningThreshold = Double.parseDouble(values.getOrDefault(
                "--phi-warning-threshold",
                String.valueOf(DEFAULT_WARNING_THRESHOLD)));

        double suspectedThreshold = Double.parseDouble(values.getOrDefault(
                "--phi-suspected-threshold",
                String.valueOf(DEFAULT_SUSPECTED_THRESHOLD)));

        double unreachableThreshold = Double.parseDouble(values.getOrDefault(
                "--phi-unreachable-threshold",
                String.valueOf(DEFAULT_UNREACHABLE_THRESHOLD)));

        double minStdDeviation = Double.parseDouble(values.getOrDefault(
                "--phi-min-std-deviation",
                String.valueOf(DEFAULT_MIN_STD_DEVIATION)));

        double minProbability = Double.parseDouble(values.getOrDefault(
                "--phi-min-probability",
                String.valueOf(DEFAULT_MIN_PROBABILITY)));

        List<PeerAddress> peers = parsePeers(values.getOrDefault("--peers", ""));

        return new AgentConfig(
                nodeId,
                bindHost,
                advertiseHost,
                p2pPort,
                dashboardUrl,
                peers,
                gossipIntervalSeconds,
                ackTimeoutSeconds,
                kHelpers,
                phiWindowSize,
                warningThreshold,
                suspectedThreshold,
                unreachableThreshold,
                minStdDeviation,
                minProbability);
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
