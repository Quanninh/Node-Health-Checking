package com.example.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.agent.node.NodeStatus;
import com.example.agent.node.PhiAccrualFailure;

public class PhiAccrualFailureDetectorTest {
    private PhiAccrualFailure detector;

    @BeforeEach
    public void setUp() {
        detector = new PhiAccrualFailure(
                5, // windowSize
                1.0, // warningThreshold
                3.0, // suspectedThreshold
                5.0, // unreachableThreshold
                0.1, // minStdDeviation
                1e-12 // minProbability
        );
    }

    @Test
    public void testCalculateMean() {
        List<Double> window = Arrays.asList(5.0, 5.1, 4.9, 5.2, 5.0);

        double mean = detector.calculateMean(window);

        assertEquals(5.04, mean, 0.0001);
    }

    @Test
    public void testCalculateStandardDeviation() {
        List<Double> window = Arrays.asList(5.0, 5.1, 4.9, 5.2, 5.0);

        double mean = detector.calculateMean(window);
        double stdDeviation = detector.calculateStandardDeviation(window, mean);

        assertEquals(0.10198, stdDeviation, 0.001);
    }

    @Test
    public void testCalculatePhiWarningCase() {
        List<Double> window = Arrays.asList(5.0, 5.1, 4.9, 5.2, 5.0);

        long lastHeartbeatTimeMillis = 100000L;
        long currentTimeMillis = 105200L;

        double phi = detector.calculatePhi(
                window,
                lastHeartbeatTimeMillis,
                currentTimeMillis);

        NodeStatus status = detector.determineStatus(phi);

        assertTrue(phi >= 1.0 && phi < 3.0);
        assertEquals(NodeStatus.WARNING, status);
    }

    @Test
    public void testDetermineHealthyStatus() {
        NodeStatus status = detector.determineStatus(0.5);

        assertEquals(NodeStatus.ALIVE, status);
    }

    @Test
    public void testDetermineWarningStatus() {
        NodeStatus status = detector.determineStatus(1.5);

        assertEquals(NodeStatus.WARNING, status);
    }

    @Test
    public void testDetermineSuspectedStatus() {
        NodeStatus status = detector.determineStatus(3.5);

        assertEquals(NodeStatus.SUSPECTED, status);
    }

    @Test
    public void testDetermineUnreachableStatus() {
        NodeStatus status = detector.determineStatus(5.0);

        assertEquals(NodeStatus.UNREACHABLE, status);
    }

    @Test
    public void testUpdateSlidingWindowDoesNotExceedWindowSize() {
        List<Double> window = new ArrayList<>();

        detector.updateSlidingWindow(window, 5.0);
        detector.updateSlidingWindow(window, 5.1);
        detector.updateSlidingWindow(window, 4.9);
        detector.updateSlidingWindow(window, 5.2);
        detector.updateSlidingWindow(window, 5.0);
        detector.updateSlidingWindow(window, 5.3);

        assertEquals(5, window.size());

        assertEquals(5.1, window.get(0), 0.0001);
        assertEquals(4.9, window.get(1), 0.0001);
        assertEquals(5.2, window.get(2), 0.0001);
        assertEquals(5.0, window.get(3), 0.0001);
        assertEquals(5.3, window.get(4), 0.0001);
    }

    @Test
    public void testEmptySlidingWindowReturnsZeroPhi() {
        List<Double> window = new ArrayList<>();

        double phi = detector.calculatePhi(window, 100000L, 105000L);

        assertEquals(0.0, phi, 0.0001);
    }

    @Test
    public void testOneSampleSlidingWindowReturnsZeroPhi() {
        List<Double> window = Arrays.asList(5.0);

        double phi = detector.calculatePhi(window, 100000L, 105000L);

        assertEquals(0.0, phi, 0.0001);
    }

    @Test
    public void testNeverReceivedHeartbeatReturnsInfinity() {
        List<Double> window = Arrays.asList(5.0, 5.1, 4.9);

        double phi = detector.calculatePhi(window, -1L, 105000L);

        assertEquals(Double.POSITIVE_INFINITY, phi);
    }

    @Test
    public void testCurrentTimeBeforeLastHeartbeatThrowsException() {
        List<Double> window = Arrays.asList(5.0, 5.1, 4.9);

        assertThrows(
                IllegalArgumentException.class,
                () -> detector.calculatePhi(window, 105000L, 100000L));
    }

    @Test
    public void testNullSlidingWindowInUpdateThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> detector.updateSlidingWindow(null, 5.0));
    }

    @Test
    public void testNegativeIntervalThrowsException() {
        List<Double> window = new ArrayList<>();

        assertThrows(
                IllegalArgumentException.class,
                () -> detector.updateSlidingWindow(window, -1.0));
    }
}
