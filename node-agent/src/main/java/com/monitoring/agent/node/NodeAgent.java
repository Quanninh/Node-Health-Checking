package com.monitoring.agent.node;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;

import com.monitoring.agent.constant.Constant;

/**
 * A single agent. It is capable of checking its neighbors' status.
 */
public class NodeAgent {

    private final AgentConfig config;
    private final NodeAddress localAddress;
    private final ConnectionManager connectionManager;
    private final NeighborDirectory neighborDirectory;
    private final DashboardReporter dashboardReporter;
    private final PhiAccrualFailure phiDetector;
    private final NodeClient nodeClient;
    private final NodeServer nodeServer;
    private final GossipService gossipService;
    private final FailureDetector failureDetector;

    /**
     * Constructor for NodeAgent from command line arguments.
     * 
     * @param args command line arguments
 * @throws Exception 
     */
    public NodeAgent(String[] args) throws Exception {
        config = AgentConfig.fromArgs(args);

         localAddress = new NodeAddress(
                config.nodeId(),
                config.advertiseHost(),
                config.p2pPort());

         connectionManager = new ConnectionManager(
                localAddress,
                Constant.DEFAULT_MAX_NEIGHBORS);

        neighborDirectory = new NeighborDirectory(connectionManager);

        dashboardReporter = new DashboardReporter(config.nodeId(), config.dashboardUrl());

        phiDetector = new PhiAccrualFailure(
                config.phiWindowSize(),
                config.warningThreshold(),
                config.suspectedThreshold(),
                config.unreachableThreshold(),
                config.minStdDeviation(),
                config.minProbability());

        nodeClient = new NodeClient(config.nodeId(), config.ackTimeoutSeconds());

        nodeServer = new NodeServer(
                config.nodeId(),
                config.bindHost(),
                config.p2pPort(),
                nodeClient);

        gossipService = new GossipService(
                config.nodeId(),
                neighborDirectory,
                nodeClient,
                config.gossipTtl(), connectionManager);

        nodeServer.setGossipService(gossipService);

        failureDetector = new FailureDetector(
                config.nodeId(),
                neighborDirectory,
                nodeClient,
                dashboardReporter,
                phiDetector,
                gossipService,
                config.probeIntervalSeconds(),
                config.unreachableThreshold());

                NetworkInterface multicastInterface = resolveMulticastInterface();

        DiscoveryConfig discoveryConfig = new DiscoveryConfig(
                InetAddress.getByName(config.multicastGroup()),
                config.multicastPort(),
                multicastInterface,
                Constant.DEFAULT_MAX_NEIGHBORS,
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
                Constant.DEFAULT_MAX_NEIGHBORS,
                connectionManager,
                discoveryService,
                membershipControlService);

        nodeServer.start();

        membershipControlService.start();

        discoveryService.startResponder();

        joinCoordinator.joinNetwork();

        dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

        failureDetector.start();

        printStartupInfo();
    }

    private  NetworkInterface resolveMulticastInterface() throws Exception {
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

    private void printStartupInfo() {
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
        System.out.println("Max neighbors n            : " + Constant.DEFAULT_MAX_NEIGHBORS);

        System.out.println("Discovery retry count      : " + config.discoveryRetryCount());
        System.out.println("Discovery retry interval   : " + config.discoveryRetryIntervalMillis() + " ms");
        System.out.println("Discovery collection window: " + config.discoveryCollectionWindowMillis() + " ms");

        System.out.println("Probe interval             : " + config.probeIntervalSeconds() + " seconds");
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