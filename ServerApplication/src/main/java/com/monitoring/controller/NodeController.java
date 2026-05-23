package com.monitoring.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.monitoring.model.FailureReport;
import com.monitoring.model.Node;
import com.monitoring.service.NodeService;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class NodeController {

    @Autowired
    private NodeService nodeService;

    @PostMapping("/heartbeat")
    public ResponseEntity<String> receiveHeartbeat(
            @RequestBody Node node
    ) {

        nodeService.processHeartbeat(node);

        return ResponseEntity.ok(
                "Heartbeat received from " + node.getId()
        );
    }

    @PostMapping("/failure-report")
    public ResponseEntity<String> receiveFailureReport(
            @RequestBody FailureReport report
    ) {

        nodeService.processFailureReport(report);

        return ResponseEntity.ok(
                "Failure report received"
        );
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<Node>> getAllNodes() {

        return ResponseEntity.ok(
                nodeService.getAllNodes()
        );
    }

    @GetMapping("/nodes/{id}")
    public ResponseEntity<Node> getNodeById(
            @PathVariable String id
    ) {

        Node node =
                nodeService.getNodeById(id);

        if (node == null) {

            return ResponseEntity.notFound()
                    .build();
        }

        return ResponseEntity.ok(node);
    }

    @GetMapping("/failure-reports")
    public ResponseEntity<List<FailureReport>> getFailureReports() {

        return ResponseEntity.ok(
                nodeService.getFailureReports()
        );
    }
}