package com.monitoring.agent.node.recovery;

public class RepairSession {
    private final String repairEpoch;
    private volatile boolean completed;

    public RepairSession(String repairEpoch) {
        this.repairEpoch = repairEpoch;
    }

    public String repairEpoch() {
        return repairEpoch;
    }

    public boolean completed() {
        return completed;
    }

    public void markCompleted() {
        this.completed = true;
    }
}
