package com.monitoring.agent.node;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;

import com.monitoring.agent.node.agent.AgentConfig;
import com.monitoring.agent.node.connection.ConnectionManager;
import com.monitoring.agent.node.connection.MembershipControlService;
import com.monitoring.agent.node.connection.MulticastDiscoveryService;
import com.monitoring.agent.node.connection.MulticastJoinCoordinator;
import com.monitoring.agent.node.connection.NeighborDirectory;
import com.monitoring.agent.node.recovery.NetworkTopologyCache;
import com.monitoring.agent.node.recovery.RecoveryUDPService;
import com.monitoring.agent.node.recovery.RewiringCoordinator;
import com.monitoring.agent.node.transport.UdpCoordinator;
import com.monitoring.agent.util.Console;
import com.monitoring.agent.vaultcracking.NodeHttpServer;

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
    private final NetworkInterface multicastInterface;
    private final DiscoveryConfig discoveryConfig;
    private final MulticastDiscoveryService discoveryService;
    private final UdpCoordinator udpCoordinator;
    private final MembershipControlService membershipControlService;
    private final MulticastJoinCoordinator joinCoordinator;

    private final RecoveryUDPService recoveryUdpService;
    private final NetworkTopologyCache repairCache;
    private final RewiringCoordinator rewiringCoordinator;
    private final NodeHttpServer crackingServer;

    /**
     * Constructor for NodeAgent from command line arguments.
     * 
     * @param args command line arguments
     * @throws Exception
     */
    public NodeAgent(String[] args) throws Exception {
        config = AgentConfig.fromArgs(args);

        Console.setNodeId(config.nodeId());

        nodeClient = new NodeClient(config.nodeId(), config.ackTimeoutSeconds());

        nodeServer = new NodeServer(
                config.nodeId(),
                config.bindHost(),
                config.p2pPort(),
                nodeClient);

        localAddress = new NodeAddress(
                config.nodeId(),
                config.advertiseHost(),
                nodeServer.getPort());

        connectionManager = new ConnectionManager(
                localAddress,
                config.maxNeighbors());

        dashboardReporter = new DashboardReporter(config.nodeId(), config.dashboardUrl());

        phiDetector = new PhiAccrualFailure(
                config.phiWindowSize(),
                config.warningThreshold(),
                config.suspectedThreshold(),
                config.unreachableThreshold(),
                config.minStdDeviation(),
                config.minProbability());

        multicastInterface = resolveMulticastInterface();

        discoveryConfig = new DiscoveryConfig(
                InetAddress.getByName(config.multicastGroup()),
                config.multicastPort(),
                multicastInterface,
                config.maxNeighbors(),
                config.discoveryRetryCount(),
                Duration.ofMillis(config.discoveryRetryIntervalMs()),
                Duration.ofMillis(config.discoveryCollectionWindowMs()),
                8192);

        discoveryService = new MulticastDiscoveryService(
                localAddress,
                discoveryConfig,
                connectionManager);

        // Create a single UDP Coordinator for both membership and recovery services
        udpCoordinator = new UdpCoordinator(nodeServer.getPort(), discoveryConfig.packetBufferSize());

        membershipControlService = new MembershipControlService(
                localAddress,
                connectionManager,
                udpCoordinator);

        joinCoordinator = new MulticastJoinCoordinator(
                localAddress,
                config.maxNeighbors(),
                connectionManager,
                discoveryService,
                membershipControlService);

        repairCache = new NetworkTopologyCache();

        rewiringCoordinator = new RewiringCoordinator(localAddress, connectionManager,
                udpCoordinator);

        recoveryUdpService = new RecoveryUDPService(localAddress, repairCache, connectionManager,
                udpCoordinator,
                rewiringCoordinator);

        neighborDirectory = new NeighborDirectory(connectionManager);

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
                recoveryUdpService,
                config.probeIntervalSeconds(),
                config.unreachableThreshold());

        crackingServer = new NodeHttpServer(config.nodeId(), config.crackingPort());

        nodeServer.start();
        crackingServer.start();

        // Start UDP coordinator before services
        udpCoordinator.start();

        membershipControlService.start();

        discoveryService.startResponder();

        rewiringCoordinator.start();
        recoveryUdpService.start();

        joinCoordinator.joinNetwork();

        dashboardReporter.reportSelfAlive(config.advertiseHost(), nodeServer.getPort(), crackingServer.getPort());

        failureDetector.start();

        printStartupInfo();
    }

    /**
     * Takes the multicast interface from the agent configuration and finds the
     * interface. The popular interface on MacOS is en0 and on Windows is
     * wireless_32768.
     * 
     * @return a network interface
     * @throws Exception when the interface is not found
     */
    private NetworkInterface resolveMulticastInterface() throws Exception {
        if (!config.multicastInterfaceName().isBlank()) {
            NetworkInterface networkInterface = NetworkInterface.getByName(config.multicastInterfaceName());

            if (networkInterface == null) {
                throw new IllegalArgumentException("No network interface found with name: "
                        + config.multicastInterfaceName());
            }

            return networkInterface;
        }

        InetAddress advertiseAddress = InetAddress.getByName(config.advertiseHost());

        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(advertiseAddress);

        if (networkInterface == null) {
            throw new IllegalArgumentException("Could not resolve network interface for advertiseHost: "
                    + config.advertiseHost()
                    + ". Try passing --multicast-interface manually, e.g. en0 on macOS and wireless_32768 for Windows.");
        }

        return networkInterface;
    }

    private void printStartupInfo() {
        Console.println("====================================");
        Console.println("Node Agent Started");
        Console.println("Node ID                    : " + config.nodeId());
        Console.println("Bind Address               : " + config.bindHost() + ":" + config.p2pPort());
        Console.println("Advertise Address          : " + config.advertiseHost() + ":" + nodeServer.getPort());
        Console.println("Dashboard URL              : " + config.dashboardUrl());
        Console.println("Discovery Mode             : UDP Multicast");
        Console.println("Multicast Group            : " + config.multicastGroup());
        Console.println("Multicast Port             : " + config.multicastPort());
        Console.println("Multicast Interface        : "
                + (config.multicastInterfaceName().isBlank() ? "auto"
                        : config.multicastInterfaceName()));
        Console.println("Current neighbors          : " + neighborDirectory.addresses());
        Console.println("Current neighbor count     : " + connectionManager.size());
        Console.println("Max neighbors n            : " + connectionManager.getMaxNeighbors());
        Console.println("Discovery retry count      : " + config.discoveryRetryCount());
        Console.println("Discovery retry interval   : " + config.discoveryRetryIntervalMs() + " ms");
        Console.println("Discovery collection window: " + config.discoveryCollectionWindowMs() + " ms");
        Console.println("Probe interval             : " + config.probeIntervalSeconds() + " seconds");
        Console.println("ACK timeout                : " + config.ackTimeoutSeconds() + " seconds");
        Console.println("Phi window size            : " + config.phiWindowSize());
        Console.println("Phi thresholds             : WARNING=" + config.warningThreshold()
                + ", SUSPECTED=" + config.suspectedThreshold()
                + ", UNREACHABLE=" + config.unreachableThreshold());
        Console.println("Min std deviation          : " + config.minStdDeviation());
        Console.println("Min probability            : " + config.minProbability());
        Console.println("Cracking HTTP Port        : " + crackingServer.getPort());
        Console.println("====================================");
    }
}
