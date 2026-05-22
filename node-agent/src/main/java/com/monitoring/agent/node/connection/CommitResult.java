package com.monitoring.agent.node.connection;

/**
 * The result of a commit.
 * 
 * @param accepted whether the commit is accepted or not
 * @param reason   reason for accept/deny
 */
public record CommitResult(boolean accepted, String reason) {
}
