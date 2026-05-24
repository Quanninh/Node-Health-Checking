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
import com.monitoring.agent.node.recovery.ConvergenceMonitor;
import com.monitoring.agent.node.recovery.DirectRepairCoordinator;
import com.monitoring.agent.node.recovery.EdgeLockManager;
import com.monitoring.agent.node.recovery.FailureRecoveryManager;
import com.monitoring.agent.node.recovery.NetworkTopologyCache;
import com.monitoring.agent.node.recovery.RecoveryControlService;
import com.monitoring.agent.node.recovery.RecoveryCoordinator;
import com.monitoring.agent.node.recovery.RecoveryUDPService;
import com.monitoring.agent.node.recovery.RewiringCoordinator;
import com.monitoring.agent.util.Console;

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
    private final MembershipControlService membershipControlService;
    private final MulticastJoinCoordinator joinCoordinator;

    private final RecoveryUDPService recoveryUdpService;
    private final RecoveryControlService recoveryControlService;
    private final NetworkTopologyCache repairCache;
    private final DirectRepairCoordinator directRepairCoordinator;
    private final ConvergenceMonitor convergenceMonitor;
    private final EdgeLockManager edgeLockManager;
    private final RewiringCoordinator rewiringCoordinator;
    private final RecoveryCoordinator recoveryCoordinator;
    private final FailureRecoveryManager failureRecoveryManager;

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
                config.maxNeighbors());

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

        multicastInterface = resolveMulticastInterface();

        discoveryConfig = new DiscoveryConfig(
                InetAddress.getByName(config.multicastGroup()),
                config.multicastPort(),
                multicastInterface,
                config.maxNeighbors(),
                config.discoveryRetryCount(),
                Duration.ofMillis(config.discoveryRetryIntervalMillis()),
                Duration.ofMillis(config.discoveryCollectionWindowMillis()),
                8192);

        discoveryService = new MulticastDiscoveryService(
                localAddress,
                discoveryConfig,
                connectionManager);

        membershipControlService = new MembershipControlService(
                localAddress,
                connectionManager,
                config.p2pPort(),
                discoveryConfig.packetBufferSize());

        joinCoordinator = new MulticastJoinCoordinator(
                localAddress,
                config.maxNeighbors(),
                connectionManager,
                discoveryService,
                membershipControlService);

        recoveryUdpService = new RecoveryUDPService();

        recoveryControlService = new RecoveryControlService(localAddress, connectionManager, recoveryUdpService);

        repairCache = new NetworkTopologyCache();

        directRepairCoordinator = new DirectRepairCoordinator(repairCache);

        edgeLockManager = new EdgeLockManager();

        rewiringCoordinator = new RewiringCoordinator(connectionManager, repairCache, edgeLockManager);

        convergenceMonitor = new ConvergenceMonitor(connectionManager);

        recoveryCoordinator = new RecoveryCoordinator(localAddress, connectionManager, recoveryControlService,
                repairCache, directRepairCoordinator, rewiringCoordinator, convergenceMonitor);

        failureRecoveryManager = new FailureRecoveryManager(connectionManager, recoveryCoordinator);
        neighborDirectory = new NeighborDirectory(connectionManager, failureRecoveryManager);

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

        nodeServer.start();

        membershipControlService.start();

        discoveryService.startResponder();

        joinCoordinator.joinNetwork();

        dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

        failureDetector.start();

        printStartupInfo();
    }

    private NetworkInterface resolveMulticastInterface() throws Exception {
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
        Console.println("====================================");
        Console.println("Node Agent Started");
        Console.println("Node ID                    : " + config.nodeId());
        Console.println("Bind Address               : " + config.bindHost() + ":" + config.p2pPort());
        Console.println("Advertise Address          : " + config.advertiseHost() + ":" + config.p2pPort());
        Console.println("Dashboard URL              : " + config.dashboardUrl());
        Console.println("Discovery Mode             : UDP Multicast");
        Console.println("Multicast Group            : " + config.multicastGroup());
        Console.println("Multicast Port             : " + config.multicastPort());
        Console.println("Multicast Interface        : "
                + (config.multicastInterfaceName().isBlank() ? "auto" : config.multicastInterfaceName()));
        Console.println("Current neighbors          : " + neighborDirectory.addresses());
        Console.println("Current neighbor count     : " + connectionManager.size());
        Console.println("Max neighbors n            : " + connectionManager.getMaxNeighbors());
        Console.println("Discovery retry count      : " + config.discoveryRetryCount());
        Console.println("Discovery retry interval   : " + config.discoveryRetryIntervalMillis() + " ms");
        Console.println("Discovery collection window: " + config.discoveryCollectionWindowMillis() + " ms");
        Console.println("Probe interval             : " + config.probeIntervalSeconds() + " seconds");
        Console.println("ACK timeout                : " + config.ackTimeoutSeconds() + " seconds");
        Console.println("Phi window size            : " + config.phiWindowSize());
        Console.println("Phi thresholds             : WARNING=" + config.warningThreshold()
                + ", SUSPECTED=" + config.suspectedThreshold()
                + ", UNREACHABLE=" + config.unreachableThreshold());
        Console.println("Min std deviation          : " + config.minStdDeviation());
        Console.println("Min probability            : " + config.minProbability());
        Console.println("====================================");
    }
}