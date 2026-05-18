package com.example.agent.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final int DEFAULT_GOSSIP_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_ACK_TIMEOUT_SECONDS = 10;

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
