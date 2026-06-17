package com.monitoring.agent.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.monitoring.agent.constant.Constant;

public class Console {

    private static String nodeId;
    private static final String LOG_FILE = "log.txt";
    private static final String LOG_CSV = "packet_log.csv";

    private static synchronized void writeToFile(String text) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {

            out.println(nodeId + "::" + text);

        } catch (IOException ignore) {
        }
    }

    public static synchronized void logPacket(String timestamp, String sender, String receiver, String type,
            String message) {
        try {
            Files.writeString(
                    Path.of(LOG_CSV),
                    String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n", timestamp, sender, receiver, type,
                            message.replace("\"", "\"\"")),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

        } catch (IOException e) {
            // e.printStackTrace();
        }
    }

    public static void setNodeId(String nodeId) {
        Console.nodeId = nodeId;
        writeToFile(nodeId);
    }

    public static void logInfo(String message) {
        log(message, Constant.CYAN);
    }

    public static void logError(String message) {
        log(message, Constant.RED);
    }

    public static void logWarning(String message) {
        log(message, Constant.ORANGE);
    }

    public static void logSuccess(String message) {
        log(message, Constant.GREEN);
    }

    public static void logHighlight(String message) {
        log(message, Constant.BG_PINK);
    }

    public static void log(String message, String color) {
        String lineConsole = "[" + Constant.NOW() + "] " + " [" + Thread.currentThread().getStackTrace()[2] + "]"
                + color + message + Constant.RESET;
        String line = "[" + Constant.NOW() + "] " + message;
        System.out.println(lineConsole);
        writeToFile(line);
    }

    public static void log(String message) {
        System.out.println(
                "[" + Constant.NOW() + "] " + " [" + Thread.currentThread().getStackTrace()[2] + "]" + message);
        writeToFile("[" + Constant.NOW() + "] " + message);
    }

    public static void println(String message) {
        System.out.println(message);
        writeToFile(message);
    }

    public static void println(String message, String color) {
        System.out.println(color + message + Constant.RESET);
        writeToFile(message);
    }

}
