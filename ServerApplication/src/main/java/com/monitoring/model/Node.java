package com.monitoring.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity

public class Node {
    @Id
    private String id;

    private double cpuUsage;
    private double memoryUsage;
    private String ipAddress;

    public String getIpAddress(){
        return ipAddress;
    }
}
