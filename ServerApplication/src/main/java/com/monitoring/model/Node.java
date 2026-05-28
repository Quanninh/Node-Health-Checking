package com.monitoring.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "nodes")
public class Node {

    @Id
    private String id;
    private String ipAddress;
    private LocalDateTime lastHeartbeat;
    private String status;
    private int crackingPort;
    private int p2pPort;

    @ElementCollection
    @CollectionTable(name = "node_neighbors", joinColumns = @JoinColumn(name = "node_id"))
    private List<String> neighbors = new ArrayList<>();

    public Node() {
    }

    public int getCrackingPort() {
        return crackingPort;
    }

    public String getId() {
        return id;
    }

    public void setCrackingPort(int crackingPort) {
        this.crackingPort = crackingPort;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getP2pPort() {
        return p2pPort;
    }

    public void setP2pPort(int p2pPort) {
        this.p2pPort = p2pPort;
    }

    public List<String> getNeighbors() {
        return neighbors;
    }

    public void setNeighbors(List<String> neighbors) {
        this.neighbors = neighbors;
    }

}