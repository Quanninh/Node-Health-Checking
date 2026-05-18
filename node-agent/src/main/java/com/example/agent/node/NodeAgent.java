package com.example.agent.node;
import java.io.IOException;

public class NodeAgent {

    public static void main(String[] args) throws IOException {
        AgentConfig config = AgentConfig.fromArgs(args);

        PeerDirectory peerDirectory = new PeerDirectory(config.peers());
        DashboardReporter dashboardReporter = new DashboardReporter(
                config.nodeId(),
                config.dashboardUrl()
        );

        PeerServer peerServer = new PeerServer(
                config.nodeId(),
                config.bindHost(),
                config.p2pPort()
        );

        PeerClient peerClient = new PeerClient(
                config.nodeId(),
                config.ackTimeoutSeconds()
        );

        FailureDetector failureDetector = new FailureDetector(
                config.nodeId(),
                peerDirectory,
                peerClient,
                dashboardReporter,
                config.gossipIntervalSeconds()
        );

        peerServer.start();

        dashboardReporter.reportSelfAlive(config.advertiseHost(), config.p2pPort());

        failureDetector.start();

        printStartupInfo(config);
    }

    private static void printStartupInfo(AgentConfig config) {
        System.out.println("====================================");
        System.out.println("Node Agent Started");
        System.out.println("Node ID          : " + config.nodeId());
        System.out.println("Bind Address     : " + config.bindHost() + ":" + config.p2pPort());
        System.out.println("Advertise Address: " + config.advertiseHost() + ":" + config.p2pPort());
        System.out.println("Dashboard URL    : " + config.dashboardUrl());
        System.out.println("Peers            : " + config.peers());
        System.out.println("Gossip interval  : " + config.gossipIntervalSeconds() + " seconds");
        System.out.println("ACK timeout      : " + config.ackTimeoutSeconds() + " seconds");
        System.out.println("====================================");
    }
}