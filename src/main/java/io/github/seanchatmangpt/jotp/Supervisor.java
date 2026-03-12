package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * OTP supervision tree node — hierarchical process supervision with restart strategies.
 *
 * <p>Joe Armstrong: "Supervisors are the key to Erlang's fault tolerance. A supervisor's job is to
 * start, stop, and monitor its children. When a child crashes, the supervisor decides what to do —
 * restart it, restart all children, or give up."
 *
 * <p>In OTP, supervisors form a tree where the root supervisor starts application-level
 * supervisors, which in turn start workers. This hierarchical structure ensures that failures are
 * contained and recovered at the appropriate level.
 *
 * <p><strong>Restart Strategies:</strong>
 *
 * <ul>
 *   <li>{@link Strategy#ONE_FOR_ONE} — Only the crashed child is restarted
 *   <li>{@link Strategy#ONE_FOR_ALL} — All children are restarted when any crashes
 *   <li>{@link Strategy#REST_FOR_ONE} — The crashed child and all children started after it are
 *       restarted
 *   <li>{@link Strategy#SIMPLE_ONE_FOR_ONE} — Dynamic homogeneous pool; all children are instances
 *       of the same template spec
 * </ul>
 *
 * <p><strong>Restart Types (per child via {@link ChildSpec}):</strong>
 *
 * <p>The {@code maxRestarts} and {@code window} parameters implement OTP's "max restarts in a time
 * window" feature. If a child crashes {@code maxRestarts} or more times within {@code window}, the
 * supervisor gives up and terminates itself (and by extension, its entire subtree). This prevents
 * infinite restart loops. For example, {@code maxRestarts=5} means the supervisor allows up to 4
 * crashes and gives up on the 5th crash within the window.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Simple usage (backward-compatible):
 * var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 * var worker = sup.supervise("worker-1", initialState, handler);
 *
 * // Structured usage with ChildSpec:
 * var spec = new Supervisor.ChildSpec<>(
 *     "worker-1", () -> initialState, handler,
 *     Supervisor.ChildSpec.RestartType.TRANSIENT,
 *     new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(10)),
 *     Supervisor.ChildSpec.ChildType.WORKER,
 *     false);
 * var worker = sup.startChild(spec);
 *
 * // Simple one-for-one pool:
 * var template = Supervisor.ChildSpec.worker("conn", ConnState::new, ConnHandler::handle);
 * var pool = Supervisor.createSimple(template, 10, Duration.ofSeconds(30));
 * var conn1 = pool.startChild();
 * var conn2 = pool.startChild();
 * }</pre>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Virtual Threads:</strong> The supervisor itself runs on a virtual thread,
 *       monitoring all children efficiently.
 *   <li><strong>Sealed Interfaces:</strong> Internal {@code SvEvent} is sealed to {@code
 *       SvEvent_ChildCrashed}, {@code SvEvent_Shutdown} for exhaustive event handling.
 *   <li><strong>Records:</strong> Events are records carrying child ID, crash reason, etc.
 *   <li><strong>Pattern Matching:</strong> Supervisor's event loop uses switch/case on sealed
 *       events to route crashes to the appropriate restart strategy handler.
 * </ul>
 *
 * @see Proc
 * @see ProcRef
 * @see CrashRecovery
 */
public final class Supervisor {

    // ── Strategy ────────────────────────────────────────────────────────────────

    /** Restart strategy for the supervision tree. */
    public enum Strategy {
        /** Only the crashed child is restarted. Other children are unaffected. */
        ONE_FOR_ONE,
        /** All children are restarted when any child crashes. */
        ONE_FOR_ALL,
        /** The crashed child and all children started after it are restarted. */
        REST_FOR_ONE,
        /**
         * Simplified one-for-one: all children are dynamically added instances of the same template
         * spec. Children are shut down asynchronously (mirroring OTP).
         */
        SIMPLE_ONE_FOR_ONE
    }

    // ── AutoShutdown ────────────────────────────────────────────────────────────

    /**
     * Controls whether and when a supervisor automatically shuts itself down when significant
     * children terminate. Mirrors OTP {@code auto_shutdown} supervisor flag.
     *
     * <p>Auto-shutdown only applies when significant children terminate <em>by themselves</em>, not
     * when their termination was caused by the supervisor (e.g. via {@link #terminateChild}).
     */
    public enum AutoShutdown {
        /** Automatic shutdown is disabled. This is the default. */
        NEVER,
        /**
         * Supervisor shuts down when <em>any</em> significant child terminates by itself (transient
         * normal exit or any temporary exit).
         */
        ANY_SIGNIFICANT,
        /**
         * Supervisor shuts down when <em>all</em> significant children have terminated by
         * themselves.
         */
        ALL_SIGNIFICANT
    }

    // ── ChildSpec ────────────────────────────────────────────────────────────────

    /**
     * Per-child configuration — mirrors OTP's {@code child_spec()} map.
     *
     * <p>Defines the identity, factory, restart policy, shutdown method, process type, and
     * significance of a supervised child.
     *
     * @param <S> state type of the child process
     * @param <M> message type of the child process
     */
    public record ChildSpec<S, M>(
            String id,
            Supplier<S> stateFactory,
            BiFunction<S, M, S> handler,
            RestartType restart,
            Shutdown shutdown,
            ChildType type,
            boolean significant) {

        /** Controls when a terminated child process is restarted. */
        public enum RestartType {
            /** Always restarted — on crash or normal exit. */
            PERMANENT,
            /** Restarted only on abnormal exit (crash). Normal exit is not restarted. */
            TRANSIENT,
            /** Never restarted. */
            TEMPORARY
        }

        /** Whether the child is a worker process or a supervisor. */
        public enum ChildType {
            WORKER,
            SUPERVISOR
        }

        /**
         * How a child is terminated during shutdown. Mirrors OTP {@code shutdown} child_spec field.
         */
        public sealed interface Shutdown
                permits Shutdown.BrutalKill, Shutdown.Timeout, Shutdown.Infinity {
            /** Unconditional immediate termination (OTP: {@code brutal_kill}). */
            record BrutalKill() implements Shutdown {}

            /**
             * Graceful shutdown with a timeout. If child does not stop within the duration, it is
             * forcibly interrupted (OTP: integer milliseconds).
             */
            record Timeout(Duration duration) implements Shutdown {}

            /**
             * Wait indefinitely for the child to stop. Use for supervisors and trusted workers
             * (OTP: {@code infinity}).
             */
            record Infinity() implements Shutdown {}
        }

        /** Validates required fields. */
        public ChildSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(stateFactory, "stateFactory");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(restart, "restart");
            Objects.requireNonNull(shutdown, "shutdown");
            Objects.requireNonNull(type, "type");
        }

        /**
         * Create a permanent worker spec with 5-second shutdown timeout — the most common pattern.
         */
        public static <S, M> ChildSpec<S, M> worker(
                String id, Supplier<S> stateFactory, BiFunction<S, M, S> handler) {
            return new ChildSpec<>(
                    id,
                    stateFactory,
                    handler,
                    RestartType.PERMANENT,
                    new Shutdown.Timeout(Duration.ofMillis(5000)),
                    ChildType.WORKER,
                    false);
        }

        /** Create a permanent worker spec with a fixed initial state value. */
        public static <S, M> ChildSpec<S, M> permanent(
                String id, S initial, BiFunction<S, M, S> handler) {
            return worker(id, () -> initial, handler);
        }
    }

    // ── Public query result ──────────────────────────────────────────────────────

    /** Snapshot of a child's current state, returned by {@link #whichChildren()}. */
    public record ChildInfo(String id, boolean alive, ChildSpec.ChildType type) {}

    // ── Internal events ──────────────────────────────────────────────────────────

    private sealed interface SvEvent
            permits SvEvent_ChildCrashed, SvEvent_ChildExited, SvEvent_Shutdown {}

    private record SvEvent_ChildCrashed(String id, Throwable cause) implements SvEvent {}

    private record SvEvent_ChildExited(String id) implements SvEvent {}

    private record SvEvent_Shutdown() implements SvEvent {}

    // ── Internal: ChildEntry ─────────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private static final class ChildEntry {
        final ChildSpec spec;
        volatile ProcRef ref;
        final List<Instant> restartHistory = new ArrayList<>();
        volatile boolean stopping = false; // supervisor-initiated stop in progress
        volatile boolean alive = true; // false after non-restarting exit or terminateChild()

        @SuppressWarnings("unchecked")
        ChildEntry(ChildSpec<?, ?> spec) {
            this.spec = spec;
        }
    }

    // ── Fields ───────────────────────────────────────────────────────────────────

    private final Strategy strategy;
    private final int maxRestarts;
    private final Duration window;
    private final AutoShutdown autoShutdown;
    private final LinkedTransferQueue<SvEvent> events = new LinkedTransferQueue<>();
    private final List<ChildEntry> children = new ArrayList<>();
    private final Thread supervisorThread;
    private volatile boolean running = true;
    private volatile Throwable fatalError = null;
    private final AtomicInteger activeSignificant = new AtomicInteger(0);
    private final AtomicInteger instanceCounter = new AtomicInteger(0);
    private final ChildSpec<?, ?> simpleTemplate; // only set for SIMPLE_ONE_FOR_ONE

    // ── Primary constructor ───────────────────────────────────────────────────────

    private Supervisor(
            String name,
            Strategy strategy,
            int maxRestarts,
            Duration window,
            AutoShutdown autoShutdown,
            ChildSpec<?, ?> simpleTemplate) {
        this.strategy = strategy;
        this.maxRestarts = maxRestarts;
        this.window = window;
        this.autoShutdown = autoShutdown;
        this.simpleTemplate = simpleTemplate;
        String threadName = name != null ? "supervisor-" + name : "supervisor";
        this.supervisorThread = Thread.ofVirtual().name(threadName).start(this::eventLoop);
    }

    // ── Deprecated constructors (kept for backward compatibility) ─────────────────

    /**
     * @deprecated Use {@link #create(Strategy, int, Duration)} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public Supervisor(Strategy strategy, int maxRestarts, Duration window) {
        this(null, strategy, maxRestarts, window, AutoShutdown.NEVER, null);
    }

    /**
     * @deprecated Use {@link #create(String, Strategy, int, Duration)} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public Supervisor(String name, Strategy strategy, int maxRestarts, Duration window) {
        this(name, strategy, maxRestarts, window, AutoShutdown.NEVER, null);
    }

    // ── Factory methods ───────────────────────────────────────────────────────────

    /**
     * Create a supervisor with the given strategy and restart limits.
     *
     * <p><strong>Deprecated:</strong> Use {@link #create(Strategy, int, Duration)} or {@link
     * #create(String, Strategy, int, Duration)} instead.
     *
     * @param strategy restart strategy (ONE_FOR_ONE, ONE_FOR_ALL, or REST_FOR_ONE)
     * @param maxRestarts crash limit within the window; the supervisor gives up on the {@code
     *     maxRestarts}-th crash (i.e., allows up to {@code maxRestarts - 1} restarts)
     * @param window time window for counting restarts
     * @deprecated Use {@link #create(Strategy, int, Duration)} instead
     */
    public static Supervisor create(Strategy strategy, int maxRestarts, Duration window) {
        return new Supervisor(null, strategy, maxRestarts, window, AutoShutdown.NEVER, null);
    }

    /**
     * Create a supervisor with auto-shutdown behaviour.
     *
     * @param strategy restart strategy
     * @param maxRestarts maximum restarts within the window
     * @param window time window for counting restart attempts
     * @param autoShutdown when the supervisor should automatically shut itself down
     */
    public static Supervisor create(
            Strategy strategy, int maxRestarts, Duration window, AutoShutdown autoShutdown) {
        return new Supervisor(null, strategy, maxRestarts, window, autoShutdown, null);
    }

    /**
     * Create a named supervisor with the given strategy and restart limits.
     *
     * <p><strong>Deprecated:</strong> Use {@link #create(String, Strategy, int, Duration)} instead.
     *
     * @param name supervisor name (used for thread naming)
     * @param strategy restart strategy
     * @param maxRestarts crash limit within the window; the supervisor gives up on the {@code
     *     maxRestarts}-th crash (i.e., allows up to {@code maxRestarts - 1} restarts)
     * @param window time window for counting restarts
     * @deprecated Use {@link #create(String, Strategy, int, Duration)} instead
     */
    public static Supervisor create(
            String name, Strategy strategy, int maxRestarts, Duration window) {
        return new Supervisor(name, strategy, maxRestarts, window, AutoShutdown.NEVER, null);
    }

    /**
     * Create a named supervisor with auto-shutdown behaviour.
     *
     * <p>The child is started immediately and monitored for crashes. If the child crashes, the
     * supervisor applies its restart strategy.
     *
     * <p><strong>Note:</strong> All restarts use the same {@code initialState} value captured at
     * registration time. For mutable state objects, all restarts will share the same instance,
     * potentially carrying over corrupted state from a crashed process. Prefer immutable state
     * types (records, value types) to avoid this hazard.
     *
     * @param id unique identifier for this child (used in restart strategy)
     * @param initialState initial state for the child process
     * @param handler message handler for the child process
     * @return a {@link ProcRef} that transparently redirects to restarted processes
     * @param <S> state type
     * @param <M> message type
     */
    public static Supervisor create(
            String name,
            Strategy strategy,
            int maxRestarts,
            Duration window,
            AutoShutdown autoShutdown) {
        return new Supervisor(name, strategy, maxRestarts, window, autoShutdown, null);
    }

    /**
     * Create a {@link Strategy#SIMPLE_ONE_FOR_ONE} supervisor for a homogeneous dynamic child pool.
     * All children are spawned from the given template spec via {@link #startChild()}.
     *
     * @param template child spec template (id is used as prefix for instance ids)
     * @param maxRestarts maximum restarts within the window
     * @param window time window for counting restart attempts
     */
    public static <S, M> Supervisor createSimple(
            ChildSpec<S, M> template, int maxRestarts, Duration window) {
        return new Supervisor(
                null,
                Strategy.SIMPLE_ONE_FOR_ONE,
                maxRestarts,
                window,
                AutoShutdown.NEVER,
                template);
    }

    // ── Child management ──────────────────────────────────────────────────────────

    /**
     * Backward-compatible convenience method: supervise a permanent worker child.
     *
     * <p>Equivalent to {@code startChild(ChildSpec.permanent(id, initialState, handler))}.
     *
     * @param id unique identifier for this child
     * @param initialState initial state for the child process
     * @param handler message handler
     * @return a stable {@link ProcRef} that survives restarts
     */
    @SuppressWarnings("unchecked")
    public synchronized <S, M> ProcRef<S, M> supervise(
            String id, S initialState, BiFunction<S, M, S> handler) {
        return startChild(ChildSpec.permanent(id, initialState, handler));
    }

    /**
     * Add a child to the supervision tree using an explicit {@link ChildSpec}.
     *
     * <p>Not valid for {@link Strategy#SIMPLE_ONE_FOR_ONE} supervisors; use {@link #startChild()}
     * instead.
     *
     * @param spec child specification
     * @return a stable {@link ProcRef} that survives restarts
     * @throws IllegalStateException if this is a SIMPLE_ONE_FOR_ONE supervisor
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <S, M> ProcRef<S, M> startChild(ChildSpec<S, M> spec) {
        if (strategy == Strategy.SIMPLE_ONE_FOR_ONE) {
            throw new IllegalStateException(
                    "Use startChild() without ChildSpec for SIMPLE_ONE_FOR_ONE supervisors");
        }
        return addChild(spec);
    }

    /**
     * Spawn a new instance from the template (only for {@link Strategy#SIMPLE_ONE_FOR_ONE}).
     *
     * @return a {@link ProcRef} for the new instance
     * @throws IllegalStateException if this is not a SIMPLE_ONE_FOR_ONE supervisor
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <S, M> ProcRef<S, M> startChild() {
        if (strategy != Strategy.SIMPLE_ONE_FOR_ONE) {
            throw new IllegalStateException(
                    "startChild() without ChildSpec is only for SIMPLE_ONE_FOR_ONE supervisors");
        }
        ChildSpec<S, M> tpl = (ChildSpec<S, M>) simpleTemplate;
        String instanceId = tpl.id() + "-" + instanceCounter.incrementAndGet();
        ChildSpec<S, M> instanceSpec =
                new ChildSpec<>(
                        instanceId,
                        tpl.stateFactory(),
                        tpl.handler(),
                        tpl.restart(),
                        tpl.shutdown(),
                        tpl.type(),
                        tpl.significant());
        return addChild(instanceSpec);
    }

    /**
     * Stop a child and retain its spec in the tree (mirrors {@code supervisor:terminate_child/2}).
     *
     * <p>Does <em>not</em> trigger auto-shutdown even if the child is significant. The child can be
     * restarted later via {@link #startChild(ChildSpec)} using the same id.
     *
     * @param id the child id to stop
     * @throws IllegalArgumentException if no child with the given id exists
     * @throws InterruptedException if interrupted while waiting for the child to stop
     */
    public synchronized void terminateChild(String id) throws InterruptedException {
        ChildEntry entry = find(id);
        if (entry == null) throw new IllegalArgumentException("No child with id: " + id);
        if (!entry.alive) return;
        entry.stopping = true;
        stopChild(entry);
        entry.alive = false;
        if (entry.spec.significant()) activeSignificant.decrementAndGet();
    }

    /**
     * Stop a specific {@link Strategy#SIMPLE_ONE_FOR_ONE} instance by its {@link ProcRef}.
     *
     * @param ref the ref returned by {@link #startChild()}
     * @throws IllegalArgumentException if the ref is not tracked by this supervisor
     * @throws InterruptedException if interrupted while waiting
     */
    public synchronized void terminateChild(ProcRef<?, ?> ref) throws InterruptedException {
        ChildEntry entry = findByRef(ref);
        if (entry == null) throw new IllegalArgumentException("No child found for given ref");
        if (!entry.alive) return;
        entry.stopping = true;
        stopChild(entry);
        entry.alive = false;
        if (entry.spec.significant()) activeSignificant.decrementAndGet();
    }

    /**
     * Remove a stopped child's spec from the tree (mirrors {@code supervisor:delete_child/2}).
     *
     * @param id the child id whose spec should be removed
     * @throws IllegalArgumentException if no child with the given id exists
     * @throws IllegalStateException if the child is still running
     */
    public synchronized void deleteChild(String id) {
        ChildEntry entry = find(id);
        if (entry == null) throw new IllegalArgumentException("No child with id: " + id);
        if (entry.alive) throw new IllegalStateException("Cannot delete a running child: " + id);
        children.remove(entry);
    }

    /**
     * Returns a snapshot of the current child tree state (mirrors {@code
     * supervisor:which_children/1}).
     *
     * @return unmodifiable list of {@link ChildInfo} for each child currently in the tree
     */
    @SuppressWarnings("unchecked")
    public synchronized List<ChildInfo> whichChildren() {
        List<ChildInfo> result = new ArrayList<>(children.size());
        for (ChildEntry e : children) {
            result.add(new ChildInfo(e.spec.id(), e.alive, e.spec.type()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Gracefully shut down the supervisor and all its children.
     *
     * <p>Children are stopped in reverse order of their creation (LIFO) for static strategies.
     * {@link Strategy#SIMPLE_ONE_FOR_ONE} children are stopped asynchronously. This method blocks
     * until all children have terminated.
     *
     * @throws InterruptedException if interrupted while waiting for children to stop
     */
    public void shutdown() throws InterruptedException {
        events.add(new SvEvent_Shutdown());
        supervisorThread.join();
    }

    /** Returns {@code true} if the supervisor is still running. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the fatal error that caused the supervisor to terminate due to exceeding max
     * restarts, or {@code null} if the supervisor has not failed.
     */
    public Throwable fatalError() {
        return fatalError;
    }

    // ── Internal: process spawning ────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <S, M> ProcRef<S, M> addChild(ChildSpec<S, M> spec) {
        ChildEntry entry = new ChildEntry(spec);
        Object initialState = spec.stateFactory().get();
        Proc proc = spawnProc(entry, initialState);
        ProcRef ref = new ProcRef<>(proc);
        entry.ref = ref;
        if (spec.significant()) activeSignificant.incrementAndGet();
        children.add(entry);
        return (ProcRef<S, M>) ref;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Proc spawnProc(ChildEntry entry, Object initialState) {
        Proc proc = new Proc(initialState, entry.spec.handler());

        // Abnormal exit callback
        proc.addCrashCallback(
                () -> {
                    if (!entry.stopping)
                        events.add(new SvEvent_ChildCrashed(entry.spec.id(), proc.lastError()));
                });

        // Normal exit callback (exitReason == null means normal exit)
        proc.addTerminationCallback(
                exitReason -> {
                    if (!entry.stopping && exitReason == null)
                        events.add(new SvEvent_ChildExited(entry.spec.id()));
                });

        return proc;
    }

    // ── Internal: event loop ──────────────────────────────────────────────────────

    private void eventLoop() {
        try {
            while (running) {
                SvEvent ev = events.take();
                switch (ev) {
                    case SvEvent_ChildCrashed(var id, var cause) -> handleCrash(id, cause);
                    case SvEvent_ChildExited(var id) -> handleNormalExit(id);
                    case SvEvent_Shutdown() -> {
                        running = false;
                        stopAllOrdered();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void handleCrash(String id, Throwable cause) {
        ChildEntry entry = find(id);
        if (entry == null || entry.stopping) return;

        Instant now = Instant.now();
        entry.restartHistory.removeIf(t -> t.isBefore(now.minus(window)));
        entry.restartHistory.add(now);

        if (entry.restartHistory.size() >= maxRestarts) {
            fatalError = cause;
            running = false;
            stopAllOrdered();
            return;
        }

        // Enforce restart intensity (PERMANENT and TRANSIENT both restart on crash)
        if (exceedsIntensity(entry, cause)) return;

        applyRestartStrategy(entry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void handleNormalExit(String id) {
        ChildEntry entry = find(id);
        if (entry == null || entry.stopping) return;

        // TRANSIENT and TEMPORARY: do NOT restart on normal exit
        if (entry.spec.restart() != ChildSpec.RestartType.PERMANENT) {
            entry.alive = false;
            checkAutoShutdown(entry);
            return;
        }

        // PERMANENT: restart even on normal exit; count toward intensity
        if (exceedsIntensity(entry, null)) return;

        applyRestartStrategy(entry);
    }

    /**
     * Track restart attempt and check if max intensity has been exceeded.
     *
     * @return true if intensity exceeded (supervisor is terminating); false if restart can proceed
     */
    private boolean exceedsIntensity(ChildEntry entry, Throwable cause) {
        Instant now = Instant.now();
        entry.restartHistory.removeIf(t -> t.isBefore(now.minus(window)));
        entry.restartHistory.add(now);
        if (entry.restartHistory.size() > maxRestarts) {
            fatalError =
                    cause != null
                            ? cause
                            : new RuntimeException(
                                    "Max restarts exceeded for child: " + entry.spec.id());
            running = false;
            stopAllOrdered();
            return true;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyRestartStrategy(ChildEntry entry) {
        switch (strategy) {
            case ONE_FOR_ONE, SIMPLE_ONE_FOR_ONE -> restartOne(entry);
            case ONE_FOR_ALL -> {
                List<ChildEntry> snapshot = List.copyOf(children);
                for (ChildEntry c : snapshot) if (c != entry) stopChild(c);
                for (ChildEntry c : snapshot) restartOne(c);
            }
            case REST_FOR_ONE -> {
                List<ChildEntry> snapshot = List.copyOf(children);
                boolean found = false;
                for (ChildEntry c : snapshot) {
                    if (c == entry) {
                        found = true;
                        continue;
                    }
                    if (found) stopChild(c);
                }
                found = false;
                for (ChildEntry c : snapshot) {
                    if (c == entry) found = true;
                    if (found) restartOne(c);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restartOne(ChildEntry entry) {
        // Spawn restart on a new virtual thread with a brief delay. The delay serves two purposes:
        // 1. Gives external observers a window to see lastError on the crashed proc (via
        //    ref.proc()) before the delegate is replaced.
        // 2. Absorbs rapid re-crash messages that arrive during restart, preventing them from
        //    registering with the supervisor's restart-window tracker (they land on the dead proc).
        Thread.ofVirtual()
                .name("supervisor-restart-" + entry.spec.id())
                .start(
                        () -> {
                            try {
                                Thread.sleep(75);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            Object freshState = entry.spec.stateFactory().get();
                            Proc newProc = spawnProc(entry, freshState);
                            entry.stopping = false;
                            entry.ref.swap(newProc);
                        });
    }

    /**
     * Stop a child according to its {@link ChildSpec.Shutdown} policy.
     *
     * <ul>
     *   <li>{@link ChildSpec.Shutdown.BrutalKill} — interrupt immediately, no waiting
     *   <li>{@link ChildSpec.Shutdown.Timeout} — interrupt and wait up to timeout
     *   <li>{@link ChildSpec.Shutdown.Infinity} — interrupt and wait indefinitely
     * </ul>
     */
    private void stopChild(ChildEntry entry) {
        entry.stopping = true;
        try {
            switch (entry.spec.shutdown()) {
                case ChildSpec.Shutdown.BrutalKill() -> entry.ref.proc().thread().interrupt();
                case ChildSpec.Shutdown.Timeout(var d) -> {
                    entry.ref.proc().thread().interrupt();
                    entry.ref.proc().thread().join(d.toMillis());
                }
                case ChildSpec.Shutdown.Infinity() -> entry.ref.stop();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop all children.
     *
     * <p>Static strategies ({@link Strategy#ONE_FOR_ONE}, {@link Strategy#ONE_FOR_ALL}, {@link
     * Strategy#REST_FOR_ONE}): LIFO order (reverse registration order).
     *
     * <p>{@link Strategy#SIMPLE_ONE_FOR_ONE}: asynchronous — all instances stopped in parallel
     * virtual threads (mirroring OTP's async simple_one_for_one shutdown).
     */
    private synchronized void stopAllOrdered() {
        List<ChildEntry> snapshot = List.copyOf(children);
        if (strategy == Strategy.SIMPLE_ONE_FOR_ONE) {
            List<Thread> stoppers = new ArrayList<>(snapshot.size());
            for (ChildEntry e : snapshot) {
                stoppers.add(Thread.ofVirtual().start(() -> stopChild(e)));
            }
            for (Thread t : stoppers) {
                try {
                    t.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } else {
            for (int i = snapshot.size() - 1; i >= 0; i--) stopChild(snapshot.get(i));
        }
    }

    /**
     * Check if auto-shutdown should be triggered after a significant child exits by itself.
     *
     * <p>Only called when the exit was <em>not</em> supervisor-initiated ({@code entry.stopping} is
     * {@code false}).
     */
    private void checkAutoShutdown(ChildEntry entry) {
        if (!entry.spec.significant()) return;
        switch (autoShutdown) {
            case ANY_SIGNIFICANT -> triggerGracefulShutdown();
            case ALL_SIGNIFICANT -> {
                if (activeSignificant.decrementAndGet() == 0) triggerGracefulShutdown();
            }
            case NEVER -> {}
        }
    }

    private void triggerGracefulShutdown() {
        events.add(new SvEvent_Shutdown());
    }

    private ChildEntry find(String id) {
        return children.stream().filter(c -> c.spec.id().equals(id)).findFirst().orElse(null);
    }

    private ChildEntry findByRef(ProcRef<?, ?> ref) {
        return children.stream().filter(c -> c.ref == ref).findFirst().orElse(null);
    }

    /**
     * Create a supervision tree node with the given restart strategy and limits.
     *
     * <p>Establishes a supervisor process that manages child process lifecycle, restart policy, and
     * fault tolerance — the core OTP supervision primitive.
     *
     * <p><b>Usage Example:</b>
     *
     * <pre>{@code
     * var supervisor = Supervisor.create(
     *     Supervisor.Strategy.ONE_FOR_ONE,
     *     5,
     *     Duration.ofSeconds(60)
     * );
     *
     * var worker1 = supervisor.supervise("worker-1", state1, handler1);
     * var worker2 = supervisor.supervise("worker-2", state2, handler2);
     *
     * // If worker1 crashes, only worker1 is restarted (ONE_FOR_ONE strategy)
     * // If any worker crashes >= 5 times in 60 seconds, supervisor terminates
     *
     * supervisor.shutdown();  // Graceful shutdown
     * }</pre>
     *
     * @param strategy restart policy (ONE_FOR_ONE, ONE_FOR_ALL, or REST_FOR_ONE)
     * @param maxRestarts crash limit within the window; the supervisor gives up on the {@code
     *     maxRestarts}-th crash (i.e., allows up to {@code maxRestarts - 1} restarts)
     * @param window time window for counting restart attempts
     * @return a new supervisor instance with its event loop running on a virtual thread
     * @throws NullPointerException if {@code strategy} or {@code window} is null
     * @throws IllegalArgumentException if {@code maxRestarts < 0}
     * @see #create(String, Strategy, int, Duration) for named supervisors
     * @see #supervise(String, Object, BiFunction) to add child processes
     * @see Strategy for restart policy details
     */
}
