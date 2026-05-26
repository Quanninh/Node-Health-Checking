package com.monitoring.model;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class FailureReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reporterNodeId;
    private String failedNodeId;
    private String message;
    private LocalDateTime timestamp;

    private Double phi;

    private Double threshold;

    private String status;

    public Long getId() {
        return id;
    }

    public String getReporterNodeId() {
        return reporterNodeId;
    }

    public void setReporterNodeId(String reporterNodeId) {
        this.reporterNodeId = reporterNodeId;
    }

    public String getFailedNodeId() {
        return failedNodeId;
    }

    public void setFailedNodeId(String failedNodeId) {
        this.failedNodeId = failedNodeId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Double getPhi() {
        return phi;
    }

    public void setPhi(Double phi) {
        this.phi = phi;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

}
