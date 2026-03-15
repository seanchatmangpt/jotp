package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcTimer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * TaktTimeAgent — meters output at a pace precisely matching customer demand rate.
 *
 * <p><strong>Toyota principle:</strong> Takt time (from German <em>Takt</em>, "beat") is the
 * maximum time available to produce one unit while meeting demand. It synchronises production pace
 * to customer pull: too fast causes overproduction; too slow causes delay. The formula is: {@code
 * takt = available production time / customer demand rate}.
 *
 * <p><strong>Armstrong:</strong> "A process must know its rhythm. When a process produces faster
 * than the consumer can absorb, the buffer grows until the system crashes."
 *
 * <p>The agent maintains a {@link ProcTimer} that fires every {@code taktTime} duration. On each
 * tick, if a work item is available, it is dispatched to the configured consumer. The agent tracks
 * actual cycle time versus takt time and reports over/under-production.
 *
 * <pre>{@code
 * var agent = TaktTimeAgent.<String>start(
 *     Duration.ofMillis(100),   // takt time
 *     item -> System.out.println("Produced: " + item)
 * );
 * agent.enqueue("widget-1");
 * agent.enqueue("widget-2");
 * // Items dispatched at 100ms intervals
 * var report = agent.report(Duration.ofSeconds(1));
 * assertThat(report.dispatchedCount()).isEqualTo(2);
 * }</pre>
 *
 * @param <T> the work-item type dispatched on each takt beat
 */
public final class TaktTimeAgent<T> {

    // ── Domain types ──────────────────────────────────────────────────────────

    /**
     * Disposition of a single takt tick.
     *
     * @param <I> work-item type
     * @param tickNumber monotonically increasing tick counter
     * @param dispatchedItem item dispatched on this tick (null if queue was empty)
     * @param tickAt time the tick fired
     * @param cycleTime duration since the previous tick
     * @param status whether this tick was on-time, ahead, or behind takt
     */
    public record TaktTick<I>(
            long tickNumber,
            I dispatchedItem,
            Instant tickAt,
            Duration cycleTime,
            TickStatus status) {

        public boolean produced() {
            return dispatchedItem != null;
        }
    }

    /** Production status relative to takt time. */
    public enum TickStatus {
        ON_TIME,
        BEHIND,
        EMPTY
    }

    /** Aggregate production report. */
    public record TaktReport(
            long totalTicks,
            long dispatchedCount,
            long emptyTicks,
            long behindTicks,
            Duration taktTime,
            double efficiency,
            List<TaktTick<?>> ticks) {

        public static TaktReport from(TaktState<?> state) {
            long behind =
                    state.ticks().stream().filter(t -> t.status() == TickStatus.BEHIND).count();
            long empty = state.ticks().stream().filter(t -> t.status() == TickStatus.EMPTY).count();
            long dispatched = state.ticks().stream().filter(TaktTick::produced).count();
            double eff = state.totalTicks() == 0 ? 1.0 : (double) dispatched / state.totalTicks();
            return new TaktReport(
                    state.totalTicks(),
                    dispatched,
                    empty,
                    behind,
                    state.taktTime(),
                    eff,
                    List.copyOf(state.ticks()));
        }
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    /** Messages accepted by the TaktTimeAgent process. */
    public sealed interface TaktMsg<T> permits TaktMsg.Enqueue, TaktMsg.Tick, TaktMsg.GetReport {

        /** Enqueue a work item for takt-metered dispatch. */
        record Enqueue<T>(T item) implements TaktMsg<T> {}

        /** Internal timer tick: dispatch one item if available. */
        record Tick<T>() implements TaktMsg<T> {}

        /** Query the current production report (ask() pattern). */
        record GetReport<T>() implements TaktMsg<T> {}
    }

    /** State held by the TaktTimeAgent process. */
    public record TaktState<T>(
            java.util.Queue<T> queue,
            List<TaktTick<T>> ticks,
            long totalTicks,
            Duration taktTime,
            Instant lastTickAt,
            Consumer<T> consumer) {

        public static <T> TaktState<T> of(Duration taktTime, Consumer<T> consumer) {
            return new TaktState<>(
                    new java.util.ArrayDeque<>(),
                    new ArrayList<>(),
                    0L,
                    taktTime,
                    Instant.now(),
                    consumer);
        }

        public TaktState<T> tick() {
            Instant now = Instant.now();
            Duration cycleTime = Duration.between(lastTickAt, now);
            long tickNum = totalTicks + 1;
            T item = queue.poll();
            TickStatus status;
            if (item == null) {
                status = TickStatus.EMPTY;
            } else {
                consumer.accept(item);
                status =
                        cycleTime.compareTo(taktTime.multipliedBy(11).dividedBy(10)) > 0
                                ? TickStatus.BEHIND
                                : TickStatus.ON_TIME;
            }
            var newTicks = new ArrayList<>(ticks);
            newTicks.add(new TaktTick<>(tickNum, item, now, cycleTime, status));
            return new TaktState<>(queue, newTicks, tickNum, taktTime, now, consumer);
        }
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> TaktState<T> handle(TaktState<T> state, TaktMsg<T> msg) {
        return switch (msg) {
            case TaktMsg.Enqueue<T>(var item) -> {
                state.queue().offer(item);
                yield state;
            }
            case TaktMsg.Tick<T>() -> state.tick();
            case TaktMsg.GetReport<T>() -> state; // ask() delivers state
        };
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private final Proc<TaktState<T>, TaktMsg<T>> proc;
    private final ProcTimer.TimerRef taktTimer;

    private TaktTimeAgent(Duration taktTime, Consumer<T> consumer) {
        this.proc = new Proc<>(TaktState.of(taktTime, consumer), TaktTimeAgent::handle);
        this.taktTimer = ProcTimer.sendInterval(taktTime.toMillis(), proc, new TaktMsg.Tick<>());
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Starts a TaktTimeAgent that dispatches items at the specified takt interval.
     *
     * @param taktTime time between dispatch ticks (one unit per tick)
     * @param consumer called on each tick when an item is available
     * @param <T> work-item type
     * @return running agent
     */
    public static <T> TaktTimeAgent<T> start(Duration taktTime, Consumer<T> consumer) {
        return new TaktTimeAgent<>(taktTime, consumer);
    }

    /**
     * Enqueue a work item for takt-metered dispatch.
     *
     * @param item item to produce on the next available takt beat
     */
    public void enqueue(T item) {
        proc.tell(new TaktMsg.Enqueue<>(item));
    }

    /** Returns the current production report, blocking up to {@code timeout}. */
    public TaktReport report(Duration timeout) {
        return TaktReport.from(proc.ask(new TaktMsg.GetReport<>(), timeout).join());
    }

    /** Returns the underlying process. */
    public Proc<TaktState<T>, TaktMsg<T>> proc() {
        return proc;
    }

    /** Stops the takt timer and the agent process. */
    public void stop() throws InterruptedException {
        taktTimer.cancel();
        proc.stop();
    }
}
