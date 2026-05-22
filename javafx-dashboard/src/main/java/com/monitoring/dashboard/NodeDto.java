package com.monitoring.dashboard;

import java.time.LocalDateTime;

public class NodeDto {
    private String id;
    private double cpuUsage;
    private double memoryUsage;
    private String ipAddress;
    private LocalDateTime lastHeartbeat;
    private String status;

    public NodeDto() {
    }

    public String getId() {
        return id;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public double getMemoryUsage() {
        return memoryUsage;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public String getStatus() {
        return status;
    }
}