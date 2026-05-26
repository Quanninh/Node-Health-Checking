package com.monitoring.agent.vaultcracking;

/**
 * The crack result.
 * 
 * @param found     if the correct password is found
 * @param password  the correct password if found
 * @param timeTaken the time taken to search the current space
 */
public class CrackResult {

    public final boolean found;
    public final String password;
    public final long timeTaken;

    /**
     * The crack result.
     * 
     * @param found     if the correct password is found
     * @param password  the correct password if found
     * @param timeTaken the time taken to search the current space
     */
    public CrackResult(boolean found, String password, long timeTaken) {
        this.found = found;
        this.password = password;
        this.timeTaken = timeTaken;
    }

}
