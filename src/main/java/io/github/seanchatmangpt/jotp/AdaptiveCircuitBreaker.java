package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Adaptive Circuit Breaker with EWMA trend prediction and anomaly detection.
 *
 * <p>Extends beyond simple threshold counting with statistical learning:
 *
 * <ul>
 *   <li><strong>EWMA error rate</strong> — exponentially weighted moving average reacts faster to
 *       error bursts than sliding-window counts
 *   <li><strong>Predictive opening</strong> — opens when the error rate <em>trend</em> is rising
 *       toward the threshold, before it is actually crossed
 *   <li><strong>Adaptive recovery</strong> — probe count in HALF_OPEN scales with the health signal
 *   <li><strong>Latency anomaly detection</strong> — opens on sustained latency spikes even with
 *       low error rate
 * </ul>
 *
 * <p><strong>State machine:</strong>
 *
 * <pre>
 * CLOSED → OPEN      : EWMA error rate > errorRateThreshold
 *                      OR (EWMA error rate > predictiveThreshold AND trend > 0)
 *                      OR last latency > latencyThreshold
 * OPEN → HALF_OPEN   : after openDuration expires
 * HALF_OPEN → CLOSED : all halfOpenProbes succeed
 * HALF_OPEN → OPEN   : any probe fails
 * </pre>
 *
 * <p><strong>Usage example:</strong>
 *
 * <pre>{@code
 * var breaker = AdaptiveCircuitBreaker.withDefaults();
 *
 * Result<String, Exception> result = breaker.tryExecute(() -> externalApi.call());
 * switch (result) {
 *     case Result.Ok(var value)  -> process(value);
 *     case Result.Err(var error) -> handleError(error);
 * }
 * }</pre>
 *
 * @see CircuitBreaker
 * @see Result
 */
public final class AdaptiveCircuitBreaker {

    // ── Exception thrown when the circuit is OPEN ───────────────────────────

