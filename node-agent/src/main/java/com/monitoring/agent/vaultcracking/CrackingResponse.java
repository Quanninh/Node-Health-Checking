package com.monitoring.agent.vaultcracking;

public class CrackingResponse {
    private boolean found;
    private String password;
    private String nodeId;
    private long timeTaken;

    public CrackingResponse() {
    }

    public CrackingResponse(boolean found, String password, String nodeId, long timeTaken) {
        this.found = found;
        this.password = password;
        this.nodeId = nodeId;
        this.timeTaken = timeTaken;
    }

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public long getTimeTaken() {
        return timeTaken;
    }

    public void setTimeTaken(long timeTaken) {
        this.timeTaken = timeTaken;
    }
}
