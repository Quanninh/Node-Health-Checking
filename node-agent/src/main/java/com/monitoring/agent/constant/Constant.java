package com.monitoring.agent.constant;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Constant {

    public static final int DEFAULT_MAX_NEIGHBORS = 2;
    public static final int DEFAULT_JOIN_TIMEOUT_SECONDS = 3;
    public static final double DEFAULT_JOIN_MIN_PROBABILITY = 0.35;
    public static final double DEFAULT_JOIN_MAX_PROBABILITY = 0.75;
    public static final long UNREACHABLE_CLEANUP_INTERVAL_SECONDS = 5;

    public static final int DEFAULT_GOSSIP_INTERVAL_SECONDS = 7;
    public static final int DEFAULT_ACK_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_K_HELPERS = 3;
    public static final int DEFAULT_PHI_WINDOW_SIZE = 5;

    public static final double DEFAULT_WARNING_THRESHOLD = 1.0;
    public static final double DEFAULT_SUSPECTED_THRESHOLD = 3.0;
    public static final double DEFAULT_UNREACHABLE_THRESHOLD = 5.0;
    public static final double DEFAULT_MIN_STD_DEVIATION = 0.1;
    public static final double DEFAULT_MIN_PROBABILITY = 1e-12;

    public static final int DEFAULT_GOSSIP_TTL = 4;

    // ANSI styling is disabled because this app's logs can make some terminal
    // themes unreadable. Original values are kept below as comments.
    public static final String RESET = "";

    public static final String RED = "";
    public static final String GREEN = "";
    public static final String YELLOW = "";
    public static final String BLUE = "";
    public static final String PURPLE = "";
    public static final String CYAN = "";

    public static final String BRIGHT_RED = "";
    public static final String BRIGHT_GREEN = "";
    public static final String BRIGHT_YELLOW = "";
    public static final String BRIGHT_BLUE = "";
    public static final String BRIGHT_PURPLE = "";
    public static final String BRIGHT_CYAN = "";

    // public static final String RESET = "\u001B[0m";
    // public static final String RED = "\u001B[31m";
    // public static final String GREEN = "\u001B[32m";
    // public static final String YELLOW = "\u001B[33m";
    // public static final String BLUE = "\u001B[34m";
    // public static final String PURPLE = "\u001B[35m";
    // public static final String CYAN = "\u001B[36m";
    // public static final String BRIGHT_RED = "\u001B[91m";
    // public static final String BRIGHT_GREEN = "\u001B[92m";
    // public static final String BRIGHT_YELLOW = "\u001B[93m";
    // public static final String BRIGHT_BLUE = "\u001B[94m";
    // public static final String BRIGHT_PURPLE = "\u001B[95m";
    // public static final String BRIGHT_CYAN = "\u001B[96m";

    // public static final String BG_RED = "\u001B[41m";
    // public static final String BG_GREEN = "\u001B[42m";
    // public static final String BG_YELLOW = "\u001B[43m";
    // public static final String BG_BLUE = "\u001B[44m";
    // public static final String BG_PURPLE = "\u001B[45m";
    // public static final String BG_CYAN = "\u001B[46m";

    public static final String BG_RED = "";
    public static final String BG_GREEN = "";
    public static final String BG_YELLOW = "";
    public static final String BG_BLUE = "";
    public static final String BG_PURPLE = "";
    public static final String BG_CYAN = "";

    public static final String BOLD = "";
    public static final String DIM = "";
    public static final String ITALIC = "";
    public static final String UNDERLINE = "";
    public static final String BLINK = "";
    public static final String STRIKETHROUGH = "";

    // public static final String BOLD = "\u001B[1m";
    // public static final String DIM = "\u001B[2m";
    // public static final String ITALIC = "\u001B[3m";
    // public static final String UNDERLINE = "\u001B[4m";
    // public static final String BLINK = "\u001B[5m";
    // public static final String STRIKETHROUGH = "\u001B[9m";

    public static String NOW() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yy_HH:mm:ss.SSS"));
    }

}
