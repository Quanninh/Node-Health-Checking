package com.example.agent.constant;

public final class Constant {
    private Constant() {
        // Can not initialize
    }

    public static final int DEFAULT_GOSSIP_INTERVAL_SECONDS = 5;
    public static final int DEFAULT_ACK_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_K_HELPERS = 3;
    public static final int DEFAULT_PHI_WINDOW_SIZE = 5;

    public static final double DEFAULT_WARNING_THRESHOLD = 1.0;
    public static final double DEFAULT_SUSPECTED_THRESHOLD = 3.0;
    public static final double DEFAULT_UNREACHABLE_THRESHOLD = 5.0;
    public static final double DEFAULT_MIN_STD_DEVIATION = 0.1;
    public static final double DEFAULT_MIN_PROBABILITY = 1e-12;
    public static final int DEFAULT_GOSSIP_TTL = 4;
}
