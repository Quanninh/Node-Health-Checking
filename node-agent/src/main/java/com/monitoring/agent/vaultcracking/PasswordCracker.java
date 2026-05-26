package com.monitoring.agent.vaultcracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class PasswordCracker {

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int PASSWORD_LENGTH = 5;

    private final String targetHash;
    private volatile boolean found = false;
    private String foundPassword = null;

    public PasswordCracker(String targetHash) {
        this.targetHash = targetHash.toLowerCase();
    }

    /**
     * Cracks all passwords in the range.
     * 
     * @param startIndex
     * @param endIndex
     * @return the crack result
     */
    public CrackResult crackRange(long startIndex, long endIndex) {
        long startTime = System.currentTimeMillis();

        for (long index = startIndex; index <= endIndex && !found; index++) {
            String password = indexToPassword(index);
            String hash = computeSHA256(password);

            if (hash.equals(targetHash)) {
                found = true;
                foundPassword = password;
                return new CrackResult(true, foundPassword, System.currentTimeMillis() - startTime);
            }
        }

        return new CrackResult(false, null, System.currentTimeMillis() - startTime);
    }

    /**
     * Turns an index into a password.
     * 
     * @param index
     * @return the password
     */
    private String indexToPassword(long index) {
        char[] password = new char[PASSWORD_LENGTH];
        for (int i = PASSWORD_LENGTH - 1; i >= 0; i--) {
            password[i] = CHARSET.charAt((int) (index % CHARSET.length()));
            index /= CHARSET.length();
        }
        return new String(password);
    }

    /**
     * Calculates the hash of the input string.
     * 
     * @param input the string
     * @return the hash
     */
    private String computeSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

}
