package com.example.agent.constant;

public final class Constant {

    public static final int DEFAULT_MAX_NEIGHBORS = 2;
    public static final int DEFAULT_JOIN_TIMEOUT_SECONDS = 3;
    public static final double DEFAULT_JOIN_MIN_PROBABILITY = 0.35;
    public static final double DEFAULT_JOIN_MAX_PROBABILITY = 0.75;
    public static final long UNREACHABLE_CLEANUP_INTERVAL_SECONDS = 5;

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

    // MAKE COLOR <3
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";

}
