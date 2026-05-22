package com.monitoring.agent.node;

import java.util.List;

/**
 * 
 */
public class PhiAccrualFailure {

    private final int windowSize;
    private final double warningThreshold;
    private final double suspectedThreshold;
    private final double unreachableThreshold;
    private final double minStdDeviation;
    private final double minProbability;

    public PhiAccrualFailure(
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

    /**
     * Calculates the phi value for heartbeat time.
     * 
     * @param slidingWindow
     * @param lastHeartbeatTimeMillis
     * @param currentTimeMillis
     * @return
     */
    public double calculatePhi(List<Double> slidingWindow, long lastHeartbeatTimeMillis, long currentTimeMillis) {
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
        double meanSlidingWindow = mean(slidingWindow);
        double sdSlidingWindow = standardDeviation(slidingWindow);

        sdSlidingWindow = sdSlidingWindow < minStdDeviation ? minStdDeviation : sdSlidingWindow;

        // z - number of sd away from mean
        double z = (elapsedTimeSeconds - meanSlidingWindow) / sdSlidingWindow;
        double cdf = normalCdf(z);
        double pLater = 1.0 - cdf;

        if (pLater < minProbability) {
            pLater = minProbability;
        }

        return -Math.log10(pLater);
    }

    /**
     * From the phi value, determine the status of the node.
     * 
     * @param phi phi value
     * @return the node status
     */
    public NodeStatus determineStatus(double phi) {
        if (phi >= unreachableThreshold) {
            return NodeStatus.UNREACHABLE;
        }

        if (phi >= suspectedThreshold) {
            return NodeStatus.SUSPECTED;
        }

        if (phi >= warningThreshold) {
            return NodeStatus.WARNING;
        }

        return NodeStatus.ALIVE;
    }

    /**
     * Updates the sliding window. New values are added to the end, older values are
     * removed.
     * 
     * @param slidingWindow      the sliding window
     * @param newIntervalSeconds the new value to be added
     */
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

    /**
     * Calculate the mean of the values.
     * 
     * @param values the list of values
     * @return the mean :)
     */
    private double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be null or empty.");
        }

        double sum = 0.0;

        for (double value : values) {
            sum += value;
        }

        return sum / values.size();
    }

    /**
     * Calculates the standard deviation of the values.
     * 
     * @param values the list of values
     * @return the standard deviation
     */
    private double standardDeviation(List<Double> values) {
        double mean = mean(values);
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

    /**
     * Normal distribution cumulative probability.
     * 
     * @param z z-score in [-1, 1]
     * @return [0, 1]
     */
    private double normalCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /**
     * Error function, denoted as erf. erf(a/(sigma * sqrt(2))) is the probability
     * that the error of a single measurement lies between -a and a. It is used here
     * to normalize the z-score.
     * 
     * @param x
     * @return erf in [-1; 1]
     */
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

        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return sign * y;
    }
}
