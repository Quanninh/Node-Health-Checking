package com.example.agent.node;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.agent.constant.Constant.DEFAULT_ACK_TIMEOUT_SECONDS;
import static com.example.agent.constant.Constant.DEFAULT_GOSSIP_INTERVAL_SECONDS;
import static com.example.agent.constant.Constant.DEFAULT_MAX_NEIGHBORS;
import static com.example.agent.constant.Constant.DEFAULT_MIN_PROBABILITY;
import static com.example.agent.constant.Constant.DEFAULT_MIN_STD_DEVIATION;
import static com.example.agent.constant.Constant.DEFAULT_PHI_WINDOW_SIZE;
import static com.example.agent.constant.Constant.DEFAULT_SUSPECTED_THRESHOLD;
import static com.example.agent.constant.Constant.DEFAULT_UNREACHABLE_THRESHOLD;
import static com.example.agent.constant.Constant.DEFAULT_WARNING_THRESHOLD;

public record AgentConfig(
        String nodeId,
        String bindHost,
        String advertiseHost,
        int p2pPort,
        String dashboardUrl,

        int maxNeighbors,

        String multicastGroup,
        int multicastPort,
        String multicastInterfaceName,
        int discoveryRetryCount,
        int discoveryRetryIntervalMillis,
        int discoveryCollectionWindowMillis,

        int gossipIntervalSeconds,
        int ackTimeoutSeconds,
        int phiWindowSize,
        double warningThreshold,
        double suspectedThreshold,
        double unreachableThreshold,
        double minStdDeviation,
        double minProbability
) {

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

        int maxNeighbors = Integer.parseInt(values.getOrDefault(
                "--max-neighbors",
                String.valueOf(DEFAULT_MAX_NEIGHBORS)));

        String multicastGroup = values.getOrDefault(
                "--multicast-group",
                "239.10.20.30");

        int multicastPort = Integer.parseInt(values.getOrDefault(
                "--multicast-port",
                "50505"));

        String multicastInterfaceName = values.getOrDefault(
                "--multicast-interface",
                "");

        int discoveryRetryCount = Integer.parseInt(values.getOrDefault(
                "--discovery-retry-count",
                "3"));

        int discoveryRetryIntervalMillis = Integer.parseInt(values.getOrDefault(
                "--discovery-retry-interval-ms",
                "400"));

        int discoveryCollectionWindowMillis = Integer.parseInt(values.getOrDefault(
                "--discovery-collection-window-ms",
                "3000"));

        int gossipIntervalSeconds = Integer.parseInt(values.getOrDefault(
                "--probe-interval-seconds",
                values.getOrDefault("--gossip-interval-seconds",
                        String.valueOf(DEFAULT_GOSSIP_INTERVAL_SECONDS))));

        int ackTimeoutSeconds = Integer.parseInt(values.getOrDefault(
                "--ack-timeout-seconds",
                String.valueOf(DEFAULT_ACK_TIMEOUT_SECONDS)));

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

        return new AgentConfig(
                nodeId,
                bindHost,
                advertiseHost,
                p2pPort,
                dashboardUrl,
                maxNeighbors,
                multicastGroup,
                multicastPort,
                multicastInterfaceName,
                discoveryRetryCount,
                discoveryRetryIntervalMillis,
                discoveryCollectionWindowMillis,
                gossipIntervalSeconds,
                ackTimeoutSeconds,
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
}