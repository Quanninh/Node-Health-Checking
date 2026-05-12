package com.monitoring.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.monitoring.model.Node;
import com.monitoring.model.NodeHistory;
import com.monitoring.repository.NodeHistoryRepository;
import com.monitoring.repository.NodeRepository;

@Service
public class NodeService {

    private static final long NODE_TIMEOUT_SECONDS = 10;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private NodeHistoryRepository historyRepository;

    // Implement this method
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

    @Scheduled(fixedRate = 5000)
    public void checkNodeStatus() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(NODE_TIMEOUT_SECONDS);
        List<Node> nodes = nodeRepository.findAll();

        for (Node node : nodes) {
            LocalDateTime lastHeartbeat = node.getLastHeartbeat();

            if (lastHeartbeat != null && lastHeartbeat.isBefore(cutoffTime) && !"DOWN".equals(node.getStatus())) {
                node.setStatus("DOWN");
                nodeRepository.save(node);
            }
        }
    }
}
