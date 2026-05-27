package com.monitoring.agent.vaultcracking;

/**
 * A cracking response from a node.
 * 
 * @param found     whether the password is found
 * @param password  the password if found
 * @param nodeId    the node ID
 * @param timeTaken the time taken (ms)
 */
public class CrackingResponse {

    // @SuppressWarnings("unused")
    private final boolean found;
    // @SuppressWarnings("unused")
    private final String password;
    private final String nodeId;
    // @SuppressWarnings("unused")
    private final long timeTaken;

    public CrackingResponse(boolean found, String password, String nodeId, long timeTaken) {
        this.found = found;
        this.password = password;
        this.nodeId = nodeId;
        this.timeTaken = timeTaken;
    }

    public boolean isFound() {
        return found;
    }

    public String getPassword() {
        return password;
    }

    public String getNodeId() {
        return nodeId;
    }

    public long getTimeTaken() {
        return timeTaken;
    }

}
