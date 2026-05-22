package com.example.agent.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.agent.constant.Constant.DEFAULT_ACK_TIMEOUT_SECONDS;
import static com.example.agent.constant.Constant.DEFAULT_GOSSIP_INTERVAL_SECONDS;
import static com.example.agent.constant.Constant.DEFAULT_GOSSIP_TTL;
import static com.example.agent.constant.Constant.DEFAULT_MIN_PROBABILITY;
import static com.example.agent.constant.Constant.DEFAULT_MIN_STD_DEVIATION;
import static com.example.agent.constant.Constant.DEFAULT_PHI_WINDOW_SIZE;
import static com.example.agent.constant.Constant.DEFAULT_SUSPECTED_THRESHOLD;
import static com.example.agent.constant.Constant.DEFAULT_UNREACHABLE_THRESHOLD;
import static com.example.agent.constant.Constant.DEFAULT_WARNING_THRESHOLD;

/**
 * Configuration settings for an agent.
 *
 * @param nodeId               unique ID of the node
 * @param bindHost             local network interface/IP address the node
 *                             listens on
 * @param advertiseHost        IP address that this node tells its peers to
 *                             use when reaching back to it (self-advertisement
 *                             address)
 * @param p2pPort              port dedicated to P2P node-to-node
 *                             communication (Gossip/SWIM)
 * @param dashboardUrl         API endpoint of the centralized server used
 *                             for demo, testing, and state visualization
 * @param neighborList         comma-separated list of known bootstrap peers
 *                             formatted as ID@IP:PORT
 * @param probeIntervalSeconds interval (in seconds) between gossip/probe
 *                             messages
 * @param ackTimeoutSeconds    timeout (in seconds) for ACK responses from peer
 *                             nodes
 * @param gossipTtl            gossip time to live (max hops)
 * @param phiWindowSize        size of sliding window for phi accrual failure
 *                             detection
 * @param warningThreshold     phi threshold for warning state
 * @param suspectedThreshold   phi threshold for suspected failure state
 * @param unreachableThreshold phi threshold for unreachable/failed state
 * @param minStdDeviation      minimum standard deviation for phi calculations
 * @param minProbability       minimum probability for phi calculations
 */
public record AgentConfig(
        String nodeId,
        String bindHost,
        String advertiseHost,
        int p2pPort,
        String dashboardUrl,
        List<NodeAddress> neighborList,
        int probeIntervalSeconds,
        int ackTimeoutSeconds,
        int gossipTtl,
        int phiWindowSize,
        double warningThreshold,
        double suspectedThreshold,
        double unreachableThreshold,
        double minStdDeviation,
        double minProbability) {

    /**
     * Converts command line arguments into agent configurations.
     * 
     * @param args the command line arguments
     * @return agent configurations
     */
    public static AgentConfig fromArgs(String[] args) {
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

        int probeIntervalSeconds = Integer.parseInt(values.getOrDefault(
                "--probe-interval-seconds",
                values.getOrDefault("--gossip-interval-seconds",
                        String.valueOf(DEFAULT_GOSSIP_INTERVAL_SECONDS))));

        int ackTimeoutSeconds = Integer.parseInt(values.getOrDefault(
                "--ack-timeout-seconds",
                String.valueOf(DEFAULT_ACK_TIMEOUT_SECONDS)));

        int gossipTtl = Integer.parseInt(values.getOrDefault(
                "--gossip-ttl",
                String.valueOf(DEFAULT_GOSSIP_TTL)));

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

        List<NodeAddress> neighborList = parseNeighborList(values.getOrDefault("--neighbors", ""));

        return new AgentConfig(
                nodeId,
                bindHost,
                advertiseHost,
                p2pPort,
                dashboardUrl,
                neighborList,
                probeIntervalSeconds,
                ackTimeoutSeconds,
                gossipTtl,
                phiWindowSize,
                warningThreshold,
                suspectedThreshold,
                unreachableThreshold,
                minStdDeviation,
                minProbability);
    }

    /**
     * Parses the command line arguments into a map for each config-value pair.
     * 
     * @param args the command line arguments
     * @return the mapping for each configuration
     */
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

    private static List<NodeAddress> parseNeighborList(String rawNeighbors) {
        List<NodeAddress> neighborList = new ArrayList<>();

        if (rawNeighbors == null || rawNeighbors.isBlank()) {
            return neighborList;
        }

        String[] nodeTokens = rawNeighbors.split(",");

        for (String token : nodeTokens) {
            String trimmed = token.trim();

            if (trimmed.isBlank()) {
                continue;
            }

            neighborList.add(NodeAddress.from(trimmed));
        }

        return neighborList;
    }
}