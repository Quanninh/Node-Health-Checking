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
                                localAddress,
                                peerClient,
                                neighborDirectory,
                                failureEventLog);

                FailureDetector failureDetector = new FailureDetector(
                                config.nodeId(),
                                neighborDirectory,
                                peerClient,
                                dashboardReporter,
                                phiDetector,
                                config.gossipIntervalSeconds(),
                                failureEventLog,
                                config.unreachableThreshold());

                JoinCoordinator joinCoordinator = new JoinCoordinator(
                                localAddress,
                                neighborDirectory,
                                peerClient,
                                config.bootstrapPeers(),
                                config.maxNeighbors(),
                                config.joinTimeoutSeconds(),
                                config.joinMinProbability(),
                                config.joinMaxProbability());

                peerServer.start();

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
                System.out.println("Phi window size      : " + config.phiWindowSize());
                System.out.println("Phi thresholds       : WARNING=" + config.warningThreshold()
                                + ", SUSPECTED=" + config.suspectedThreshold()
                                + ", UNREACHABLE=" + config.unreachableThreshold());
                System.out.println("Min std deviation    : " + config.minStdDeviation());
                System.out.println("Min probability      : " + config.minProbability());
                System.out.println("====================================");
        }
}