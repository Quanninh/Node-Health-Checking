package com.monitoring.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.monitoring.model.FailureReport;
import com.monitoring.model.Node;
import com.monitoring.model.NodeHistory;
import com.monitoring.service.NodeService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class NodeController {

    @Autowired
    private NodeService nodeService;

    // Hearbeat endpoint
    @PostMapping("/heartbeat")
    public String receiveHeartbeat(@RequestBody Node node, HttpServletRequest request) {
        String ipAddress = request.getRemoteAddr();
        node.setIpAddress(ipAddress);

        nodeService.processHeartbeat(node);

        return "Heartbeat received from " + ipAddress;
    }

    @GetMapping("/nodes")
    public List<Node> getAllNodes() {
        return nodeService.getAllNodes();
    }

    @GetMapping("/nodes/{id}")
    public Node getNodeById(@PathVariable String id) {
        return nodeService.getNodeById(id);
    }

    @GetMapping("/nodes/{id}/history")
    public List<NodeHistory> getNodeHistory(@PathVariable String id) {
        return nodeService.getNodeHistory(id);
    }

    @PostMapping("/failure-report")
    public String receiveFailureReport(@RequestBody FailureReport report) {
        nodeService.processFailureReport(report);
        return "Failure report received: " + report.getMessage();
    }

    @GetMapping("/failure-reports")
    public List<FailureReport> getFailureReports() {
        return nodeService.getFailureReports();
    }

}
