package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcTimer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * HeijunkaAgent — production leveling that smooths uneven demand into a uniform dispatch rhythm.
 *
 * <p><strong>Toyota principle:</strong> Heijunka ("level scheduling") eliminates mura (unevenness)
 * by distributing work of different types evenly across time. Rather than processing all work of
 * one type in a batch, a heijunka box interleaves types according to their mix ratio, so each time
 * box delivers a balanced set of items.
 *
 * <p><strong>Armstrong:</strong> "A process under constant irregular load is a process that will
 * eventually crash. Level the load; let the system breathe."
 *
 * <p>The agent maintains a heijunka box — a two-dimensional structure of rows (work types) and
 * columns (time pitches). A {@link ProcTimer} fires on each pitch interval and dispatches one item
 * per active row, interleaved round-robin. Surplus demand queues up; idle rows produce nothing.
 *
 * <pre>{@code
 * var heijunka = HeijunkaAgent.start(
 *     List.of("feature", "bugfix", "chore"),
 *     Duration.ofMillis(100)   // pitch interval
 * );
 * heijunka.submit("feature", new HeijunkaItem("FEA-1", "Add login"));
 * heijunka.submit("feature", new HeijunkaItem("FEA-2", "Add logout"));
 * heijunka.submit("bugfix",  new HeijunkaItem("BUG-1", "Fix NPE"));
 * // After two pitches: FEA-1, BUG-1, FEA-2 dispatched in order
 * }</pre>
 */
public final class HeijunkaAgent {

    // ── Domain types ──────────────────────────────────────────────────────────

    /** A single unit of work entering the heijunka box. */
    public record HeijunkaItem(String id, String description) {}

    /** A dispatched item: item + the pitch number when it was released. */
    public record DispatchedItem(
            HeijunkaItem item, String workType, long pitch, Instant dispatchedAt) {}

    // ── State ─────────────────────────────────────────────────────────────────

    /** Messages accepted by the HeijunkaAgent process. */
    public sealed interface HeijunkaMsg
            permits HeijunkaMsg.Submit,
                    HeijunkaMsg.Pitch,
                    HeijunkaMsg.GetDispatched,
                    HeijunkaMsg.GetMetrics,
                    HeijunkaMsg.Stop {

        /** Submit a work item to a named work-type row. */
        record Submit(String workType, HeijunkaItem item) implements HeijunkaMsg {}

        /** Internal timer tick: advance one pitch and dispatch one item per row. */
        record Pitch() implements HeijunkaMsg {}

        /** Query the list of all dispatched items (ask() pattern). */
        record GetDispatched() implements HeijunkaMsg {}

        /** Query leveling metrics (ask() pattern). */
        record GetMetrics() implements HeijunkaMsg {}

        /** Gracefully stop the agent. */
        record Stop() implements HeijunkaMsg {}
    }

    /**
     * State of the heijunka box.
     *
     * @param rows queued work items per work type
     * @param workTypes ordered list of work-type names (determines round-robin sequence)
     * @param dispatched all items dispatched so far
     * @param pitchCount total pitch intervals elapsed
     * @param rowIndex current round-robin position in {@code workTypes}
     */
    public record HeijunkaState(
            Map<String, Queue<HeijunkaItem>> rows,
            List<String> workTypes,
            List<DispatchedItem> dispatched,
            long pitchCount,
            int rowIndex) {

        public static HeijunkaState of(List<String> workTypes) {
            var rows = new HashMap<String, Queue<HeijunkaItem>>();
            workTypes.forEach(t -> rows.put(t, new ArrayDeque<>()));
            return new HeijunkaState(rows, List.copyOf(workTypes), new ArrayList<>(), 0L, 0);
        }

        /**
         * Execute one pitch: dispatch one item from each non-empty row in round-robin order.
         *
         * @return updated state after the pitch
         */
        public HeijunkaState pitch() {
            long newPitch = pitchCount + 1;
            var newDispatched = new ArrayList<>(dispatched);
            // Starting from current rowIndex, sweep all types once
            for (int i = 0; i < workTypes.size(); i++) {
                int idx = (rowIndex + i) % workTypes.size();
                String type = workTypes.get(idx);
                HeijunkaItem item = rows.get(type).poll();
                if (item != null) {
                    newDispatched.add(new DispatchedItem(item, type, newPitch, Instant.now()));
                }
            }
            int newRowIndex = (rowIndex + 1) % workTypes.size();
            return new HeijunkaState(rows, workTypes, newDispatched, newPitch, newRowIndex);
        }

        /** Total items currently queued across all rows. */
        public int totalQueued() {
            return rows.values().stream().mapToInt(Queue::size).sum();
        }

        /** Items dispatched per work type. */
        public Map<String, Long> dispatchedByType() {
            var counts = new HashMap<String, Long>();
            workTypes.forEach(t -> counts.put(t, 0L));
            dispatched.forEach(d -> counts.merge(d.workType(), 1L, Long::sum));
            return Collections.unmodifiableMap(counts);
        }
    }

