package com.example.agent.node;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;

public class NodeAgent {

    public static void main(String[] args) throws Exception {
        AgentConfig config = AgentConfig.fromArgs(args);

        NodeAddress localAddress = new NodeAddress(
                config.nodeId(),
                config.advertiseHost(),
                config.p2pPort());

        ConnectionManager connectionManager = new ConnectionManager(
                localAddress,
                config.maxNeighbors());

        NeighborDirectory neighborDirectory = new NeighborDirectory(connectionManager);

        DashboardReporter dashboardReporter = new DashboardReporter(
                config.nodeId(),
                config.dashboardUrl());

        PhiAccrualFailureDetector phiDetector = new PhiAccrualFailureDetector(
                config.phiWindowSize(),
                config.warningThreshold(),
                config.suspectedThreshold(),
                config.unreachableThreshold(),
                config.minStdDeviation(),
                config.minProbability());

        NodeClient peerClient = new NodeClient(
                config.nodeId(),
                config.ackTimeoutSeconds());

        PeerServer peerServer = new PeerServer(
                config.nodeId(),
                config.bindHost(),
                config.p2pPort(),
                peerClient);

        FailureDetector failureDetector = new FailureDetector(
                config.nodeId(),
                neighborDirectory,
                peerClient,
                dashboardReporter,
                phiDetector,
                config.gossipIntervalSeconds());

        NetworkInterface multicastInterface = resolveMulticastInterface(config);

        DiscoveryConfig discoveryConfig = new DiscoveryConfig(
                InetAddress.getByName(config.multicastGroup()),
                config.multicastPort(),
                multicastInterface,
                config.maxNeighbors(),
                config.discoveryRetryCount(),
                Duration.ofMillis(config.discoveryRetryIntervalMillis()),
                Duration.ofMillis(config.discoveryCollectionWindowMillis()),
                8192);

        MulticastDiscoveryService discoveryService = new MulticastDiscoveryService(
                localAddress,
                discoveryConfig,
                connectionManager);

        MembershipControlService membershipControlService = new MembershipControlService(
                localAddress,
                connectionManager,
                config.p2pPort(),
                discoveryConfig.packetBufferSize());

        MulticastJoinCoordinator joinCoordinator = new MulticastJoinCoordinator(
                localAddress,
                config.maxNeighbors(),
                connectionManager,
                discoveryService,
                membershipControlService);

        peerServer.start();

        membershipControlService.start();

        discoveryService.startResponder();

        joinCoordinator.joinNetwork();

        dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

        failureDetector.start();

        printStartupInfo(config, neighborDirectory);
    }

    private static NetworkInterface resolveMulticastInterface(AgentConfig config) throws Exception {
        if (!config.multicastInterfaceName().isBlank()) {
            NetworkInterface networkInterface = NetworkInterface.getByName(config.multicastInterfaceName());

            if (networkInterface == null) {
                throw new IllegalArgumentException(
                        "No network interface found with name: " + config.multicastInterfaceName());
            }

            return networkInterface;
        }

        InetAddress advertiseAddress = InetAddress.getByName(config.advertiseHost());

        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(advertiseAddress);

        if (networkInterface == null) {
            throw new IllegalArgumentException(
                    "Could not resolve network interface for advertiseHost: " + config.advertiseHost()
                            + ". Try passing --multicast-interface manually, e.g. en0 on macOS.");
        }

        return networkInterface;
    }

    private static void printStartupInfo(
            AgentConfig config,
            NeighborDirectory neighborDirectory
    ) {
        System.out.println("====================================");
        System.out.println("Node Agent Started");
        System.out.println("Node ID                    : " + config.nodeId());
        System.out.println("Bind Address               : " + config.bindHost() + ":" + config.p2pPort());
        System.out.println("Advertise Address          : " + config.advertiseHost() + ":" + config.p2pPort());
        System.out.println("Dashboard URL              : " + config.dashboardUrl());

        System.out.println("Discovery Mode             : UDP Multicast");
        System.out.println("Multicast Group            : " + config.multicastGroup());
        System.out.println("Multicast Port             : " + config.multicastPort());
        System.out.println("Multicast Interface        : "
                + (config.multicastInterfaceName().isBlank()
                ? "auto"
                : config.multicastInterfaceName()));

        System.out.println("Current neighbors          : " + neighborDirectory.addresses());
        System.out.println("Current neighbor count     : " + neighborDirectory.size());
        System.out.println("Max neighbors n            : " + config.maxNeighbors());

        System.out.println("Discovery retry count      : " + config.discoveryRetryCount());
        System.out.println("Discovery retry interval   : " + config.discoveryRetryIntervalMillis() + " ms");
        System.out.println("Discovery collection window: " + config.discoveryCollectionWindowMillis() + " ms");

        System.out.println("Probe interval             : " + config.gossipIntervalSeconds() + " seconds");
        System.out.println("ACK timeout                : " + config.ackTimeoutSeconds() + " seconds");
        System.out.println("Phi window size            : " + config.phiWindowSize());
        System.out.println("Phi thresholds             : WARNING=" + config.warningThreshold()
                + ", SUSPECTED=" + config.suspectedThreshold()
                + ", UNREACHABLE=" + config.unreachableThreshold());
        System.out.println("Min std deviation          : " + config.minStdDeviation());
        System.out.println("Min probability            : " + config.minProbability());
        System.out.println("====================================");
    }
}