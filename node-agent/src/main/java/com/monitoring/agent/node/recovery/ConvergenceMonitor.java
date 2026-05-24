package com.monitoring.agent.node.recovery;

import com.monitoring.agent.node.connection.ConnectionManager;

/**
 * Checks if system has reached convergence, i.e. all nodes has reached the
 * neighbor limit.
 */
public class ConvergenceMonitor {

    private final ConnectionManager connectionManager;

    public ConvergenceMonitor(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Checks if system has reached convergence, i.e. all nodes has reached the
     * neighbor limit.
     * 
     * @return whether the system has reached convergence
     */
    public boolean hasConverged() {
        return connectionManager.size() == connectionManager.getMaxNeighbors();
    }

}