    /** Thrown by {@link #execute} when the circuit is open (fast-fail). */
    public static final class CircuitOpenException extends RuntimeException {
        public CircuitOpenException() {
            super("Circuit breaker is OPEN — request rejected (fast-fail)");
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Observable state of the circuit breaker. */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    // ── Config ───────────────────────────────────────────────────────────────

    /**
     * Immutable configuration for the adaptive circuit breaker.
     *
     * @param ewmaAlpha smoothing factor in (0, 1]; smaller = slower reaction, larger = faster
     * @param errorRateThreshold open when EWMA error rate exceeds this value (0..1)
     * @param predictiveThreshold open when error rate exceeds this AND trend is positive (0..1)
     * @param openDuration how long the circuit stays OPEN before transitioning to HALF_OPEN
     * @param halfOpenProbes number of successful probes required to close the circuit
     * @param latencyThreshold open the circuit when a single call exceeds this latency
     */
    public record Config(
            double ewmaAlpha,
            double errorRateThreshold,
            double predictiveThreshold,
            Duration openDuration,
            int halfOpenProbes,
            Duration latencyThreshold) {

        /** Validate invariants at construction time. */
        public Config {
            if (ewmaAlpha <= 0 || ewmaAlpha > 1)
                throw new IllegalArgumentException("ewmaAlpha must be in (0, 1]");
            if (errorRateThreshold <= 0 || errorRateThreshold > 1)
                throw new IllegalArgumentException("errorRateThreshold must be in (0, 1]");
            if (predictiveThreshold <= 0 || predictiveThreshold > 1)
                throw new IllegalArgumentException("predictiveThreshold must be in (0, 1]");
            if (halfOpenProbes <= 0)
                throw new IllegalArgumentException("halfOpenProbes must be > 0");
        }

        /** Default configuration suitable for most services. */
        public static Config defaults() {
            return new Config(0.1, 0.5, 0.7, Duration.ofSeconds(5), 3, Duration.ofSeconds(2));
        }

        /** Fluent builder for custom configurations. */
        public static Builder builder() {
            return new Builder();
        }

        /** Fluent builder — all fields default to {@link Config#defaults()} values. */
        public static final class Builder {
            private double ewmaAlpha = 0.1;
            private double errorRateThreshold = 0.5;
            private double predictiveThreshold = 0.7;
            private Duration openDuration = Duration.ofSeconds(5);
            private int halfOpenProbes = 3;
            private Duration latencyThreshold = Duration.ofSeconds(2);

            private Builder() {}

            public Builder ewmaAlpha(double v) {
                this.ewmaAlpha = v;
                return this;
            }

            public Builder errorRateThreshold(double v) {
                this.errorRateThreshold = v;
                return this;
            }

            public Builder predictiveThreshold(double v) {
                this.predictiveThreshold = v;
                return this;
            }

            public Builder openDuration(Duration v) {
                this.openDuration = v;
                return this;
            }

            public Builder halfOpenProbes(int v) {
                this.halfOpenProbes = v;
                return this;
            }

            public Builder latencyThreshold(Duration v) {
                this.latencyThreshold = v;
                return this;
            }

            public Config build() {
                return new Config(
                        ewmaAlpha,
                        errorRateThreshold,
                        predictiveThreshold,
                        openDuration,
                        halfOpenProbes,
                        latencyThreshold);
            }
        }
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    /**
     * A snapshot of the circuit breaker's internal metrics at a point in time.
     *
     * @param state current circuit state
     * @param ewmaErrorRate current smoothed error rate (0..1)
     * @param errorTrend rate-of-change of the EWMA error rate (positive = worsening)
     * @param totalCalls total calls recorded since last reset
     * @param totalFailures total failures recorded since last reset
     * @param totalSuccesses total successes recorded since last reset
     * @param p99Latency approximate P99 call latency based on the last 100 measurements
     */
    public record Stats(
            State state,
            double ewmaErrorRate,
            double errorTrend,
            long totalCalls,
            long totalFailures,
            long totalSuccesses,
            Duration p99Latency) {}

    // ── Internal mutable state (guarded by `this`) ───────────────────────────

    private final Config config;

    // State machine
    private volatile State state = State.CLOSED;
    private volatile Instant openedAt = null;
    private volatile int halfOpenSuccesses = 0;

    // EWMA bookkeeping
    private volatile double ewmaErrorRate = 0.0;
    private volatile double previousEwmaErrorRate = 0.0;

    // Counters
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final AtomicLong totalSuccesses = new AtomicLong();

    // Latency reservoir — circular buffer of last 100 nanos
    private static final int RESERVOIR_SIZE = 100;
    private final long[] latencyReservoir = new long[RESERVOIR_SIZE];
    private volatile int reservoirHead = 0;
    private volatile int reservoirFill = 0; // how many slots are valid (0..RESERVOIR_SIZE)

    // ── Construction ─────────────────────────────────────────────────────────

    private AdaptiveCircuitBreaker(Config config) {
        this.config = config;
    }

    /** Create an AdaptiveCircuitBreaker with explicit configuration. */
    public static AdaptiveCircuitBreaker create(Config config) {
        return new AdaptiveCircuitBreaker(config);
    }

    /** Create an AdaptiveCircuitBreaker with default configuration. */
    public static AdaptiveCircuitBreaker withDefaults() {
        return new AdaptiveCircuitBreaker(Config.defaults());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Execute a supplier through the circuit breaker.
     *
     * <p>If the circuit is OPEN, throws {@link CircuitOpenException} immediately without invoking
     * the supplier.
     *
     * @param supplier the operation to execute
     * @param <T> return type of the supplier
     * @return the supplier result on success
     * @throws CircuitOpenException if the circuit is OPEN
     * @throws Exception if the supplier throws
     */
    public <T> T execute(Supplier<T> supplier) throws Exception {
        checkAndTransitionToHalfOpen();

        State currentState = state;
        if (currentState == State.OPEN) {
            throw new CircuitOpenException();
        }

        long startNanos = System.nanoTime();
        try {
            T result = supplier.get();
            long elapsedNanos = System.nanoTime() - startNanos;
            recordSuccess(elapsedNanos);
            return result;
        } catch (Exception e) {
            long elapsedNanos = System.nanoTime() - startNanos;
            recordFailure(elapsedNanos);
            throw e;
        }
    }

    /**
     * Execute a supplier, returning a {@link Result} rather than throwing.
     *
     * <p>If the circuit is OPEN, returns {@code Result.err(CircuitOpenException)} without invoking
     * the supplier.
     *
     * @param supplier the operation to execute
     * @param <T> return type of the supplier
     * @return {@code Result.ok(value)} on success; {@code Result.err(exception)} on any failure
     */
    public <T> Result<T, Exception> tryExecute(Supplier<T> supplier) {
        try {
            return Result.ok(execute(supplier));
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /** Return the current circuit state. */
    public State state() {
        checkAndTransitionToHalfOpen();
        return state;
    }

    /** Return a consistent snapshot of all metrics. */
    public Stats stats() {
        synchronized (this) {
            return new Stats(
                    state,
                    ewmaErrorRate,
                    ewmaErrorRate - previousEwmaErrorRate,
                    totalCalls.get(),
                    totalFailures.get(),
                    totalSuccesses.get(),
                    computeP99());
        }
    }

    /**
     * Reset the circuit breaker to CLOSED state and zero all metrics.
     *
     * <p>Useful for testing or manual recovery after a known-good deployment.
     */
    public void reset() {
        synchronized (this) {
            state = State.CLOSED;
            openedAt = null;
            halfOpenSuccesses = 0;
            ewmaErrorRate = 0.0;
            previousEwmaErrorRate = 0.0;
            totalCalls.set(0);
            totalFailures.set(0);
            totalSuccesses.set(0);
            reservoirHead = 0;
            reservoirFill = 0;
            Arrays.fill(latencyReservoir, 0L);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * If the circuit is OPEN and the openDuration has expired, transition to HALF_OPEN so that the
     * next call can probe the downstream service.
     */
    private void checkAndTransitionToHalfOpen() {
        synchronized (this) {
            if (state == State.OPEN
                    && openedAt != null
                    && Instant.now().isAfter(openedAt.plus(config.openDuration()))) {
                state = State.HALF_OPEN;
                halfOpenSuccesses = 0;
            }
        }
    }

    private void recordSuccess(long elapsedNanos) {
        synchronized (this) {
            totalCalls.incrementAndGet();
            totalSuccesses.incrementAndGet();
            addLatencySample(elapsedNanos);
            updateEwma(0.0);

            switch (state) {
                case HALF_OPEN -> {
                    halfOpenSuccesses++;
                    if (halfOpenSuccesses >= config.halfOpenProbes()) {
                        state = State.CLOSED;
                        openedAt = null;
                        halfOpenSuccesses = 0;
                        // Gradually cool the error rate on successful recovery
                        ewmaErrorRate = 0.0;
                        previousEwmaErrorRate = 0.0;
                    }
                }
                case CLOSED -> {
                    // Normal operation — check latency anomaly
                    Duration p99 = computeP99();
                    if (p99.compareTo(config.latencyThreshold()) > 0) {
                        openCircuit();
                    }
                }
                case OPEN -> {
                    // Should not normally arrive here
                }
            }
        }
    }

    private void recordFailure(long elapsedNanos) {
        synchronized (this) {
            totalCalls.incrementAndGet();
            totalFailures.incrementAndGet();
            addLatencySample(elapsedNanos);
            updateEwma(1.0);

            switch (state) {
                case HALF_OPEN -> {
                    // Any probe failure re-opens the circuit immediately
                    openCircuit();
                }
                case CLOSED -> {
                    if (shouldOpen()) {
                        openCircuit();
                    }
                }
                case OPEN -> {
                    // Already open
                }
            }
        }
    }

    /** Determine whether the circuit should open based on EWMA metrics and latency. */
    private boolean shouldOpen() {
        // Condition 1: EWMA error rate has crossed the hard threshold
        if (ewmaErrorRate > config.errorRateThreshold()) {
            return true;
        }
        // Condition 2: Predictive — error rate is rising toward threshold
        double trend = ewmaErrorRate - previousEwmaErrorRate;
        if (ewmaErrorRate > config.predictiveThreshold() && trend > 0.0) {
            return true;
        }
        // Condition 3: Latency spike (checked even if error rate is low)
        Duration p99 = computeP99();
        if (p99.compareTo(config.latencyThreshold()) > 0) {
            return true;
        }
        return false;
    }

    /** Transition to OPEN state. */
    private void openCircuit() {
        state = State.OPEN;
        openedAt = Instant.now();
        halfOpenSuccesses = 0;
    }

    /**
     * Update the EWMA error rate.
     *
     * @param outcome 0.0 for success, 1.0 for failure
     */
    private void updateEwma(double outcome) {
        previousEwmaErrorRate = ewmaErrorRate;
        ewmaErrorRate = config.ewmaAlpha() * outcome + (1.0 - config.ewmaAlpha()) * ewmaErrorRate;
    }

    /** Add a latency measurement to the circular reservoir. */
    private void addLatencySample(long nanos) {
        latencyReservoir[reservoirHead] = nanos;
        reservoirHead = (reservoirHead + 1) % RESERVOIR_SIZE;
        if (reservoirFill < RESERVOIR_SIZE) {
            reservoirFill++;
        }
    }

    /**
     * Compute the approximate P99 latency from the reservoir.
     *
     * <p>Returns {@link Duration#ZERO} when no samples are available.
     */
    private Duration computeP99() {
        int fill = reservoirFill;
        if (fill == 0) return Duration.ZERO;

        long[] samples = Arrays.copyOf(latencyReservoir, fill);
        Arrays.sort(samples);
        // P99 index: ceil(0.99 * fill) - 1, clamped to [0, fill-1]
        int p99Index = Math.min((int) Math.ceil(0.99 * fill) - 1, fill - 1);
        p99Index = Math.max(p99Index, 0);
        return Duration.ofNanos(samples[p99Index]);
    }
}
