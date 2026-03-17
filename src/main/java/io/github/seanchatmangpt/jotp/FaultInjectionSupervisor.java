package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Chaos testing wrapper that injects faults into supervised processes at configurable moments.
 *
 * <p>Java developers who want deterministic distributed systems need predictable failure injection
 * for testing. This wrapper intercepts handler calls and throws {@link InjectedFault} at precisely
 * the right moment, causing the underlying {@link Supervisor} to apply its real restart strategy.
 *
 * <p>The supervisor is never modified — fault injection is purely in the handler wrapper. Tests
 * exercise real restart semantics without mocking anything.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 * var chaos = FaultInjectionSupervisor.wrap(sup);
 *
 * // Crash after the 3rd message is delivered
 * var ref = chaos.superviseWithFault("worker", 0,
 *     (state, msg) -> state + 1,
 *     FaultSpec.afterMessages(3));
 *
 * ref.tell(1); ref.tell(2); ref.tell(3); // 3rd succeeds, 4th triggers crash + restart
 * await().atMost(Duration.ofSeconds(2)).until(() -> chaos.faultsInjected() == 1);
 *
 * // After restart the process runs normally — no second fault
 * ref.tell(4); ref.tell(5); // pass through
 * assertThat(chaos.faultsInjected()).isEqualTo(1);
 * }</pre>
 *
 * <p><strong>One-shot semantics:</strong> Each {@link #superviseWithFault} call injects at most one
 * fault for that child registration. After the fault fires, the handler passes through normally for
 * all subsequent messages — including messages to the restarted process. This models the common
 * scenario of testing that a supervisor recovers from a single transient failure.
 *
 * @see Supervisor
 * @see FaultSpec
 */
public final class FaultInjectionSupervisor {

    /**
     * Condition that triggers a fault injection.
     *
     * <p>Three built-in conditions:
     *
     * <ul>
     *   <li>{@link AfterMessages} — crash after N messages have been processed successfully
     *   <li>{@link AfterDuration} — crash when wall-clock time since process start exceeds a
     *       duration
     *   <li>{@link OnMessage} — crash when a predicate matches the incoming message
     * </ul>
     */
    public sealed interface FaultSpec permits FaultSpec.AfterMessages, FaultSpec.AfterDuration, FaultSpec.OnMessage {

        /**
         * Crash after {@code count} messages have been processed successfully. The crash occurs on
         * the ({@code count}+1)-th message attempt.
         *
         * @param count number of messages to process before crashing (must be &gt;= 0)
         */
        record AfterMessages(int count) implements FaultSpec {}

        /**
         * Crash when the elapsed time since the process wrapper was created exceeds {@code
         * duration}. Useful for time-based fault injection in integration tests.
         *
         * @param duration how long to wait before injecting the fault
         */
        record AfterDuration(Duration duration) implements FaultSpec {}

        /**
         * Crash when the given predicate returns {@code true} for an incoming message. The
         * predicate receives the raw message and should handle type checking internally.
         *
         * @param predicate message predicate — return {@code true} to trigger the fault
         */
        record OnMessage(Predicate<Object> predicate) implements FaultSpec {}

        // ── Factory conveniences ────────────────────────────────────────────────

        /** Crash after {@code count} messages have been processed successfully. */
        static FaultSpec afterMessages(int count) {
            return new AfterMessages(count);
        }

        /** Crash when elapsed time since wrapper creation exceeds {@code duration}. */
        static FaultSpec afterDuration(Duration duration) {
            return new AfterDuration(duration);
        }

        /**
         * Crash when the message matches {@code predicate}. The cast is unchecked; the predicate
         * receives only messages of type {@code M}, so a class-check cast inside the predicate is
         * safe.
         *
         * <pre>{@code
         * FaultSpec.onMessage(msg -> msg instanceof MyMsg.Crash)
         * }</pre>
         */
        @SuppressWarnings("unchecked")
        static <M> FaultSpec onMessage(Predicate<M> predicate) {
            return new OnMessage((Predicate<Object>) (Predicate<?>) predicate);
        }
    }

    /**
     * Exception thrown when the fault condition is met. Extends {@link RuntimeException} so the
     * process crashes and the supervisor applies its restart strategy.
     */
    public static final class InjectedFault extends RuntimeException {
        InjectedFault(String reason) {
            super("FaultInjection: " + reason);
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────────

    private final Supervisor supervisor;
    private final AtomicInteger faultsInjectedTotal = new AtomicInteger(0);

    // ── Constructor / factory ─────────────────────────────────────────────────────

    private FaultInjectionSupervisor(Supervisor supervisor) {
        this.supervisor = supervisor;
    }

    /**
     * Wrap an existing supervisor for fault injection. The supervisor is not modified; only handler
     * wrappers are injected when children are registered via {@link #superviseWithFault}.
     *
     * @param supervisor the supervisor that will restart crashed processes
     * @return a new {@code FaultInjectionSupervisor} backed by the given supervisor
     */
    public static FaultInjectionSupervisor wrap(Supervisor supervisor) {
        return new FaultInjectionSupervisor(supervisor);
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Supervise a process with fault injection using the simple {@link
     * Supervisor#supervise(String, Object, BiFunction)} API.
     *
     * <p>The real {@code handler} runs normally until the {@link FaultSpec} condition is met, at
     * which point an {@link InjectedFault} is thrown. The supervisor restarts the process; after
     * the restart the handler passes through normally (one-shot semantics).
     *
     * @param id unique child id in the supervisor
     * @param initial initial process state
     * @param handler the real message handler
     * @param faultSpec when to inject the fault
     * @param <S> state type
     * @param <M> message type
     * @return a stable {@link ProcRef} that survives restarts
     */
    public <S, M> ProcRef<S, M> superviseWithFault(
            String id, S initial, BiFunction<S, M, S> handler, FaultSpec faultSpec) {
        return supervisor.supervise(id, initial, wrappedHandler(handler, faultSpec));
    }

    /**
     * Supervise a process using an explicit {@link Supervisor.ChildSpec} with fault injection. All
     * other spec fields (restart type, shutdown, etc.) are preserved exactly.
     *
     * @param spec the child spec
     * @param faultSpec when to inject the fault
     * @param <S> state type
     * @param <M> message type
     * @return a stable {@link ProcRef} that survives restarts
     */
    public <S, M> ProcRef<S, M> superviseWithFault(
            Supervisor.ChildSpec<S, M> spec, FaultSpec faultSpec) {
        var injecting =
                new Supervisor.ChildSpec<>(
                        spec.id(),
                        spec.stateFactory(),
                        wrappedHandler(spec.handler(), faultSpec),
                        spec.restart(),
                        spec.shutdown(),
                        spec.type(),
                        spec.significant());
        return supervisor.startChild(injecting);
    }

    /**
     * Total number of faults injected across all processes managed by this instance since creation
     * or the last {@link #resetFaultCount}.
     */
    public int faultsInjected() {
        return faultsInjectedTotal.get();
    }

    /** Reset the total fault counter to zero. */
    public void resetFaultCount() {
        faultsInjectedTotal.set(0);
    }

    /** Returns the underlying supervisor (for querying children, shutdown, etc.). */
    public Supervisor supervisor() {
        return supervisor;
    }

    // ── Internal: handler wrapping ────────────────────────────────────────────────

    /**
     * Create a wrapper around {@code handler} that injects a fault when {@code faultSpec} fires.
     *
     * <p>Each call creates a fresh closure with its own counters and state. Since {@link Supervisor}
     * reuses the same handler BiFunction across restarts, the fault fires exactly once per
     * {@link #superviseWithFault} registration (one-shot semantics).
     */
    private <S, M> BiFunction<S, M, S> wrappedHandler(
            BiFunction<S, M, S> handler, FaultSpec faultSpec) {
        var messageCount = new AtomicInteger(0); // messages processed successfully so far
        var startTime = Instant.now();
        var faultFired = new AtomicBoolean(false);

        return (state, msg) -> {
            if (!faultFired.get()) {
                boolean shouldFault =
                        switch (faultSpec) {
                            case FaultSpec.AfterMessages(int n) -> messageCount.get() >= n;
                            case FaultSpec.AfterDuration(Duration d) ->
                                    Duration.between(startTime, Instant.now()).compareTo(d) >= 0;
                            case FaultSpec.OnMessage(Predicate<Object> pred) -> pred.test(msg);
                        };
                if (shouldFault) {
                    faultFired.set(true);
                    faultsInjectedTotal.incrementAndGet();
                    throw new InjectedFault(
                            "after "
                                    + messageCount.get()
                                    + " messages, spec="
                                    + faultSpec.getClass().getSimpleName());
                }
            }
            S next = handler.apply(state, msg);
            messageCount.incrementAndGet();
            return next;
        };
    }
}
