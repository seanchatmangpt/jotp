package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcTimer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AndonCordAgent — stop-the-line alert escalation via typed pub/sub.
 *
 * <p><strong>Toyota principle:</strong> The Andon cord (or light board) lets any worker halt
 * production the moment an abnormality appears. If the problem is not acknowledged within a fixed
 * response time, escalation occurs automatically — supervisors are paged, then management, then the
 * entire value stream stops.
 *
 * <p><strong>Armstrong:</strong> "Errors are first-class citizens. A process that detects an
 * anomaly should broadcast it immediately and let the supervision tree decide what to do."
 *
 * <p>The agent wraps an {@link EventManager} that fans out {@link AndonEvent} instances to all
 * registered listeners. Escalation is driven by a {@link ProcTimer} per open alert: if not
 * acknowledged within the configured window, an {@link AndonEvent.Escalate} event is automatically
 * published.
 *
 * <pre>{@code
 * var andon = AndonCordAgent.start(Duration.ofSeconds(5));
 * andon.addListener(event -> System.out.println("ANDON: " + event));
 *
 * andon.pull("station-3", "Hydraulic pressure drop");
 * // ... if not acknowledged within 5s:
 * // AndonEvent.Escalate("station-3", 1, ...) fires automatically
 *
 * andon.acknowledge("station-3");
 * andon.resolve("station-3", "Pump seal replaced");
 * }</pre>
 */
public final class AndonCordAgent {

    // ── Events ────────────────────────────────────────────────────────────────

