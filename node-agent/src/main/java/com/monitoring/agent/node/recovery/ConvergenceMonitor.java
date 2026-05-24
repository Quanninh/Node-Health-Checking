package com.monitoring.agent.node.recovery;

import com.monitoring.agent.node.connection.ConnectionManager;

public class ConvergenceMonitor {

    private final ConnectionManager connectionManager;

    public ConvergenceMonitor(
            ConnectionManager connectionManager) {

        this.connectionManager = connectionManager;
    }

    public boolean converged() {

        return connectionManager.size()
                ==
                connectionManager.getMaxNeighbors();
    }
}
