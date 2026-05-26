package com.monitoring.model;

public class PasswordCrackResponse {
    private boolean success;
    private String password;
    private String message;
    private long timeTaken;

    public PasswordCrackResponse() {}

    public PasswordCrackResponse(boolean success, String password, String message, long timeTaken) {
        this.success = success;
        this.password = password;
        this.message = message;
        this.timeTaken = timeTaken;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTimeTaken() {
        return timeTaken;
    }

    public void setTimeTaken(long timeTaken) {
        this.timeTaken = timeTaken;
    }
}
