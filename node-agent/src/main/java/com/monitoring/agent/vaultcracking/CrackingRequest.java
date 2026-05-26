package com.monitoring.agent.vaultcracking;

public class CrackingRequest {
    private String hash;
    private long rangeStart;
    private long rangeEnd;
    private long deadline;

    public CrackingRequest() {
    }

    public CrackingRequest(String hash, long rangeStart, long rangeEnd, long deadline) {
        this.hash = hash;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.deadline = deadline;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public long getRangeStart() {
        return rangeStart;
    }

    public void setRangeStart(long rangeStart) {
        this.rangeStart = rangeStart;
    }

    public long getRangeEnd() {
        return rangeEnd;
    }

    public void setRangeEnd(long rangeEnd) {
        this.rangeEnd = rangeEnd;
    }

    public long getDeadline() {
        return deadline;
    }

    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }
}
