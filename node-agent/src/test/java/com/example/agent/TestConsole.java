package com.example.agent;

import com.monitoring.agent.util.*;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleTest {

    @Test
    void testLogWritesToFile() throws Exception {

        // Arrange
        String message = "unit-test-message";

        // Act
        Console.log(message);

        // Give filesystem a moment (important for buffered IO)
        Thread.sleep(100);

        // Assert
        File file = new File("log.txt");
        assertTrue(file.exists(), "log.txt should exist");

        List<String> lines = Files.readAllLines(Paths.get("log.txt"));

        boolean found = lines.stream()
                .anyMatch(line -> line.contains(message));

        assertTrue(found, "log file should contain logged message");
    }
}