package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Elastic pool of identical OTP processes that auto-scales and uses work-stealing for optimal
 * throughput.
 *
 * <p>Each replica is a full {@link Proc} with its own state and mailbox. Work is routed using the
 * power-of-2-choices algorithm: pick two random replicas, send to the one with the smaller queue
 * depth. This is O(1) and statistically near-optimal.
 *
 * <p>Auto-scaling runs on a background virtual thread:
 *
 * <ul>
 *   <li>Scale up: if ANY replica queue depth &gt; {@code scaleUpQueueDepth} AND {@code
 *       replicaCount < maxSize}, spawn a new replica.
 *   <li>Scale down: if ALL replicas have been idle for {@code scaleDownIdleTime} AND {@code
 *       replicaCount > minSize}, shut down one replica.
 * </ul>
 *
 * <p>Example usage:
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
 * swarm.send(new Increment(1));
 * swarm.broadcast(new Reset());
 * SwarmStats stats = swarm.stats();
 * swarm.close();
 * }</pre>
 *
 * @param <S> process state type
 * @param <M> message type
 */
public final class ProcessSwarm<S, M> implements AutoCloseable {

    /** Configuration for a swarm. */
    public record Config<S, M>(
            Supplier<S> initialState,
            BiFunction<S, M, S> handler,
            int minSize,
            int maxSize,
            int scaleUpQueueDepth,
            Duration scaleDownIdleTime) {}

    /** Per-replica statistics snapshot. */
    public record ReplicaStats(int id, int queueDepth, long processedCount, boolean idle) {}

    /** Aggregate swarm statistics snapshot. */
    public record SwarmStats(
            int replicaCount,
            long totalProcessed,
            long totalInFlight,
            int avgQueueDepth,
            int maxQueueDepth) {}

    // ── Internal replica wrapper ──────────────────────────────────────────────

    private final class Replica {
        final int id;
        final Proc<S, M> proc;
        final AtomicLong processed = new AtomicLong(0);
        volatile Instant lastActive = Instant.now();

        Replica(int id) {
            this.id = id;
            this.proc = Proc.spawn(config.initialState().get(), (state, msg) -> {
                S next = config.handler().apply(state, msg);
                processed.incrementAndGet();
                lastActive = Instant.now();
                return next;
            });
        }

        int queueDepth() {
            return proc.mailboxSize();
        }

        boolean isIdle(Duration idleThreshold) {
            return queueDepth() == 0
                    && Duration.between(lastActive, Instant.now()).compareTo(idleThreshold) >= 0;
        }

        ReplicaStats toStats() {
            return new ReplicaStats(id, queueDepth(), processed.get(), isIdle(config.scaleDownIdleTime()));
        }