    /** Summary metrics for the heijunka box. */
    public record HeijunkaMetrics(
            long pitchCount,
            int totalQueued,
            int totalDispatched,
            Map<String, Long> dispatchedByType) {}

    // ── Handler ───────────────────────────────────────────────────────────────

    private static HeijunkaState handle(HeijunkaState state, HeijunkaMsg msg) {
        return switch (msg) {
            case HeijunkaMsg.Submit(var type, var item) -> {
                var q = state.rows().get(type);
                if (q == null) throw new IllegalArgumentException("Unknown work type: " + type);
                q.offer(item);
                yield state;
            }
            case HeijunkaMsg.Pitch() -> state.pitch();
            case HeijunkaMsg.GetDispatched() -> state; // ask() delivers state
            case HeijunkaMsg.GetMetrics() -> state; // ask() delivers state
            case HeijunkaMsg.Stop() -> {
                Thread.currentThread().interrupt();
                yield state;
            }
        };
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private final Proc<HeijunkaState, HeijunkaMsg> proc;
    private final ProcTimer.TimerRef pitchTimer;
    private final Duration pitchInterval;

    private HeijunkaAgent(List<String> workTypes, Duration pitchInterval) {
        this.pitchInterval = pitchInterval;
        this.proc = new Proc<>(HeijunkaState.of(workTypes), HeijunkaAgent::handle);
        this.pitchTimer =
                ProcTimer.sendInterval(pitchInterval.toMillis(), proc, new HeijunkaMsg.Pitch());
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Starts a HeijunkaAgent for the given work types and pitch interval.
     *
     * @param workTypes ordered list of work-type names (e.g. "feature", "bugfix", "chore")
     * @param pitchInterval time between dispatch pitches
     * @return running agent
     */
    public static HeijunkaAgent start(List<String> workTypes, Duration pitchInterval) {
        return new HeijunkaAgent(workTypes, pitchInterval);
    }

    /**
     * Submit a work item to a named row.
     *
     * @param workType work-type row name
     * @param item item to enqueue
     */
    public void submit(String workType, HeijunkaItem item) {
        proc.tell(new HeijunkaMsg.Submit(workType, item));
    }

    /** Returns all dispatched items so far, blocking up to {@code timeout}. */
    public List<DispatchedItem> dispatched(Duration timeout) {
        return proc.ask(new HeijunkaMsg.GetDispatched(), timeout).join().dispatched();
    }

    /** Returns current leveling metrics, blocking up to {@code timeout}. */
    public HeijunkaMetrics metrics(Duration timeout) {
        HeijunkaState s = proc.ask(new HeijunkaMsg.GetMetrics(), timeout).join();
        return new HeijunkaMetrics(
                s.pitchCount(), s.totalQueued(), s.dispatched().size(), s.dispatchedByType());
    }

    /** Returns the underlying process (for ProcSys introspection, etc.). */
    public Proc<HeijunkaState, HeijunkaMsg> proc() {
        return proc;
    }

    /** Stops the pitch timer and the agent process. */
    public void stop() throws InterruptedException {
        pitchTimer.cancel();
        proc.stop();
    }
}
