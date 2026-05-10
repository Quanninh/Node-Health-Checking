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

    private LocalDateTime lastHeartbeat;

    private String status;

    public String getId(){
        return id;
    }

    public void setId(String id){
        this.id = id;
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

    public String getIpAddress(){
        return ipAddress;
    }

    public void setIpAddress(String ipAddress){
        this.ipAddress = ipAddress;
    }

    public LocalDateTime getLastHeartbeat(){
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat){
        this.lastHeartbeat = lastHeartbeat;
    }

    public String getStatus(){
        return status;
    }

    public void setStatus(String status){
        this.status = status;
    }
}
