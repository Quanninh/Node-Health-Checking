package com.monitoring.service;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.monitoring.model.FailureReport;
import com.monitoring.model.Node;
import com.monitoring.repository.FailureReportRepository;
import com.monitoring.repository.NodeRepository;

@Service
public class NodeService {

    private static final long NODE_TIMEOUT_SECONDS = 10;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private FailureReportRepository failureReportRepository;

    /**
     * Process heartbeat from node.
     */
    public void processHeartbeat(Node incomingNode) {

        Node node = nodeRepository.findById(incomingNode.getId()).orElse(new Node());

        node.setId(incomingNode.getId());
        node.setIpAddress(incomingNode.getIpAddress());
        node.setP2pPort(incomingNode.getP2pPort());
        node.setStatus("UP");
        node.setLastHeartbeat(LocalDateTime.now());
        node.setCrackingPort(incomingNode.getCrackingPort());
        node.setNeighbors(incomingNode.getNeighbors() != null ? incomingNode.getNeighbors() : new ArrayList<>());

        writeGraphFile();

        nodeRepository.save(node);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Heartbeat received from "
                        + node.getId());
    }

    public void writeGraphFile() {
        List<Node> nodes = nodeRepository.findAll();

        try (PrintWriter writer = new PrintWriter("graph.txt")) {

            // Write nodes
            for (Node node : nodes) {
                if (node.getStatus().equals("UP")) {
                    writer.println(node.getId());
                }
            }

            // Write edges
            for (Node node : nodes) {
                if (node.getNeighbors() == null || !node.getStatus().equals("UP")) {
                    continue;
                }

                for (String neighbor : node.getNeighbors()) {
                    writer.println(node.getId() + " " + neighbor);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Detect nodes that stopped sending heartbeats.
     */
    @Scheduled(fixedRate = 5000)
    public void checkNodeStatus() {

        try {

            LocalDateTime cutoffTime = LocalDateTime.now()
                    .minusSeconds(NODE_TIMEOUT_SECONDS);

            List<Node> nodes = nodeRepository.findAll();

            for (Node node : nodes) {

                LocalDateTime lastHeartbeat = node.getLastHeartbeat();

                if (lastHeartbeat != null
                        && lastHeartbeat.isBefore(cutoffTime)
                        && !"DOWN".equals(node.getStatus())
                        && !"FAILED".equals(node.getStatus())) {

                    node.setStatus("DOWN");

                    nodeRepository.save(node);

                    System.out.println(
                            "[" + LocalDateTime.now() + "] "
                                    + node.getId()
                                    + " marked as DOWN");
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "[" + LocalDateTime.now() + "] "
                            + "Scheduler skipped: "
                            + e.getMessage());
        }
    }

    /**
     * Process distributed failure report.
     */
    public void processFailureReport(FailureReport report) {

        if (report.getTimestamp() == null) {

            report.setTimestamp(LocalDateTime.now());
        }

        if (report.getStatus() == null
                || report.getStatus().isBlank()) {

            report.setStatus("UNREACHABLE");
        }

        if (report.getMessage() == null
                || report.getMessage().isBlank()) {

            report.setMessage(
                    "Node "
                            + report.getReporterNodeId()
                            + " detected "
                            + report.getFailedNodeId()
                            + " as unreachable");
        }

        failureReportRepository.save(report);

        Node failedNode = nodeRepository
                .findById(report.getFailedNodeId())
                .orElseGet(() -> {

                    Node node = new Node();

                    node.setId(report.getFailedNodeId());

                    return node;
                });

        failedNode.setStatus("FAILED");

        nodeRepository.save(failedNode);

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + report.getMessage());
    }

    public List<Node> getAllNodes() {
        return nodeRepository.findAll();
    }

    public Node getNodeById(String id) {

        return nodeRepository
                .findById(id)
                .orElse(null);
    }

    public List<FailureReport> getFailureReports() {

        return failureReportRepository.findAll();
    }
}