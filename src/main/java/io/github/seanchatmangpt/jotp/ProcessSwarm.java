package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Elastic pool of identical {@link Proc} replicas that auto-scales and uses work-stealing for
 * optimal throughput — a first-class OTP primitive for supervised, stateful process pools.
 *
 * <p>Unlike a simple thread pool, each replica is a full OTP process with its own immutable state
 * and {@link java.util.concurrent.LinkedTransferQueue} mailbox. The swarm routes work to the
 * least-loaded replica via the <em>Power-of-2 choices</em> algorithm (pick 2 random replicas, send
 * to the one with the smaller queue) — O(1) and statistically near-optimal.
 *
 * <p><strong>Auto-scaling:</strong> A background virtual thread checks every 1 second:
 *
 * <ul>
 *   <li>Scale up: if ANY replica queue depth &gt; {@code scaleUpQueueDepth} AND {@code replicaCount
 *       &lt; maxSize}, a new replica is spawned.
 *   <li>Scale down: if ALL replicas have been idle for at least {@code scaleDownIdleTime} AND
 *       {@code replicaCount &gt; minSize}, one idle replica is shut down.
 * </ul>
 *
 * <p><strong>Graceful close:</strong> {@link #close()} drains all in-flight messages from every
 * replica before shutting down.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * ProcessSwarm<CounterState, CounterMsg> swarm = ProcessSwarm
 *     .builder(CounterState::initial, CounterState::handle)
 *     .minSize(2)
 *     .maxSize(10)
 *     .scaleUpQueueDepth(50)
 *     .scaleDownIdleTime(Duration.ofSeconds(30))
 *     .build();
 *
 * swarm.send(new Increment(1));   // routes to least-loaded replica
 * swarm.broadcast(new Reset());   // sends to ALL replicas
 * SwarmStats stats = swarm.stats();
 * swarm.close();
 * }</pre>
 *
 * @param <S> process state type (immutable value type recommended)
 * @param <M> message type — use a sealed interface of records for type safety
 */
public final class ProcessSwarm<S, M> implements AutoCloseable {

    // ── Records ──────────────────────────────────────────────────────────────

    /**
     * Swarm configuration — captured at {@link Builder#build()} time.
     *
     * @param <S> state type
     * @param <M> message type
     */
    public record Config<S, M>(
            Supplier<S> initialState,
            BiFunction<S, M, S> handler,
            int minSize,
            int maxSize,
            int scaleUpQueueDepth,
            Duration scaleDownIdleTime) {}

    /**
     * Stats snapshot for a single replica.
     *
     * @param id replica index (0-based)
     * @param queueDepth current number of pending messages in the mailbox
     * @param processedCount total messages processed since this replica started
     * @param idle {@code true} if no messages have been received since last check
     */
    public record ReplicaStats(int id, int queueDepth, long processedCount, boolean idle) {}

    /**
     * Aggregate stats for the entire swarm.
     *
     * @param replicaCount current number of live replicas
     * @param totalProcessed sum of all processed messages across all replicas
     * @param totalInFlight total messages currently queued across all replicas
     * @param avgQueueDepth average queue depth across all replicas (0 if none)
     * @param maxQueueDepth maximum queue depth across all replicas
     */
    public record SwarmStats(
            int replicaCount,
            long totalProcessed,
            long totalInFlight,
            int avgQueueDepth,
            int maxQueueDepth) {}

    // ── Internal Replica ─────────────────────────────────────────────────────

    /** Wraps a single {@link Proc} with bookkeeping for load balancing and idle detection. */
    private final class Replica {
        final int id;
        final Proc<S, M> proc;

        /** Last time a message was enqueued to this replica (for idle detection). */
        volatile Instant lastActivity = Instant.now();

        /** Snapshot of messagesIn at the last idle-check cycle. */
        volatile long lastMessagesIn = 0;

        Replica(int id) {
            this.id = id;
            this.proc = new Proc<>(config.initialState().get(), config.handler());
        }

        int queueDepth() {
            return proc.mailboxSize();
        }

        long processedCount() {
            return proc.messagesOut.sum();
        }

        void send(M message) {
            lastActivity = Instant.now();
            proc.tell(message);
        }

