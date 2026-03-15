package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

/**
 * KanbanFlowAgent — pull-based work coordination with per-lane WIP limits.
 *
 * <p><strong>Toyota principle:</strong> Kanban ("signboard") authorises downstream consumption only
 * when capacity exists. No upstream push occurs until a kanban card signals readiness. WIP limits
 * prevent overproduction and expose bottlenecks immediately.
 *
 * <p><strong>Armstrong:</strong> "A process should only receive work it can handle now. The mailbox
 * is the buffer; let it fill only when the downstream is ready."
 *
 * <p>Each logical lane (e.g. "design", "build", "test", "deploy") is modelled as a named slot
 * inside the single {@link Proc}'s state. Work items are pulled one at a time; the WIP count is
 * decremented only when a worker signals completion.
 *
 * <pre>{@code
 * var agent = KanbanFlowAgent.start(Map.of("build", 3, "test", 2));
 * agent.tell(new KanbanMsg.AddWork("build", new WorkItem("TASK-1", "Implement login")));
 * var pulled = agent.ask(new KanbanMsg.Pull("build"), Duration.ofSeconds(1)).join();
 * // ... do work ...
 * agent.tell(new KanbanMsg.Complete("build", pulled.workItem().id()));
 * agent.tell(new KanbanMsg.Shutdown());
 * }</pre>
 */
public final class KanbanFlowAgent {

    private KanbanFlowAgent() {}

    // ── Domain types ──────────────────────────────────────────────────────────

    /** A unit of work flowing through a kanban lane. */
    public record WorkItem(String id, String description) {}

