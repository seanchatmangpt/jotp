package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcSys;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GenchiGenbutsuAgent — "go and see" live process inspection.
 *
 * <p><strong>Toyota principle:</strong> Genchi genbutsu ("go to the actual place and see the actual
 * thing") means leaders and engineers solve problems at the gemba (workplace), not from reports and
 * dashboards. Direct observation reveals reality that summaries conceal.
 *
 * <p><strong>Armstrong:</strong> "Don't guess the state of a running system — inspect it. {@code
 * sys:get_state/1} tells you exactly what a process holds right now."
 *
 * <p>This agent periodically polls all registered processes via {@link ProcSys#statistics} and
 * {@link ProcSys#getState}, compares observations against declared health thresholds, and produces
 * a ranked list of anomalies. Each inspection cycle writes a timestamped {@link ObservationReport}
 * that shows process name, actual queue depth, throughput, and whether it deviates from expected
 * ranges.
 *
 * <pre>{@code
 * var inspector = GenchiGenbutsuAgent.start(Duration.ofMillis(200));
 *
 * inspector.watch("payments", paymentsProc,
 *     new HealthThreshold(0, 10, 100));  // queueDepth 0–10, minThroughput 100
 * inspector.watch("inventory", inventoryProc,
 *     new HealthThreshold(0, 5, 50));
 *
 * await().atMost(Duration.ofSeconds(2))
 *        .until(() -> !inspector.latestReport().anomalies().isEmpty());
 * }</pre>
 */
public final class GenchiGenbutsuAgent {

    // ── Domain types ──────────────────────────────────────────────────────────

    /**
     * Health thresholds for a watched process.
     *
     * @param minQueueDepth lower bound for queue depth (inclusive)
     * @param maxQueueDepth upper bound for queue depth (inclusive)
     * @param minThroughputIn minimum messages-in per inspection window
     */
    public record HealthThreshold(int minQueueDepth, int maxQueueDepth, long minThroughputIn) {}

    /**
     * One observed data point for a single process.
     *
     * @param processName registered name
     * @param queueDepth current mailbox depth
     * @param messagesIn messages received since last observation
     * @param messagesOut messages processed since last observation
     * @param observedAt timestamp of observation
     * @param healthy whether all thresholds passed
     * @param violations list of threshold violation descriptions
     */
    public record Observation(
            String processName,
            int queueDepth,
            long messagesIn,
            long messagesOut,
            Instant observedAt,
            boolean healthy,
            List<String> violations) {}

    /**
     * Aggregate report produced after each inspection sweep.
     *
     * @param sweepNumber monotonically increasing sweep counter
     * @param observations one per watched process
     * @param anomalies subset of observations that are unhealthy
     * @param producedAt timestamp when the sweep completed
     */
    public record ObservationReport(
            long sweepNumber,
            List<Observation> observations,
            List<Observation> anomalies,
            Instant producedAt) {

        public boolean allHealthy() {
            return anomalies.isEmpty();
        }
    }

    // ── Watched process registry ──────────────────────────────────────────────

    private record WatchedEntry(
            String name,
            Proc<?, ?> proc,
            HealthThreshold threshold,
            long lastMessagesIn,
            long lastMessagesOut) {}

    // ── Messages ──────────────────────────────────────────────────────────────

    /** Messages accepted by the inspector's own process. */
    public sealed interface InspectorMsg
            permits InspectorMsg.Sweep,
                    InspectorMsg.Watch,
                    InspectorMsg.Unwatch,
                    InspectorMsg.LatestReport {

        /** Internal: perform one inspection sweep. */
        record Sweep() implements InspectorMsg {}

        /** Register a process for observation. */
        record Watch(String name, Proc<?, ?> proc, HealthThreshold threshold)
                implements InspectorMsg {}

        /** Deregister a process. */
        record Unwatch(String name) implements InspectorMsg {}

        /** Query the most recent report (ask() pattern). */
        record LatestReport() implements InspectorMsg {}
    }

    /** State held by the inspector process. */
    public record InspectorState(
            Map<String, WatchedEntry> watched, List<ObservationReport> reports, long sweepCount) {

        public static InspectorState empty() {
            return new InspectorState(new ConcurrentHashMap<>(), new ArrayList<>(), 0L);
        }

        public InspectorState sweep() {
            long sweep = sweepCount + 1;
            var observations = new ArrayList<Observation>();
            var updatedWatched = new ConcurrentHashMap<>(watched);

            for (var entry : watched.values()) {
                var stats = ProcSys.statistics(entry.proc());
                long deltaIn = stats.messagesIn() - entry.lastMessagesIn();
                long deltaOut = stats.messagesOut() - entry.lastMessagesOut();
                var violations = new ArrayList<String>();
                var t = entry.threshold();
                if (stats.queueDepth() < t.minQueueDepth()) {
                    violations.add(
                            "queueDepth " + stats.queueDepth() + " < min " + t.minQueueDepth());
                }
                if (stats.queueDepth() > t.maxQueueDepth()) {
                    violations.add(
                            "queueDepth " + stats.queueDepth() + " > max " + t.maxQueueDepth());
                }
                if (deltaIn < t.minThroughputIn()) {
                    violations.add("throughputIn " + deltaIn + " < min " + t.minThroughputIn());
                }
                observations.add(
                        new Observation(
                                entry.name(),
                                stats.queueDepth(),
                                deltaIn,
                                deltaOut,
                                Instant.now(),
                                violations.isEmpty(),
                                List.copyOf(violations)));
                updatedWatched.put(
                        entry.name(),
                        new WatchedEntry(
                                entry.name(),
                                entry.proc(),
                                entry.threshold(),
                                stats.messagesIn(),
                                stats.messagesOut()));
            }

            var anomalies = observations.stream().filter(o -> !o.healthy()).toList();
            var report =
                    new ObservationReport(
                            sweep,
                            List.copyOf(observations),
                            List.copyOf(anomalies),
                            Instant.now());
            var newReports = new ArrayList<>(reports);
            newReports.add(report);
            return new InspectorState(updatedWatched, newReports, sweep);
        }

        public ObservationReport latestReport() {
            if (reports.isEmpty())
                return new ObservationReport(0, List.of(), List.of(), Instant.now());
            return reports.getLast();
        }
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    private static InspectorState handle(InspectorState state, InspectorMsg msg) {
        return switch (msg) {
            case InspectorMsg.Sweep() -> state.sweep();
            case InspectorMsg.Watch(var name, var proc, var threshold) -> {
                state.watched().put(name, new WatchedEntry(name, proc, threshold, 0L, 0L));
                yield state;
            }
            case InspectorMsg.Unwatch(var name) -> {
                state.watched().remove(name);
                yield state;
            }
            case InspectorMsg.LatestReport() -> state; // ask() delivers state
        };
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private final Proc<InspectorState, InspectorMsg> proc;
    private final io.github.seanchatmangpt.jotp.ProcTimer.TimerRef sweepTimer;

    private GenchiGenbutsuAgent(Duration sweepInterval) {
        this.proc = new Proc<>(InspectorState.empty(), GenchiGenbutsuAgent::handle);
        this.sweepTimer =
                io.github.seanchatmangpt.jotp.ProcTimer.sendInterval(
                        sweepInterval.toMillis(), proc, new InspectorMsg.Sweep());
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Starts the GenchiGenbutsu inspector with the given sweep interval.
     *
     * @param sweepInterval how frequently to inspect all watched processes
     * @return running agent
     */
    public static GenchiGenbutsuAgent start(Duration sweepInterval) {
        return new GenchiGenbutsuAgent(sweepInterval);
    }

    /**
     * Register a process for observation.
     *
     * @param name display name for reports
     * @param proc the process to watch
     * @param threshold health thresholds to check on each sweep
     */
    public void watch(String name, Proc<?, ?> proc, HealthThreshold threshold) {
        this.proc.tell(new InspectorMsg.Watch(name, proc, threshold));
    }

    /** Remove a process from observation. */
    public void unwatch(String name) {
        proc.tell(new InspectorMsg.Unwatch(name));
    }

    /** Returns the most recent observation report, blocking up to {@code timeout}. */
    public ObservationReport latestReport(Duration timeout) {
        return proc.ask(new InspectorMsg.LatestReport(), timeout).join().latestReport();
    }

    /** Force an immediate inspection sweep (useful in tests). */
    public void forceSweep() {
        proc.tell(new InspectorMsg.Sweep());
    }

    /** Stops the sweep timer and the inspector process. */
    public void stop() throws InterruptedException {
        sweepTimer.cancel();
        proc.stop();
    }
}