    /** Typed event hierarchy published on the Andon bus. */
    public sealed interface AndonEvent
            permits AndonEvent.Pull,
                    AndonEvent.Acknowledge,
                    AndonEvent.Resolve,
                    AndonEvent.Escalate {

        /** Worker pulls the Andon cord to signal an abnormality. */
        record Pull(String station, String problem, Instant pulledAt) implements AndonEvent {}

        /** Supervisor acknowledges the alert; escalation timer is cancelled. */
        record Acknowledge(String station, Instant acknowledgedAt) implements AndonEvent {}

        /** Problem resolved; station returns to normal operation. */
        record Resolve(String station, String resolution, Instant resolvedAt)
                implements AndonEvent {}

        /** Response time exceeded; alert escalated to next level. */
        record Escalate(String station, int level, Instant escalatedAt) implements AndonEvent {}
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Lifecycle state of a single Andon alert. */
    public enum AlertStatus {
        OPEN,
        ACKNOWLEDGED,
        RESOLVED,
        ESCALATED
    }

    /** Mutable record of an open Andon alert. */
    public record AlertRecord(
            String station,
            String problem,
            Instant pulledAt,
            AlertStatus status,
            int escalationLevel,
            List<String> history) {

        public static AlertRecord open(String station, String problem) {
            var h = new ArrayList<String>();
            h.add("PULLED at " + Instant.now());
            return new AlertRecord(station, problem, Instant.now(), AlertStatus.OPEN, 0, h);
        }
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private final EventManager<AndonEvent> bus;
    private final Duration responseWindow;
    private final Map<String, AlertRecord> alerts = new ConcurrentHashMap<>();
    private final Map<String, ProcTimer.TimerRef> escalationTimers = new ConcurrentHashMap<>();
    private final AtomicInteger totalPulls = new AtomicInteger();
    private final AtomicInteger totalEscalations = new AtomicInteger();
    private final Proc<List<AndonEvent>, AndonEvent> log;

    @SuppressWarnings("unchecked")
    private AndonCordAgent(Duration responseWindow) {
        this.responseWindow = responseWindow;
        this.bus = EventManager.start("andon-bus");
        this.log =
                new Proc<>(
                        new ArrayList<>(),
                        (history, event) -> {
                            var updated = new ArrayList<>(history);
                            updated.add(event);
                            return updated;
                        });
        // Self-subscribe to log all events
        bus.addHandler(event -> log.tell(event));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates and starts a new AndonCordAgent.
     *
     * @param responseWindow time allowed for acknowledgement before auto-escalation
     */
    public static AndonCordAgent start(Duration responseWindow) {
        return new AndonCordAgent(responseWindow);
    }

    /**
     * Register a listener for all Andon events.
     *
     * @param listener handler invoked on every published event
     */
    public void addListener(EventManager.Handler<AndonEvent> listener) {
        bus.addHandler(listener);
    }

    /**
     * Pull the Andon cord for a station, signalling an abnormality.
     *
     * <p>Starts an escalation timer. If not acknowledged within {@link #responseWindow}, an {@link
     * AndonEvent.Escalate} event is automatically published.
     *
     * @param station station or component identifier
     * @param problem description of the abnormality
     */
    public void pull(String station, String problem) {
        totalPulls.incrementAndGet();
        var record = AlertRecord.open(station, problem);
        alerts.put(station, record);
        bus.notify(new AndonEvent.Pull(station, problem, record.pulledAt()));
        scheduleEscalation(station, 1);
    }

    /**
     * Acknowledge an open alert, cancelling the escalation timer.
     *
     * @param station station to acknowledge
     */
    public void acknowledge(String station) {
        cancelEscalation(station);
        alerts.computeIfPresent(
                station,
                (k, v) -> {
                    v.history().add("ACKNOWLEDGED at " + Instant.now());
                    return new AlertRecord(
                            v.station(),
                            v.problem(),
                            v.pulledAt(),
                            AlertStatus.ACKNOWLEDGED,
                            v.escalationLevel(),
                            v.history());
                });
        bus.notify(new AndonEvent.Acknowledge(station, Instant.now()));
    }

    /**
     * Resolve an alert and return the station to normal operation.
     *
     * @param station station to resolve
     * @param resolution description of the fix applied
     */
    public void resolve(String station, String resolution) {
        cancelEscalation(station);
        alerts.computeIfPresent(
                station,
                (k, v) -> {
                    v.history().add("RESOLVED: " + resolution + " at " + Instant.now());
                    return new AlertRecord(
                            v.station(),
                            v.problem(),
                            v.pulledAt(),
                            AlertStatus.RESOLVED,
                            v.escalationLevel(),
                            v.history());
                });
        bus.notify(new AndonEvent.Resolve(station, resolution, Instant.now()));
    }

    /** Returns a snapshot of all current alert records. */
    public List<AlertRecord> openAlerts() {
        return alerts.values().stream()
                .filter(r -> r.status() == AlertStatus.OPEN || r.status() == AlertStatus.ESCALATED)
                .toList();
    }

    /** Returns summary metrics. */
    public AndonMetrics metrics() {
        return new AndonMetrics(totalPulls.get(), totalEscalations.get(), alerts.size());
    }

    /** Stops the underlying event bus and log process. */
    public void stop() throws InterruptedException {
        escalationTimers.values().forEach(ProcTimer.TimerRef::cancel);
        escalationTimers.clear();
        bus.stop();
        log.stop();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void scheduleEscalation(String station, int level) {
        var timerRef =
                ProcTimer.sendAfter(
                        responseWindow.toMillis(),
                        log,
                        new AndonEvent.Escalate(station, level, Instant.now()));
        escalationTimers.put(station, timerRef);

        // Also register a real handler that fires the escalation through the bus
        bus.addHandler(
                event -> {
                    if (event instanceof AndonEvent.Escalate(var s, var l, var ts)
                            && s.equals(station)) {
                        totalEscalations.incrementAndGet();
                        alerts.computeIfPresent(
                                station,
                                (k, v) ->
                                        new AlertRecord(
                                                v.station(),
                                                v.problem(),
                                                v.pulledAt(),
                                                AlertStatus.ESCALATED,
                                                level,
                                                v.history()));
                        if (level < 3) {
                            scheduleEscalation(station, level + 1);
                        }
                    }
                });

        // Fire escalation via timer
        ProcTimer.sendAfter(
                responseWindow.toMillis(),
                log,
                new AndonEvent.Escalate(station, level, Instant.now()));
    }

    private void cancelEscalation(String station) {
        var ref = escalationTimers.remove(station);
        if (ref != null) {
            ref.cancel();
        }
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    /** Summary metrics for the Andon system. */
    public record AndonMetrics(int totalPulls, int totalEscalations, int totalAlerts) {}
}
