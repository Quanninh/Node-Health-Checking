package com.monitoring.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.monitoring.model.CrackingResponse;
import com.monitoring.model.FailureReport;
import com.monitoring.model.Node;
import com.monitoring.model.PasswordCrackRequest;
import com.monitoring.model.PasswordCrackResponse;
import com.monitoring.service.NodeService;
import com.monitoring.service.PasswordCrackingService;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class NodeController {

    @Autowired
    private NodeService nodeService;

    @Autowired
    private PasswordCrackingService passwordCrackingService;

    @PostMapping("/heartbeat")
    public ResponseEntity<String> receiveHeartbeat(
            @RequestBody Node node) {

        nodeService.processHeartbeat(node);

        return ResponseEntity.ok(
                "Heartbeat received from " + node.getId());
    }

    @PostMapping("/failure-report")
    public ResponseEntity<String> receiveFailureReport(
            @RequestBody FailureReport report) {

        nodeService.processFailureReport(report);
        passwordCrackingService.handleNodeFailure(report.getFailedNodeId());

        return ResponseEntity.ok(
                "Failure report received");
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<Node>> getAllNodes() {
        return ResponseEntity.ok(nodeService.getAllNodes());
    }

    @GetMapping("/nodes/{id}")
    public ResponseEntity<Node> getNodeById(@PathVariable String id) {
        Node node = nodeService.getNodeById(id);

        if (node == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(node);
    }

    @GetMapping("/failure-reports")
    public ResponseEntity<List<FailureReport>> getFailureReports() {
        return ResponseEntity.ok(nodeService.getFailureReports());
    }

    @PostMapping("/crack-password")
    public ResponseEntity<PasswordCrackResponse> crackPassword(@RequestBody PasswordCrackRequest request) {
        try {
            PasswordCrackResponse response = passwordCrackingService.crackPassword(request.getHash());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(
                    new PasswordCrackResponse(false, null, "Error: " + e.getMessage(), 0));
        }
    }

    @PostMapping("/node/result")
    public ResponseEntity<String> receiveNodeResult(@RequestBody CrackingResponse response) {
        passwordCrackingService.handleNodeResult(response);
        return ResponseEntity.ok("Result received from node: " + response.getNodeId());
    }

    // @GetMapping("/nodes")
    // public ResponseEntity<List<Node>> getNodes() {
    // return ResponseEntity.ok(
    // passwordCrackingService.getNodes()
    // );
    // }
}