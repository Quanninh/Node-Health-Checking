package com.monitoring.agent.util;

import com.monitoring.agent.constant.Constant;

public class Console {

    public static void log(String message, String color) {
        System.out.println("[" + Constant.NOW() + "] " + color + message + Constant.RESET);
    }

    public static void log(String message) {
        System.out.println("[" + Constant.NOW() + "] " + message);
    }

    public static void println(String message) {
        System.out.println(message);
    }

    public static void println(String message, String color) {
        System.out.println(color + message + Constant.RESET);
    }

}
