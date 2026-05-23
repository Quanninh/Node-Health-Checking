package com.monitoring.ServerApplication;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.monitoring.model.Node;
import com.monitoring.repository.NodeRepository;
import com.monitoring.service.NodeService;

@SpringBootTest
class ServerApplicationTests {

    @Autowired
    private NodeService nodeService;

    @Autowired
    private NodeRepository nodeRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void checkNodeStatusMarksOldHeartbeatAsDown() {

        nodeRepository.deleteAll();

        Node activeNode = new Node();

        activeNode.setId("active-node");

        activeNode.setIpAddress("192.168.1.10:9000");

        activeNode.setLastHeartbeat(LocalDateTime.now());

        activeNode.setStatus("UP");

        Node staleNode = new Node();

        staleNode.setId("stale-node");

        staleNode.setIpAddress("192.168.1.11:9000");

        staleNode.setLastHeartbeat(
                LocalDateTime.now().minusSeconds(30)
        );

        staleNode.setStatus("UP");

        nodeRepository.save(activeNode);

        nodeRepository.save(staleNode);

        nodeService.checkNodeStatus();

        Node updatedActiveNode =
                nodeRepository
                        .findById("active-node")
                        .orElseThrow();

        Node updatedStaleNode =
                nodeRepository
                        .findById("stale-node")
                        .orElseThrow();

        assertEquals(
                "UP",
                updatedActiveNode.getStatus()
        );

        assertEquals(
                "DOWN",
                updatedStaleNode.getStatus()
        );
    }
}