    /** Result of a pull request: either a kanban card or a WIP-limit signal. */
    public sealed interface PullResult
            permits PullResult.Card, PullResult.WipLimitReached, PullResult.EmptyQueue {

        /** A work item authorised for downstream processing. */
        record Card(WorkItem workItem, String lane) implements PullResult {}

        /** No capacity in this lane — WIP limit is fully occupied. */
        record WipLimitReached(String lane, int limit, int current) implements PullResult {}

        /** Lane has capacity but the backlog is empty. */
        record EmptyQueue(String lane) implements PullResult {}
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all kanban lanes.
     *
     * @param lanes configured WIP limits per lane name
     * @param backlog pending work items per lane (FIFO)
     * @param inProgress count of items currently in-flight per lane
     */
    public record KanbanState(
            Map<String, Integer> lanes,
            Map<String, Queue<WorkItem>> backlog,
            Map<String, Integer> inProgress) {

        /** Bootstrap state from a lane → WIP-limit map. */
        public static KanbanState of(Map<String, Integer> wipLimits) {
            var back = new HashMap<String, Queue<WorkItem>>();
            var wip = new HashMap<String, Integer>();
            wipLimits.forEach(
                    (lane, limit) -> {
                        back.put(lane, new ArrayDeque<>());
                        wip.put(lane, 0);
                    });
            return new KanbanState(Map.copyOf(wipLimits), back, wip);
        }

        /** Returns a new state with one item enqueued into the given lane. */
        public KanbanState enqueue(String lane, WorkItem item) {
            if (!lanes.containsKey(lane)) {
                throw new IllegalArgumentException("Unknown lane: " + lane);
            }
            backlog.get(lane).offer(item);
            return this;
        }

        /**
         * Attempts a pull from {@code lane}.
         *
         * @return a pair of (new state, pull result)
         */
        public PullAttempt tryPull(String lane) {
            if (!lanes.containsKey(lane)) {
                throw new IllegalArgumentException("Unknown lane: " + lane);
            }
            int limit = lanes.get(lane);
            int current = inProgress.get(lane);
            if (current >= limit) {
                return new PullAttempt(this, new PullResult.WipLimitReached(lane, limit, current));
            }
            WorkItem item = backlog.get(lane).poll();
            if (item == null) {
                return new PullAttempt(this, new PullResult.EmptyQueue(lane));
            }
            inProgress.put(lane, current + 1);
            return new PullAttempt(this, new PullResult.Card(item, lane));
        }

        /** Returns a new state with the given work item marked complete. */
        public KanbanState complete(String lane, String workId) {
            int current = inProgress.getOrDefault(lane, 0);
            inProgress.put(lane, Math.max(0, current - 1));
            return this;
        }

        /** Snapshot of WIP utilisation per lane. */
        public Map<String, String> utilisation() {
            var result = new HashMap<String, String>();
            lanes.forEach(
                    (lane, limit) ->
                            result.put(lane, inProgress.getOrDefault(lane, 0) + "/" + limit));
            return Map.copyOf(result);
        }
    }

    /** Internal result type pairing updated state with the pull outcome. */
    public record PullAttempt(KanbanState state, PullResult result) {}

    // ── Messages ──────────────────────────────────────────────────────────────

    /** Message hierarchy for the KanbanFlowAgent process. */
    public sealed interface KanbanMsg
            permits KanbanMsg.AddWork,
                    KanbanMsg.Pull,
                    KanbanMsg.Complete,
                    KanbanMsg.Utilisation,
                    KanbanMsg.Shutdown {

        /** Enqueue a work item into a lane. */
        record AddWork(String lane, WorkItem item) implements KanbanMsg {}

        /** Pull the next authorised work item from a lane (ask() pattern). */
        record Pull(String lane) implements KanbanMsg {}

        /** Signal that a previously pulled work item is finished. */
        record Complete(String lane, String workId) implements KanbanMsg {}

        /** Query current WIP utilisation (ask() pattern). */
        record Utilisation() implements KanbanMsg {}

        /** Gracefully stop the agent. */
        record Shutdown() implements KanbanMsg {}
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    private static KanbanState handle(KanbanState state, KanbanMsg msg) {
        return switch (msg) {
            case KanbanMsg.AddWork(var lane, var item) -> state.enqueue(lane, item);
            case KanbanMsg.Pull(var lane) -> {
                var attempt = state.tryPull(lane);
                // Result delivered via ask() reply mechanism — return updated state
                yield attempt.state();
            }
            case KanbanMsg.Complete(var lane, var workId) -> state.complete(lane, workId);
            case KanbanMsg.Utilisation() -> state; // ask() delivers the state itself
            case KanbanMsg.Shutdown() -> {
                Thread.currentThread().interrupt();
                yield state;
            }
        };
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a running KanbanFlowAgent with the supplied lane → WIP-limit configuration.
     *
     * @param wipLimits lane name to maximum in-progress count
     * @return running {@link Proc} accepting {@link KanbanMsg} messages
     */
    public static Proc<KanbanState, KanbanMsg> start(Map<String, Integer> wipLimits) {
        return new Proc<>(KanbanState.of(wipLimits), KanbanFlowAgent::handle);
    }

    /**
     * Convenience factory: creates a work item with an auto-generated ID.
     *
     * @param description human-readable description of the work
     * @return new {@link WorkItem}
     */
    public static WorkItem workItem(String description) {
        return new WorkItem(UUID.randomUUID().toString(), description);
    }

    /**
     * Synchronously pull the next available item from {@code lane}, blocking up to {@code timeout}.
     *
     * <p>Returns an {@link Optional} wrapping the {@link PullResult.Card} if work was available and
     * WIP capacity permitted; otherwise returns empty (WIP limit or empty queue).
     */
    public static Optional<PullResult.Card> pull(
            Proc<KanbanState, KanbanMsg> agent, String lane, Duration timeout) {
        KanbanState state = agent.ask(new KanbanMsg.Pull(lane), timeout).join();
        var attempt = state.tryPull(lane);
        return switch (attempt.result()) {
            case PullResult.Card card -> Optional.of(card);
            default -> Optional.empty();
        };
    }
}
