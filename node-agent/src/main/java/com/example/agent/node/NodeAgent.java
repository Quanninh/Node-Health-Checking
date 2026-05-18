package com.example.agent.node;

import java.io.IOException;

public class NodeAgent {

        public static void main(String[] args) throws IOException {
                AgentConfig config = AgentConfig.fromArgs(args);

                PeerDirectory peerDirectory = new PeerDirectory(config.peers());

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

                PeerClient peerClient = new PeerClient(
                                config.nodeId(),
                                config.ackTimeoutSeconds());

                PeerServer peerServer = new PeerServer(
                                config.nodeId(),
                                config.bindHost(),
                                config.p2pPort(),
                                peerClient);

                FailureDetector failureDetector = new FailureDetector(
                                config.nodeId(),
                                peerDirectory,
                                peerClient,
                                dashboardReporter,
                                phiDetector,
                                config.gossipIntervalSeconds(),
                                config.kHelpers());

                peerServer.start();

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
                System.out.println("Peers                : " + config.peers());
                System.out.println("Probe interval       : " + config.gossipIntervalSeconds() + " seconds");
                System.out.println("ACK timeout          : " + config.ackTimeoutSeconds() + " seconds");
                System.out.println("K helper nodes       : " + config.kHelpers());
                System.out.println("Phi window size      : " + config.phiWindowSize());
                System.out.println("Phi thresholds       : WARNING=" + config.warningThreshold()
                                + ", SUSPECTED=" + config.suspectedThreshold()
                                + ", UNREACHABLE=" + config.unreachableThreshold());
                System.out.println("Min std deviation    : " + config.minStdDeviation());
                System.out.println("Min probability      : " + config.minProbability());
                System.out.println("====================================");
        }
}