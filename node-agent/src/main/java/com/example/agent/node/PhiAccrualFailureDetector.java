package com.example.agent.node;

import java.util.List;

public class PhiAccrualFailureDetector {
    private final int windowSize;

    private final double warningThreshold;
    private final double suspectedThreshold;
    private final double unreachableThreshold;

    private final double minStdDeviation;
    private final double minProbability;

    public PhiAccrualFailureDetector(
            int windowSize,
            double warningThreshold,
            double suspectedThreshold,
            double unreachableThreshold,
            double minStdDeviation,
            double minProbability) {
        if (windowSize <= 1) {
            throw new IllegalArgumentException("windowSize must be greater than 1.");
        }

        this.windowSize = windowSize;
        this.warningThreshold = warningThreshold;
        this.suspectedThreshold = suspectedThreshold;
        this.unreachableThreshold = unreachableThreshold;
        this.minStdDeviation = minStdDeviation;
        this.minProbability = minProbability;
    }

    public double calculatePhi(
            List<Double> slidingWindow,
            long lastHeartbeatTimeMillis,
            long currentTimeMillis) {
        if (lastHeartbeatTimeMillis <= 0) {
            return Double.POSITIVE_INFINITY;
        }

        if (currentTimeMillis < lastHeartbeatTimeMillis) {
            throw new IllegalArgumentException("currentTimeMillis cannot be smaller than lastHeartbeatTimeMillis.");
        }

        if (slidingWindow == null || slidingWindow.size() < 2) {
            return 0.0;
        }

        double elapsedTimeSeconds = (currentTimeMillis - lastHeartbeatTimeMillis) / 1000.0;

        double mean = calculateMean(slidingWindow);
        double stdDeviation = calculateStandardDeviation(slidingWindow, mean);

        if (stdDeviation < minStdDeviation) {
            stdDeviation = minStdDeviation;
        }

        double z = (elapsedTimeSeconds - mean) / stdDeviation;

        double cdf = normalCdf(z);

        double pLater = 1.0 - cdf;

        if (pLater < minProbability) {
            pLater = minProbability;
        }

        return -Math.log10(pLater);
    }

    // This one is depend what you need
    public PeerStatus determineStatus(double phi) {
        if (phi >= unreachableThreshold) {
            return PeerStatus.UNREACHABLE;
        }

        if (phi >= suspectedThreshold) {
            return PeerStatus.SUSPECTED;
        }

        if (phi >= warningThreshold) {
            return PeerStatus.WARNING;
        }

        return PeerStatus.ALIVE;
    }

    public void updateSlidingWindow(List<Double> slidingWindow, double newIntervalSeconds) {
        if (slidingWindow == null) {
            throw new IllegalArgumentException("slidingWindow cannot be null.");
        }

        if (newIntervalSeconds < 0) {
            throw new IllegalArgumentException("newIntervalSeconds cannot be negative.");
        }

        slidingWindow.add(newIntervalSeconds);

        while (slidingWindow.size() > windowSize) {
            slidingWindow.remove(0);
        }
    }

    public double calculateMean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be null or empty.");
        }

        double sum = 0.0;

        for (double value : values) {
            sum += value;
        }

        return sum / values.size();
    }

    public double calculateStandardDeviation(List<Double> values, double mean) {
        if (values == null || values.size() < 2) {
            return minStdDeviation;
        }

        double sumSquaredDifference = 0.0;

        for (double value : values) {
            double difference = value - mean;
            sumSquaredDifference += difference * difference;
        }

        double variance = sumSquaredDifference / values.size();

        return Math.sqrt(variance);
    }

    public double normalCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    private double erf(double x) {
        double sign = Math.signum(x);
        x = Math.abs(x);

        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        double t = 1.0 / (1.0 + p * x);

        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1)
                * t
                * Math.exp(-x * x);

        return sign * y;
    }
}
