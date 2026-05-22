package com.monitoring.agent.node.connection;

public record CommitResult(boolean accepted, String reason) {
}
