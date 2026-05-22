package com.example.agent.node;

import java.io.IOException;
import java.util.List;

public class NodeAgent {

        public static void main(String[] args) throws IOException {
                // Shared local failure log used by both:
                // 1. FailureDetector to write local failure events
                // 2. PeerServer to expose those events to dashboard/observers
                FailureEventLog failureEventLog = new FailureEventLog();

                AgentConfig config = AgentConfig.fromArgs(args);

                NodeAddress localAddress = new NodeAddress(
                                config.nodeId(),
                                config.advertiseHost(),
                                config.p2pPort());

                NeighborDirectory neighborDirectory = new NeighborDirectory(List.of(), config.maxNeighbors());

                DashboardReporter dashboardReporter = new DashboardReporter(
                                config.nodeId(),
                                config.dashboardUrl());

                PhiAccrualFailure phiDetector = new PhiAccrualFailure(
                                config.phiWindowSize(),
                                config.warningThreshold(),
                                config.suspectedThreshold(),
                                config.unreachableThreshold(),
                                config.minStdDeviation(),
                                config.minProbability());

                NodeClient nodeClient = new NodeClient(
                                config.nodeId(),
                                config.ackTimeoutSeconds());

                NodeServer nodeServer = new NodeServer(
                                config.nodeId(),
                                config.bindHost(),
                                config.p2pPort(),
                                localAddress,
                                nodeClient,
                                neighborDirectory,
                                failureEventLog);

                GossipService gossipService = new GossipService(
                                config.nodeId(),
                                neighborDirectory,
                                nodeClient,
                                config.gossipTtl());

                FailureDetector failureDetector = new FailureDetector(
                                config.nodeId(),
                                neighborDirectory,
                                nodeClient,
                                dashboardReporter,
                                phiDetector,
                                config.gossipIntervalSeconds(),
                                failureEventLog,
                                config.unreachableThreshold(),
                                gossipService);

                JoinCoordinator joinCoordinator = new JoinCoordinator(
                                localAddress,
                                neighborDirectory,
                                nodeClient,
                                config.bootstrapPeers(),
                                config.maxNeighbors(),
                                config.joinTimeoutSeconds(),
                                config.joinMinProbability(),
                                config.joinMaxProbability());

                nodeServer.start();

                joinCoordinator.joinNetwork();

                dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

                failureDetector.start();

                printStartupInfo(config, neighborDirectory);
        }

        private static void printStartupInfo(AgentConfig config, NeighborDirectory neighborDirectory) {
                System.out.println("====================================");
                System.out.println("Node Agent Started");
                System.out.println("Node ID              : " + config.nodeId());
                System.out.println("Bind Address         : " + config.bindHost() + ":" + config.p2pPort());
                System.out.println("Advertise Address    : " + config.advertiseHost() + ":" + config.p2pPort());
                System.out.println("Dashboard URL        : " + config.dashboardUrl());
                System.out.println("Bootstrap peers      : " + config.bootstrapPeers());
                System.out.println("Current neighbors    : " + neighborDirectory.addresses());
                System.out.println("Max neighbors k      : " + config.maxNeighbors());
                System.out.println("Join timeout         : " + config.joinTimeoutSeconds() + " seconds");
                System.out.println("Join probability     : [" + config.joinMinProbability()
                                + ", " + config.joinMaxProbability() + "]");
                System.out.println("Probe interval       : " + config.gossipIntervalSeconds() + " seconds");
                System.out.println("ACK timeout          : " + config.ackTimeoutSeconds() + " seconds");
                System.out.println("Gossip TTL           : " + config.gossipTtl());
                System.out.println("Phi window size      : " + config.phiWindowSize());
                System.out.println("Phi thresholds       : WARNING=" + config.warningThreshold()
                                + ", SUSPECTED=" + config.suspectedThreshold()
                                + ", UNREACHABLE=" + config.unreachableThreshold());
                System.out.println("Min std deviation    : " + config.minStdDeviation());
                System.out.println("Min probability      : " + config.minProbability());
                System.out.println("====================================");
        }
}