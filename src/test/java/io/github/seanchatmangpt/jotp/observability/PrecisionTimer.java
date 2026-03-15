package io.github.seanchatmangpt.jotp.observability;

import java.util.Arrays;

/**
 * High-precision timing utility for nanosecond and microsecond accuracy measurements.
 *
 * <p>Provides nanosecond-precision timing using {@link System#nanoTime()}, which is monotonic and
 * suitable for measuring elapsed time intervals. Note that nanoTime() provides relative timing only
 * and should not be used for wall-clock time.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * try (var ctx = PrecisionTimer.startNs()) {
 *     // code to measure
 *     long elapsedNs = ctx.stopNs();
 * }
 *
 * long[] samples = {100, 200, 300, 400, 500};
 * var percentiles = PrecisionTimer.calculatePercentiles(samples);
 * System.out.println("p95: " + percentiles.p95());
 * }</pre>
 */
public final class PrecisionTimer {

    private PrecisionTimer() {
        // Utility class
    }

    /**
     * Starts a nanosecond-precision timing context.
     *
     * @return a new TimingContext configured for nanosecond precision
     */
    public static TimingContext startNs() {
        return new TimingContext(Precision.NANOSECONDS);
    }

    /**
     * Starts a microsecond-precision timing context.
     *
     * @return a new TimingContext configured for microsecond precision
     */
    public static TimingContext startUs() {
        return new TimingContext(Precision.MICROSECONDS);
    }

    /**
     * Returns the current value of the running Java Virtual Machine's high-resolution time source,
     * in nanoseconds.
     *
     * <p>This method can only be used to measure elapsed time and is not related to any other
     * notion of system or wall-clock time.
     *
     * @return the current value of the high-resolution time source, in nanoseconds
     * @see System#nanoTime()
     */
    public static long nowNanos() {
        return System.nanoTime();
    }

    /**
     * Returns the current time in microseconds.
     *
     * <p>This is a derived value from nanoseconds divided by 1000.
     *
     * @return the current time in microseconds
     */
    public static long nowMicros() {
        return System.nanoTime() / 1000;
    }

    /**
     * Asserts that the actual nanosecond value is less than the expected value.
     *
     * @param actual the actual value in nanoseconds
     * @param expected the expected maximum value in nanoseconds
     * @param message the assertion message
     * @throws AssertionError if actual >= expected
     */
    public static void assertLessThanNs(long actual, long expected, String message) {
        if (actual >= expected) {
            throw new AssertionError(
                    String.format("%s: %d ns >= %d ns", message, actual, expected));
        }
    }

    /**
     * Asserts that the actual microsecond value is less than the expected value.
     *
     * @param actual the actual value in microseconds
     * @param expected the expected maximum value in microseconds
     * @param message the assertion message
     * @throws AssertionError if actual >= expected
     */
    public static void assertLessThanUs(long actual, long expected, String message) {
        if (actual >= expected) {
            throw new AssertionError(
                    String.format("%s: %d μs >= %d μs", message, actual, expected));
        }
    }

    /**
     * Asserts that the actual percentage is within the tolerance of the expected percentage.
     *
     * @param actual the actual percentage value (0-100)
     * @param expected the expected percentage value (0-100)
     * @param tolerance the allowed tolerance in percentage points
     * @param message the assertion message
     * @throws AssertionError if the difference exceeds tolerance
     */
    public static void assertPercentage(
            double actual, double expected, double tolerance, String message) {
        double diff = Math.abs(actual - expected);
        if (diff > tolerance) {
            throw new AssertionError(
                    String.format(
                            "%s: %.2f%% differs from %.2f%% by %.2f%% (tolerance: %.2f%%)",
                            message, actual, expected, diff, tolerance));
        }
    }

    /**
     * Calculates percentiles from a sample of timing measurements.
     *
     * <p>Computes p50 (median), p95, p99, min, max, and mean from the provided samples.
     *
     * @param samples an array of timing measurements in nanoseconds
     * @return a PercentileResult containing the computed statistics
     * @throws IllegalArgumentException if samples is null or empty
     */
    public static PercentileResult calculatePercentiles(long[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples must not be null or empty");
        }

        long[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);

        long min = sorted[0];
        long max = sorted[sorted.length - 1];
        double mean = Arrays.stream(samples).average().orElse(0.0);

        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);

        return new PercentileResult(p50, p95, p99, min, max, mean);
    }

    /**
     * Calculates a percentile from a sorted array using linear interpolation.
     *
     * @param sorted a sorted array of values
     * @param percentile the percentile to calculate (0-100)
     * @return the percentile value
     */
    private static long percentile(long[] sorted, double percentile) {
        int n = sorted.length;
        double rank = percentile / 100.0 * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);

        if (lower == upper) {
            return sorted[lower];
        }

        // Linear interpolation
        double weight = rank - lower;
        return (long) (sorted[lower] * (1 - weight) + sorted[upper] * weight);
    }

    /** Precision level for timing measurements. */
    private enum Precision {
        NANOSECONDS,
        MICROSECONDS
    }

    /**
     * A timing context that captures the start time and provides methods to stop timing.
     *
     * <p>Designed for use with try-with-resources for automatic cleanup:
     *
     * <pre>{@code
     * try (var ctx = PrecisionTimer.startNs()) {
     *     // code to measure
     * } // auto-stop
     * }</pre>
     */
    public static final class TimingContext implements AutoCloseable {

        private final long startNanos;
        private final Precision precision;
        private boolean stopped = false;

        private TimingContext(Precision precision) {
            this.precision = precision;
            this.startNanos = System.nanoTime();
        }

        /**
         * Stops timing and returns the elapsed time in nanoseconds.
         *
         * @return elapsed time in nanoseconds
         * @throws IllegalStateException if already stopped
         */
        public long stopNs() {
            ensureNotStopped();
            stopped = true;
            return System.nanoTime() - startNanos;
        }

        /**
         * Stops timing and returns the elapsed time in microseconds.
         *
         * @return elapsed time in microseconds
         * @throws IllegalStateException if already stopped
         */
        public long stopUs() {
            return stopNs() / 1000;
        }

        /**
         * Returns the elapsed time without stopping the context.
         *
         * @return elapsed time in nanoseconds
         */
        public long elapsedNanos() {
            return System.nanoTime() - startNanos;
        }

        /**
         * Returns the elapsed time in microseconds without stopping the context.
         *
         * @return elapsed time in microseconds
         */
        public long elapsedMicros() {
            return elapsedNanos() / 1000;
        }

        private void ensureNotStopped() {
            if (stopped) {
                throw new IllegalStateException("TimingContext already stopped");
            }
        }

        @Override
        public void close() {
            if (!stopped) {
                stopNs();
            }
        }
    }

    /**
     * A record containing percentile statistics from timing measurements.
     *
     * @param p50 the 50th percentile (median)
     * @param p95 the 95th percentile
     * @param p99 the 99th percentile
     * @param min the minimum value
     * @param max the maximum value
     * @param mean the arithmetic mean
     */
    public record PercentileResult(long p50, long p95, long p99, long min, long max, double mean) {
        @Override
        public String toString() {
            return String.format(
                    "PercentileResult{p50=%d ns, p95=%d ns, p99=%d ns, min=%d ns, max=%d ns, mean=%.2f ns}",
                    p50, p95, p99, min, max, mean);
        }
    }
}
