package com.monitoring.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FailureReportDto {
    private String reporterNodeId;
    private String failedNodeId;
    private String message;
    private double phi;
    private String status;
    private LocalDateTime timestamp;

    public FailureReportDto() {
    }

    public String getReporterNodeId() {
        return reporterNodeId;
    }

    public String getFailedNodeId() {
        return failedNodeId;
    }

    public String getMessage() {
        return message;
    }

    public double getPhi() {
        return phi;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}