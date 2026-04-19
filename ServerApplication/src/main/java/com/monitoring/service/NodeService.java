package com.monitoring.service;

import com.monitoring.model.Node;
import com.monitoring.model.NodeHistory;
import com.monitoring.repository.NodeRepository;
import com.monitoring.repository.NodeHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service

public class NodeService {

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private NodeHistoryRepository historyRepository;
    public void processHeartbeat(Node node){}

    public List<Node> getAllNodes(){
        return nodeRepository.findAll();
    }

    public Node getNodeById(String id){
        return nodeRepository.findById(id).orElse(null);
    }

    public List<NodeHistory> getNodeHistory(String id){
        return historyRepository.findByNodeId(id);
    }
}
