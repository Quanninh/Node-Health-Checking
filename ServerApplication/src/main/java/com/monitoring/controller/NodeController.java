package com.monitoring.controller;

import com.monitoring.model.Node;
import com.monitoring.model.NodeHistory;
import com.monitoring.service.NodeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin

public class NodeController {
    @Autowired
    private NodeService nodeService;

    // Hearbeat endpoint
    @PostMapping("/heartbeat")
    public String receiveHeartbeat(@RequestBody Node node, HttpServletRequest request){
        // get sender IP address
        String ipAddress = request.getRemoteAddr();
        //node.setIpAddress(ipAddress);

        // forward to service layer
        nodeService.processHeartbeat(node);

        return "Heartbeat received from " + ipAddress;
    }

    // Get all nodes
    @GetMapping("/node")
    public List<Node> getAllNodes(){
        return nodeService.getAllNodes();
    }

    // Get node by ID
    @GetMapping("/nodes/{id}")
    public Node getNodeById(@PathVariable String id){
        return nodeService.getNodeById(id);
    }

    // Get node history 
    @GetMapping("/nodes/{id}/history")
    public List<NodeHistory> getNodeHistory(@PathVariable String id){
        return nodeService.getNodeHistory(id);
    }
}
