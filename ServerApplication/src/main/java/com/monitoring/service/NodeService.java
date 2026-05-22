package com.monitoring.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.monitoring.model.FailureReport;
import com.monitoring.model.Node;
import com.monitoring.model.NodeHistory;
import com.monitoring.repository.FailureReportRepository;
import com.monitoring.repository.HistoryRepository;
import com.monitoring.repository.NodeRepository;

@Service
public class NodeService {

    private static final long NODE_TIMEOUT_SECONDS = 10;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private FailureReportRepository failureReportRepository;

    public void processHeartbeat(Node node) {
        node.setLastHeartbeat(LocalDateTime.now());
        node.setStatus("UP");

        nodeRepository.save(node);

        NodeHistory history = new NodeHistory();
        history.setNodeId(node.getId());
        history.setCpuUsage(node.getCpuUsage());
        history.setMemoryUsage(node.getMemoryUsage());
        history.setTimestamp(LocalDateTime.now());

        historyRepository.save(history);
    }

    public List<Node> getAllNodes() {
        return nodeRepository.findAll();
    }

    public Node getNodeById(String id) {
        return nodeRepository.findById(id).orElse(null);
    }

    public List<NodeHistory> getNodeHistory(String id) {
        return historyRepository.findByNodeId(id);
    }

    // TODO: This code was commented by someone
    //@Scheduled(fixedRate = 5000)
    public void checkNodeStatus() {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(NODE_TIMEOUT_SECONDS);
            List<Node> nodes = nodeRepository.findAll();

            for (Node node : nodes) {
                LocalDateTime lastHeartbeat = node.getLastHeartbeat();

                if (lastHeartbeat != null && lastHeartbeat.isBefore(cutoffTime) && !node.getStatus().equals("DOWN")) {
                    node.setStatus("DOWN");
                    nodeRepository.save(node);
                }
            }
        } catch (Exception e) {
            System.out.println("\n[" + LocalDateTime.now() + "] Scheduler skipped: " +
    e.getMessage());
        }
    }

    public void processFailureReport(FailureReport report) {
        if (report.getTimestamp() == null) {
            report.setTimestamp(LocalDateTime.now());
        }

        if (report.getMessage() == null || report.getMessage().isBlank()) {
            report.setMessage(
                    "Node " + report.getReporterNodeId()
                            + " finds out Node " + report.getFailedNodeId()
                            + " has failed");
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
        failedNode.setLastHeartbeat(LocalDateTime.now());

        nodeRepository.save(failedNode);

        System.out.println(
                "\n[" + LocalDateTime.now() + "] "
                        + report.getMessage());
    }

    public List<FailureReport> getFailureReports() {
        return failureReportRepository.findAll();
    }
}