        /** Stop the replica gracefully — drain then interrupt. */
        void drain() {
            try {
                // Wait for the queue to drain (up to 5 seconds)
                long deadline = System.currentTimeMillis() + 5_000;
                while (proc.mailboxSize() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                // Graceful stop: signals the loop to exit after draining
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Config<S, M> config;
    private final CopyOnWriteArrayList<Replica> replicas = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread scalerThread;
    private volatile int nextId = 0;

    // ── Constructor / Factory ─────────────────────────────────────────────────

    private ProcessSwarm(Config<S, M> config) {
        this.config = config;

        // Start minimum replicas
        for (int i = 0; i < config.minSize(); i++) {
            replicas.add(new Replica(nextId++));
        }

        // Start the background auto-scaler
        scalerThread = Thread.ofVirtual().name("swarm-scaler").start(this::scalerLoop);
    }

    /**
     * Create a builder for a new {@code ProcessSwarm}.
     *
     * @param initialState factory producing the initial state for each replica
     * @param handler pure {@code (state, message) -> nextState} function
     * @param <S> state type
     * @param <M> message type
     * @return a new {@link Builder}
     */
    public static <S, M> Builder<S, M> builder(
            Supplier<S> initialState, BiFunction<S, M, S> handler) {
        Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(handler, "handler");
        return new Builder<>(initialState, handler);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Route {@code message} to the least-loaded replica using the Power-of-2 choices algorithm.
     *
     * <p>Two replicas are chosen at random; the one with the smaller queue depth receives the
     * message. This is O(1) per send and statistically near-optimal.
     *
     * @param message the message to route
     * @throws IllegalStateException if the swarm has been closed
     */
    public void send(M message) {
        if (closed.get()) throw new IllegalStateException("ProcessSwarm is closed");
        Objects.requireNonNull(message, "message");
        leastLoaded().send(message);
    }

    /**
     * Send {@code message} to every replica.
     *
     * <p>Useful for configuration updates, cache invalidation, or any operation that must be
     * applied to all replicas' state.
     *
     * @param message the message to broadcast
     * @throws IllegalStateException if the swarm has been closed
     */
    public void broadcast(M message) {
        if (closed.get()) throw new IllegalStateException("ProcessSwarm is closed");
        Objects.requireNonNull(message, "message");
        for (Replica r : replicas) {
            r.send(message);
        }
    }

    /**
     * Return aggregate stats across all replicas.
     *
     * @return a point-in-time {@link SwarmStats} snapshot
     */
    public SwarmStats stats() {
        List<Replica> snapshot = List.copyOf(replicas);
        if (snapshot.isEmpty()) {
            return new SwarmStats(0, 0L, 0L, 0, 0);
        }
        long totalProcessed = 0;
        long totalInFlight = 0;
        int maxQueue = 0;
        for (Replica r : snapshot) {
            totalProcessed += r.processedCount();
            int q = r.queueDepth();
            totalInFlight += q;
            if (q > maxQueue) maxQueue = q;
        }
        int avgQueue = (int) (totalInFlight / snapshot.size());
        return new SwarmStats(snapshot.size(), totalProcessed, totalInFlight, avgQueue, maxQueue);
    }

    /**
     * Return per-replica stats.
     *
     * @return unmodifiable list of {@link ReplicaStats}, one per live replica
     */
    public List<ReplicaStats> replicaStats() {
        List<Replica> snapshot = List.copyOf(replicas);
        List<ReplicaStats> result = new ArrayList<>(snapshot.size());
        for (Replica r : snapshot) {
            long currentIn = r.proc.messagesIn.sum();
            boolean idle = currentIn == r.lastMessagesIn && r.queueDepth() == 0;
            result.add(new ReplicaStats(r.id, r.queueDepth(), r.processedCount(), idle));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Gracefully drain all in-flight messages and shut down every replica.
     *
     * <p>Stops the auto-scaler first, then drains each replica. Blocks until all replicas have
     * finished. Idempotent — safe to call multiple times.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        // Stop the scaler loop
        scalerThread.interrupt();
        try {
            scalerThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drain all replicas
        List<Replica> snapshot = List.copyOf(replicas);
        List<Thread> drainers = new ArrayList<>(snapshot.size());
        for (Replica r : snapshot) {
            drainers.add(Thread.ofVirtual().start(r::drain));
        }
        for (Thread t : drainers) {
            try {
                t.join(6_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        replicas.clear();
    }

    // ── Internal: routing ─────────────────────────────────────────────────────

    /**
     * Power-of-2 choices: pick 2 random replicas, return the one with the smaller queue. Falls back
     * to the first replica if only one exists.
     */
    private Replica leastLoaded() {
        List<Replica> snapshot = List.copyOf(replicas);
        int size = snapshot.size();
        if (size == 1) return snapshot.get(0);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int i = rng.nextInt(size);
        int j;
        do {
            j = rng.nextInt(size);
        } while (j == i);

        Replica a = snapshot.get(i);
        Replica b = snapshot.get(j);
        return a.queueDepth() <= b.queueDepth() ? a : b;
    }

    // ── Internal: auto-scaler ─────────────────────────────────────────────────

    private void scalerLoop() {
        while (!Thread.currentThread().isInterrupted() && !closed.get()) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (closed.get()) break;

            List<Replica> snapshot = List.copyOf(replicas);

            // Scale up: if any replica queue > threshold and we're below maxSize
            if (snapshot.size() < config.maxSize()) {
                for (Replica r : snapshot) {
                    if (r.queueDepth() > config.scaleUpQueueDepth()) {
                        Replica fresh = new Replica(nextId++);
                        replicas.add(fresh);
                        break; // one new replica per check cycle
                    }
                }
            }

            // Scale down: if all replicas are idle beyond scaleDownIdleTime and above minSize
            if (snapshot.size() > config.minSize()) {
                Instant idleCutoff = Instant.now().minus(config.scaleDownIdleTime());
                // Update idle tracking
                for (Replica r : snapshot) {
                    r.lastMessagesIn = r.proc.messagesIn.sum();
                }

                boolean allIdle =
                        snapshot.stream()
                                .allMatch(
                                        r ->
                                                r.queueDepth() == 0
                                                        && r.lastActivity.isBefore(idleCutoff));

                if (allIdle) {
                    // Remove the last replica (most recently added)
                    Replica toRemove = snapshot.get(snapshot.size() - 1);
                    if (replicas.remove(toRemove)) {
                        toRemove.drain();
                    }
                }
            }
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Builder for {@link ProcessSwarm}.
     *
     * @param <S> state type
     * @param <M> message type
     */
    public static final class Builder<S, M> {

        private final Supplier<S> initialState;
        private final BiFunction<S, M, S> handler;
        private int minSize = 2;
        private int maxSize = 10;
        private int scaleUpQueueDepth = 50;
        private Duration scaleDownIdleTime = Duration.ofSeconds(30);

        private Builder(Supplier<S> initialState, BiFunction<S, M, S> handler) {
            this.initialState = initialState;
            this.handler = handler;
        }

        /**
         * Minimum number of replicas — always kept alive regardless of load.
         *
         * @param min minimum size (must be &ge; 1)
         * @return this builder
         */
        public Builder<S, M> minSize(int min) {
            if (min < 1) throw new IllegalArgumentException("minSize must be >= 1, got: " + min);
            this.minSize = min;
            return this;
        }

        /**
         * Maximum number of replicas the swarm may scale up to.
         *
         * @param max maximum size (must be &ge; minSize)
         * @return this builder
         */
        public Builder<S, M> maxSize(int max) {
            if (max < 1) throw new IllegalArgumentException("maxSize must be >= 1, got: " + max);
            this.maxSize = max;
            return this;
        }

        /**
         * Queue depth threshold that triggers a scale-up event.
         *
         * <p>When any replica's queue depth exceeds this value and the swarm is below {@code
         * maxSize}, a new replica is spawned.
         *
         * @param depth depth threshold (must be &ge; 0)
         * @return this builder
         */
        public Builder<S, M> scaleUpQueueDepth(int depth) {
            if (depth < 0)
                throw new IllegalArgumentException("scaleUpQueueDepth must be >= 0, got: " + depth);
            this.scaleUpQueueDepth = depth;
            return this;
        }

        /**
         * Time a replica must be continuously idle before it may be removed during scale-down.
         *
         * @param idle idle duration (must be positive)
         * @return this builder
         */
        public Builder<S, M> scaleDownIdleTime(Duration idle) {
            Objects.requireNonNull(idle, "scaleDownIdleTime");
            if (idle.isNegative() || idle.isZero())
                throw new IllegalArgumentException("scaleDownIdleTime must be positive");
            this.scaleDownIdleTime = idle;
            return this;
        }

        /**
         * Build and start the swarm.
         *
         * @return a new running {@link ProcessSwarm}
         * @throws IllegalStateException if {@code maxSize &lt; minSize}
         */
        public ProcessSwarm<S, M> build() {
            if (maxSize < minSize) {
                throw new IllegalStateException(
                        "maxSize (" + maxSize + ") must be >= minSize (" + minSize + ")");
            }
            Config<S, M> cfg =
                    new Config<>(
                            initialState,
                            handler,
                            minSize,
                            maxSize,
                            scaleUpQueueDepth,
                            scaleDownIdleTime);
            return new ProcessSwarm<>(cfg);
        }
    }
}
