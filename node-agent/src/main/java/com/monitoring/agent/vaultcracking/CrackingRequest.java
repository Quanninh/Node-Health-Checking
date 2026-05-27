package com.monitoring.agent.vaultcracking;

public class CrackingRequest {

    private String hash;
    private long rangeStart;
    private long rangeEnd;
    private long deadline;

    // public CrackingRequest(String hash, long rangeStart, long rangeEnd, long
    // deadline) {
    // this.hash = hash;
    // this.rangeStart = rangeStart;
    // this.rangeEnd = rangeEnd;
    // this.deadline = deadline;
    // }

    public String getHash() {
        return hash;
    }

    public long getRangeStart() {
        return rangeStart;
    }

    public long getRangeEnd() {
        return rangeEnd;
    }

    public long getDeadline() {
        return deadline;
    }

}
