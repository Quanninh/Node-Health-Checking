package com.monitoring.agent.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import com.monitoring.agent.constant.Constant;

public class Console {

    private static String nodeId;
    private static final String LOG_FILE = "log.txt";

    private static synchronized void writeToFile(String text) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {

            out.println(nodeId + "::" + text);

        } catch (IOException ignore) {
        }
    }

    public static void setNodeId(String nodeId) {
        // not working
        Console.nodeId = nodeId;
        writeToFile(nodeId);
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
