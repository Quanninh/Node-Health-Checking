package com.monitoring.agent.node;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON Parser. I suggest using a library for this, it will be much easier to
 * understand and less errors.
 */
public final class P2pJson {

    /**
     * Don't allow constructor.
     */
    private P2pJson() {
    }

    public static String stringValue(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
        }

        throw new IllegalArgumentException("Missing string field in JSON: " + fieldName);
    }

    public static int intValue(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        throw new IllegalArgumentException("Missing int field in JSON: " + fieldName);
    }

    public static double doubleValue(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }

        throw new IllegalArgumentException("Missing double field in JSON: " + fieldName);
    }

    public static boolean booleanValue(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }

        throw new IllegalArgumentException("Missing boolean field in JSON: " + fieldName);
    }

    public static String optionalStringValue(String json, String fieldName) {
        Pattern stringPattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher stringMatcher = stringPattern.matcher(json);

        if (stringMatcher.find()) {
            return URLDecoder.decode(stringMatcher.group(1), StandardCharsets.UTF_8);
        }

        Pattern nullPattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*null");
        Matcher nullMatcher = nullPattern.matcher(json);

        if (nullMatcher.find()) {
            return null;
        }

        return null;
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

}
