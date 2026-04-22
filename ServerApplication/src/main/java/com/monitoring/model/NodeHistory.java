package com.monitoring.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class NodeHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nodeId;

    private double cpuUsage;
    private double memoryUsage;

    private LocalDateTime timestamp;

    public Long getId(){
        return id;
    }

    public String getNodeId(){
        return nodeId;
    }

    public void setNodeId(String nodeId){
        this.nodeId = nodeId;
    }

    public double getCpuUsage(){
        return cpuUsage;
    }

    public void setCpuUsage(double cpuUsage){
        this.cpuUsage = cpuUsage;
    }

    public double getMemoryUsage(){
        return memoryUsage;
    }

    public void setMemoryUsage(double memoryUsage){
        this.memoryUsage = memoryUsage;
    }

    public LocalDateTime getTimestamp(){
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp){
        this.timestamp = timestamp;
    }
}
