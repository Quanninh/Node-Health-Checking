package com.monitoring.agent.constant;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import io.github.cdimascio.dotenv.Dotenv;

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

    public static final int DEFAULT_GOSSIP_TTL = 3;

    public static final String FIELD_SEPARATOR = "&";
    public static final String KEY_VALUE_SEPARATOR = "=";
    public static final String LIST_SEPARATOR = ",";

    private static final Dotenv dotenv = Dotenv.load();
    public static final boolean ANSI_ENABLED = Boolean.parseBoolean(dotenv.get("ANSI_ENABLED", "false"));

    public static final String RESET = ANSI_ENABLED ? "\u001B[0m" : "";

    public static final String RED = ANSI_ENABLED ? "\u001B[31m" : "";
    public static final String GREEN = ANSI_ENABLED ? "\u001B[32m" : "";
    public static final String PURPLE = ANSI_ENABLED ? "\u001B[33m" : "";
    public static final String ORANGE = ANSI_ENABLED ? "\u001B[34m" : "";
    public static final String PINK = ANSI_ENABLED ? "\u001B[35m" : "";
    public static final String CYAN = ANSI_ENABLED ? "\u001B[36m" : "";

    public static final String BG_RED = ANSI_ENABLED ? "\u001B[41m" : "";
    public static final String BG_GREEN = ANSI_ENABLED ? "\u001B[42m" : "";
    public static final String BG_PURPLE = ANSI_ENABLED ? "\u001B[43m" : "";
    public static final String BG_ORANGE = ANSI_ENABLED ? "\u001B[44m" : "";
    public static final String BG_PINK = ANSI_ENABLED ? "\u001B[45m" : "";
    public static final String BG_CYAN = ANSI_ENABLED ? "\u001B[46m" : "";

    public static final String BOLD = ANSI_ENABLED ? "\u001B[1m" : "";
    public static final String DIM = ANSI_ENABLED ? "\u001B[2m" : "";
    public static final String ITALIC = ANSI_ENABLED ? "\u001B[3m" : "";
    public static final String UNDERLINE = ANSI_ENABLED ? "\u001B[4m" : "";
    public static final String STRIKETHROUGH = ANSI_ENABLED ? "\u001B[9m" : "";

    public static String NOW() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yy_HH:mm:ss.SSS"));
    }

}
