package com.monitoring.dashboard;

public class PasswordCrackRequest {
    private String hash;

    public PasswordCrackRequest() {}

    public PasswordCrackRequest(String hash) {
        this.hash = hash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