        void stop() {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Config<S, M> config;
    private final CopyOnWriteArrayList<Replica> replicas = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong nextId = new AtomicLong(0);
    private final Thread scalingThread;

    // ── Constructor (private — use builder) ───────────────────────────────────

    private ProcessSwarm(Config<S, M> config) {
        this.config = config;

        // Spawn minimum replicas
        for (int i = 0; i < config.minSize(); i++) {
            replicas.add(new Replica((int) nextId.getAndIncrement()));
        }

        // Background auto-scaling thread
        this.scalingThread = Thread.ofVirtual().name("process-swarm-scaler").start(this::scalingLoop);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Create a builder for a new swarm.
     *
     * @param initialState factory for per-replica initial state
     * @param handler message handler {@code (state, message) -> nextState}
     * @param <S> state type
     * @param <M> message type
     * @return a new builder
     */
    public static <S, M> Builder<S, M> builder(
            Supplier<S> initialState, BiFunction<S, M, S> handler) {
        return new Builder<>(initialState, handler);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Route a message to the least-loaded replica using the power-of-2-choices algorithm.
     *
     * <p>Picks two random replicas and sends to the one with the smaller queue depth.
     *
     * @param message the message to send
     * @throws IllegalStateException if the swarm has been closed
     */
    public void send(M message) {
        if (closed.get()) {
            throw new IllegalStateException("ProcessSwarm is closed");
        }
        leastLoaded().proc.tell(message);
    }

    /**
     * Broadcast a message to ALL replicas.
     *
     * <p>Useful for configuration updates, cache invalidation, or coordinated state resets.
     *
     * @param message the message to send to every replica
     * @throws IllegalStateException if the swarm has been closed
     */
    public void broadcast(M message) {
        if (closed.get()) {
            throw new IllegalStateException("ProcessSwarm is closed");
        }
        for (Replica r : replicas) {
            r.proc.tell(message);
        }
    }

    /**
     * Return aggregate statistics across all replicas.
     *
     * @return a snapshot of current swarm metrics
     */
    public SwarmStats stats() {
        List<Replica> snapshot = List.copyOf(replicas);
        if (snapshot.isEmpty()) {
            return new SwarmStats(0, 0L, 0L, 0, 0);
        }
        long totalProcessed = 0;
        long totalInFlight = 0;
        int maxQueueDepth = 0;
        for (Replica r : snapshot) {
            totalProcessed += r.processed.get();
            int depth = r.queueDepth();
            totalInFlight += depth;
            if (depth > maxQueueDepth) {
                maxQueueDepth = depth;
            }
        }
        int avgQueueDepth = (int) (totalInFlight / snapshot.size());
        return new SwarmStats(
                snapshot.size(), totalProcessed, totalInFlight, avgQueueDepth, maxQueueDepth);
    }

    /**
     * Return per-replica statistics.
     *
     * @return an unmodifiable list of replica stats snapshots
     */
    public List<ReplicaStats> replicaStats() {
        return replicas.stream().map(Replica::toStats).toList();
    }

    /**
     * Gracefully drain and shut down all replicas.
     *
     * <p>Waits for each replica's mailbox to drain before stopping. The scaling thread is
     * interrupted and joined.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // Already closed
        }

        scalingThread.interrupt();
        try {
            scalingThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drain all replicas: wait for mailboxes to empty
        for (Replica r : replicas) {
            drainReplica(r);
        }
        // Stop all replicas
        for (Replica r : replicas) {
            r.stop();
        }
        replicas.clear();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Power-of-2-choices: pick 2 random replicas, return the one with the smaller queue depth.
     * Falls back to the first replica if only one exists.
     */
    private Replica leastLoaded() {
        List<Replica> snapshot = List.copyOf(replicas);
        int size = snapshot.size();
        if (size == 1) {
            return snapshot.get(0);
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int idx1 = rng.nextInt(size);
        int idx2 = rng.nextInt(size - 1);
        if (idx2 >= idx1) idx2++;
        Replica r1 = snapshot.get(idx1);
        Replica r2 = snapshot.get(idx2);
        return r1.queueDepth() <= r2.queueDepth() ? r1 : r2;
    }

    /** Wait for a replica's mailbox to drain (at most 5 seconds). */
    private void drainReplica(Replica r) {
        long deadline = System.currentTimeMillis() + 5000;
        while (r.queueDepth() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Background loop: check every 1 second and scale up or down as needed. */
    private void scalingLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                if (closed.get()) break;
                maybeScaleUp();
                maybeScaleDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private synchronized void maybeScaleUp() {
        if (replicas.size() >= config.maxSize()) return;
        for (Replica r : replicas) {
            if (r.queueDepth() > config.scaleUpQueueDepth()) {
                replicas.add(new Replica((int) nextId.getAndIncrement()));
                return; // add one at a time
            }
        }
    }

    private synchronized void maybeScaleDown() {
        if (replicas.size() <= config.minSize()) return;
        // Only scale down if ALL replicas are idle
        for (Replica r : replicas) {
            if (!r.isIdle(config.scaleDownIdleTime())) return;
        }
        // Remove the last replica
        Replica toRemove = replicas.remove(replicas.size() - 1);
        toRemove.stop();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Builder for {@link ProcessSwarm}. */
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

        /** Minimum number of replicas (default: 2). */
        public Builder<S, M> minSize(int min) {
            if (min < 1) throw new IllegalArgumentException("minSize must be >= 1");
            this.minSize = min;
            return this;
        }

        /** Maximum number of replicas (default: 10). */
        public Builder<S, M> maxSize(int max) {
            if (max < 1) throw new IllegalArgumentException("maxSize must be >= 1");
            this.maxSize = max;
            return this;
        }

        /**
         * Queue depth threshold that triggers a scale-up (default: 50). When any replica's queue
         * exceeds this depth and the swarm is below maxSize, a new replica is spawned.
         */
        public Builder<S, M> scaleUpQueueDepth(int depth) {
            if (depth < 0) throw new IllegalArgumentException("scaleUpQueueDepth must be >= 0");
            this.scaleUpQueueDepth = depth;
            return this;
        }

        /**
         * Idle time threshold for scale-down (default: 30s). When ALL replicas have been idle
         * (empty queue, no recent activity) for at least this duration and the swarm is above
         * minSize, one replica is shut down.
         */
        public Builder<S, M> scaleDownIdleTime(Duration idle) {
            this.scaleDownIdleTime = idle;
            return this;
        }

        /** Build and start the swarm. */
        public ProcessSwarm<S, M> build() {
            if (maxSize < minSize) {
                throw new IllegalArgumentException("maxSize must be >= minSize");
            }
            Config<S, M> config =
                    new Config<>(
                            initialState,
                            handler,
                            minSize,
                            maxSize,
                            scaleUpQueueDepth,
                            scaleDownIdleTime);
            return new ProcessSwarm<>(config);
        }
    }
}
