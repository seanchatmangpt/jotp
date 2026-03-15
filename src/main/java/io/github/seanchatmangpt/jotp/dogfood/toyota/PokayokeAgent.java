package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * PokayokeAgent — error-proofing pipeline that catches defects at the source.
 *
 * <p><strong>Toyota principle:</strong> Poka-yoke ("mistake-proofing") devices prevent defects from
 * occurring or from being passed to the next process. Each device is a low-cost, focused check
 * applied at the exact point where a defect could arise.
 *
 * <p><strong>Armstrong:</strong> "The cheapest bug to fix is one that never enters the system. A
 * process that validates its own inputs cannot be held responsible for upstream chaos."
 *
 * <p>The agent chains an ordered sequence of named validators ("poka-yoke devices"). Each device is
 * a {@link Predicate} applied to a work item. Items that fail any device are rejected with a
 * detailed report; clean items are released to the output queue. The pipeline is itself a {@link
 * Proc}, so validation throughput is fully concurrent.
 *
 * <pre>{@code
 * var pipeline = PokayokeAgent.<Order>builder()
 *     .device("non-null-id",      o -> o.id() != null && !o.id().isBlank())
 *     .device("positive-amount",  o -> o.amount() > 0)
 *     .device("valid-currency",   o -> Set.of("USD","EUR","GBP").contains(o.currency()))
 *     .build();
 *
 * var result = pipeline.validate(new Order("ORD-1", 42.0, "USD"), Duration.ofSeconds(1));
 * assertThat(result).isInstanceOf(ValidationResult.Passed.class);
 * }</pre>
 *
 * @param <T> the work-item type flowing through the pipeline
 */
public final class PokayokeAgent<T> {

    // ── Domain types ──────────────────────────────────────────────────────────

    /** A named poka-yoke validation device. */
    public record Device<T>(String name, Predicate<T> check) {
        /** Apply the device. Returns a failure detail if the check fails. */
        public DeviceOutcome apply(T item) {
            try {
                return check.test(item)
                        ? new DeviceOutcome.Pass(name)
                        : new DeviceOutcome.Fail(name, "Device '" + name + "' rejected item");
            } catch (Exception ex) {
                return new DeviceOutcome.Fail(
                        name, "Device '" + name + "' threw: " + ex.getMessage());
            }
        }
    }

    /** Outcome of a single device check. */
    public sealed interface DeviceOutcome permits DeviceOutcome.Pass, DeviceOutcome.Fail {
        record Pass(String deviceName) implements DeviceOutcome {}

        record Fail(String deviceName, String reason) implements DeviceOutcome {}
    }

    /**
     * Overall pipeline result for a work item.
     *
     * @param <T> the work-item type
     */
    public sealed interface ValidationResult<T>
            permits ValidationResult.Passed, ValidationResult.Rejected {

        /** Item passed all devices. */
        record Passed<T>(T item, List<DeviceOutcome.Pass> passes) implements ValidationResult<T> {}

        /** Item was rejected by one or more devices. */
        record Rejected<T>(
                T item, List<DeviceOutcome.Fail> failures, List<DeviceOutcome.Pass> passes)
                implements ValidationResult<T> {}
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Messages accepted by the PokayokeAgent process. */
    public sealed interface PokaMsg<T> permits PokaMsg.Validate, PokaMsg.GetStats {

        /** Validate a single work item through all devices (ask() pattern). */
        record Validate<T>(T item) implements PokaMsg<T> {}

        /** Query validation statistics (ask() pattern). */
        record GetStats<T>() implements PokaMsg<T> {}
    }

    /** Accumulated validation statistics. */
    public record PokaStats(long totalValidated, long totalPassed, long totalRejected) {
        public static PokaStats zero() {
            return new PokaStats(0, 0, 0);
        }

        public double passRate() {
            if (totalValidated == 0) return 1.0;
            return (double) totalPassed / totalValidated;
        }
    }

    /** Internal process state. */
    public record PokaState<T>(
            List<Device<T>> devices, PokaStats stats, List<ValidationResult<T>> results) {

        public static <T> PokaState<T> of(List<Device<T>> devices) {
            return new PokaState<>(List.copyOf(devices), PokaStats.zero(), new ArrayList<>());
        }

        public PokaState<T> process(T item) {
            var passes = new ArrayList<DeviceOutcome.Pass>();
            var failures = new ArrayList<DeviceOutcome.Fail>();
            for (Device<T> device : devices) {
                switch (device.apply(item)) {
                    case DeviceOutcome.Pass p -> passes.add(p);
                    case DeviceOutcome.Fail f -> failures.add(f);
                }
            }
            ValidationResult<T> result =
                    failures.isEmpty()
                            ? new ValidationResult.Passed<>(item, List.copyOf(passes))
                            : new ValidationResult.Rejected<>(
                                    item, List.copyOf(failures), List.copyOf(passes));
            var newResults = new ArrayList<>(results);
            newResults.add(result);
            long passed = stats.totalPassed() + (failures.isEmpty() ? 1 : 0);
            long rejected = stats.totalRejected() + (failures.isEmpty() ? 0 : 1);
            return new PokaState<>(
                    devices,
                    new PokaStats(stats.totalValidated() + 1, passed, rejected),
                    newResults);
        }
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> PokaState<T> handle(PokaState<T> state, PokaMsg<T> msg) {
        return switch (msg) {
            case PokaMsg.Validate<T>(var item) -> state.process(item);
            case PokaMsg.GetStats<T>() -> state; // ask() delivers state
        };
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private final Proc<PokaState<T>, PokaMsg<T>> proc;

    private PokayokeAgent(List<Device<T>> devices) {
        this.proc = new Proc<>(PokaState.of(devices), PokayokeAgent::handle);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validate {@code item} synchronously through all devices.
     *
     * @param item work item to validate
     * @param timeout maximum wait time
     * @return {@link ValidationResult} indicating pass or rejection with device failures
     */
    public ValidationResult<T> validate(T item, Duration timeout) {
        PokaState<T> state = proc.ask(new PokaMsg.Validate<>(item), timeout).join();
        return state.results().getLast();
    }

    /**
     * Returns accumulated validation statistics.
     *
     * @param timeout maximum wait time
     */
    public PokaStats stats(Duration timeout) {
        return proc.ask(new PokaMsg.GetStats<>(), timeout).join().stats();
    }

    /** Stops the agent process. */
    public void stop() throws InterruptedException {
        proc.stop();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Fluent builder for constructing a PokayokeAgent with an ordered device chain. */
    public static final class Builder<T> {
        private final List<Device<T>> devices = new ArrayList<>();

        /**
         * Add a named validation device.
         *
         * @param name human-readable device name (printed in rejection reports)
         * @param check predicate returning {@code true} if the item passes
         */
        public Builder<T> device(String name, Predicate<T> check) {
            devices.add(new Device<>(name, check));
            return this;
        }

        /** Build and start the PokayokeAgent. */
        public PokayokeAgent<T> build() {
            if (devices.isEmpty()) {
                throw new IllegalStateException("At least one poka-yoke device is required");
            }
            return new PokayokeAgent<>(devices);
        }
    }

    /**
     * Creates a new {@link Builder} for the given work-item type.
     *
     * @param <T> work-item type
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }
}
