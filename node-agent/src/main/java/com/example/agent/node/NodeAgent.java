package com.example.agent.node;

import java.io.IOException;

public class NodeAgent {

        public static void main(String[] args) throws IOException {
                AgentConfig config = AgentConfig.fromArgs(args);

                NeighborDirectory neighborDirectory = new NeighborDirectory(config.neighborList());

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
                                nodeClient);

                GossipService gossipService = new GossipService(
                                config.nodeId(),
                                neighborDirectory,
                                nodeClient,
                                config.gossipTtl());

                nodeServer.setGossipService(gossipService);

                FailureDetector failureDetector = new FailureDetector(
                                config.nodeId(),
                                neighborDirectory,
                                nodeClient,
                                dashboardReporter,
                                phiDetector,
                                gossipService,
                                config.probeIntervalSeconds(),
                                config.unreachableThreshold());

                nodeServer.start();

                dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

                failureDetector.start();

                printStartupInfo(config);
        }

        private static void printStartupInfo(AgentConfig config) {
                System.out.println("====================================");
                System.out.println("Node Agent Started");
                System.out.println("Node ID              : " + config.nodeId());
                System.out.println("Bind Address         : " + config.bindHost() + ":" + config.p2pPort());
                System.out.println("Advertise Address    : " + config.advertiseHost() + ":" + config.p2pPort());
                System.out.println("Dashboard URL        : " + config.dashboardUrl());
                System.out.println("Neighbor list        : " + config.neighborList());
                System.out.println("K known nodes        : " + config.neighborList().size());
                System.out.println("Probe interval       : " + config.probeIntervalSeconds() + " seconds");
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