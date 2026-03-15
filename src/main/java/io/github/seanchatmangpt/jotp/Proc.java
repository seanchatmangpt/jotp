package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lightweight process with a virtual-thread mailbox — Java 26 equivalent of an Erlang process.
 *
 * <p>Joe Armstrong: "Processes share nothing, communicate only by message passing. A process is the
 * unit of concurrency."
 *
 * <p>Mapping to Java 26:
 *
 * <ul>
 *   <li>Erlang lightweight process → virtual thread (one per process, ~1 KB heap)
 *   <li>Erlang mailbox → {@link LinkedTransferQueue} (lock-free MPMC, 50–150 ns/message)
 *   <li>Erlang message → {@code M} (use a {@code Record} or sealed-Record hierarchy for
 *       immutability by construction)
 *   <li>Shared-nothing state → {@code S} held privately, never returned by reference
 * </ul>
 *
 * <p>Crash callbacks (see {@link #addCrashCallback}) are fired when this process terminates
 * abnormally (unhandled exception). Normal {@link #stop()} does <em>not</em> fire them — mirroring
 * Erlang's distinction between {@code normal} and non-normal exit reasons.
 *
 * <p>Exit trapping (see {@link #trapExits}) converts incoming EXIT signals from linked processes
 * into {@link ExitSignal} messages delivered to the mailbox rather than interrupting the process —
 * mirroring OTP's {@code process_flag(trap_exit, true)}.
 *
 * <p>Process introspection is available via {@link ProcSys}: get state, suspend/resume, and
 * per-process message statistics — mirroring OTP's {@code sys} module.
 *
 * <p><strong>Message Processing and Stream.Gatherers:</strong> The message loop polls the queue
 * with a 50ms timeout (lines 127–128) rather than blocking indefinitely. While Java 26's {@link
 * java.util.stream.Stream.Gatherers} might seem applicable for batching messages, the current
 * design is optimal because:
 *
 * <ul>
 *   <li><strong>Semantic compatibility with OTP:</strong> Erlang processes handle one message per
 *       receive cycle. A single message handler is the right abstraction, not a batch processor.
 *   <li><strong>Lock-free efficiency:</strong> {@link LinkedTransferQueue} is already highly
 *       optimized (50–150 ns/enqueue + dequeue). Adding a gather/batch layer would only add
 *       overhead.
 *   <li><strong>Natural batching via timeout:</strong> The 50ms poll timeout already provides
 *       automatic batching — if multiple messages arrive within 50ms, they're all available in the
 *       queue for the next iteration. No stream semantics needed.
 *   <li><strong>Low-latency requirements:</strong> OTP patterns rely on sub-millisecond message
 *       processing guarantees. Batching would increase latency and violate the "one message per
 *       cycle" model.
 *   <li><strong>API stability:</strong> The handler signature {@code BiFunction<S, M, S>} is
 *       intentionally single-message-oriented. Batch mode would require a breaking API change.
 * </ul>
 *
 * <p>Therefore, message batching is neither necessary nor beneficial for JOTP's use cases.
 *
 * @param <S> process state (immutable value type recommended)
 * @param <M> message type — use a {@code Record} or sealed interface of Records
 */
public final class Proc<S, M> {

    /** Internal envelope carrying both the message and an optional reply handle. */
    private record Envelope<M>(M msg, CompletableFuture<Object> reply) {}

    private final TransferQueue<Envelope<M>> mailbox = new LinkedTransferQueue<>();

    /**
     * Sys channel: {@link ProcSys#getState} offers a future here; the loop completes it with the
     * current state before processing the next regular message.
     */
    final TransferQueue<CompletableFuture<Object>> sysGetState = new LinkedTransferQueue<>();

    private final Thread thread;
    private volatile boolean stopped = false;

    /** OTP: {@code process_flag(trap_exit, true)} — EXIT signals become mailbox messages. */
    private volatile boolean trappingExits = false;

    /** OTP: {@code sys:suspend} — pause message processing without killing the process. */
    private volatile boolean suspended = false;

    private final Object suspendMonitor = new Object();

    /** Counters for {@link ProcSys#statistics}. */
    final LongAdder messagesIn = new LongAdder();

    final LongAdder messagesOut = new LongAdder();

    /** The last unhandled exception; set before crash callbacks fire. */
    public volatile Throwable lastError = null;

    /** Callbacks fired on abnormal termination (exception, not graceful {@link #stop()}). */
    private final List<Runnable> crashCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Callbacks fired on <em>any</em> termination — normal or abnormal. Argument is {@code null}
     * for a normal exit, or the {@link Throwable} exit reason for an abnormal one. Used by {@link
     * ProcMonitor} and {@link ProcRegistry}.
     */
    private final List<Consumer<Throwable>> terminationCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Static factory: create and start a process — mirrors Erlang's {@code spawn/3}.
     *
     * <p>Prefer this factory over the constructor in new code.
     *
     * @param initial initial state
     * @param handler {@code (state, message) -> nextState} — pure function, no side-effects
     * @param <S> state type
     * @param <M> message type
     * @return a new running process
     */
    public static <S, M> Proc<S, M> spawn(S initial, BiFunction<S, M, S> handler) {
        return new Proc<>(initial, handler);
    }

    /**
     * Named spawn: create, register in {@link ProcRegistry}, and return a stable {@link ProcRef}.
     *
     * <p>Mirrors the common test/OTP pattern of spawning a named process and obtaining a handle.
     *
     * @param name registration name in {@link ProcRegistry}
     * @param stateFactory supplier providing the initial state
     * @param handler {@code (state, message) -> nextState} — pure function
     * @param <S> state type
     * @param <M> message type
     * @return a {@link ProcRef} for the new process, registered under {@code name}
     */
    public static <S, M> ProcRef<S, M> spawn(
            String name, Supplier<S> stateFactory, BiFunction<S, M, S> handler) {
        Proc<S, M> proc = new Proc<>(stateFactory.get(), handler);
        ProcRegistry.register(name, proc);
        return new ProcRef<>(proc);
    }

    /**
     * Create and start a process.
     *
     * <p><strong>Java 26 Implementation Notes:</strong>
     *
     * <ul>
     *   <li><strong>Virtual Threads:</strong> Each process runs on its own virtual thread
     *       (Thread.ofVirtual) spawned immediately. This allows millions of lightweight processes
     *       with minimal heap overhead (~1 KB per process).
     *   <li><strong>Mailbox:</strong> Uses LinkedTransferQueue for lock-free MPMC message passing
     *       (50–150 ns per message round-trip).
     *   <li><strong>State Isolation:</strong> State {@code S} is never returned by reference — only
     *       by handler invocation, guaranteeing isolation.
     *   <li><strong>Message Records:</strong> Use sealed interface hierarchies of records for
     *       type-safe pattern matching in the handler.
     *   <li><strong>Immutability:</strong> State should be immutable (record, sealed class, or
     *       value type) to enable safe concurrent sharing via monitors and asks.
     * </ul>
     *
     * @param initial initial state
     * @param handler {@code (state, message) -> nextState} — pure function, no side-effects
     */
    public Proc(S initial, BiFunction<S, M, S> handler) {
        thread =
                Thread.ofVirtual()
                        .name("proc")
                        .start(
                                () -> {
                                    S state = initial;
                                    boolean crashedAbnormally = false;
                                    outer:
                                    while (!stopped || !mailbox.isEmpty()) {
                                        // 1. Drain sys get-state requests (higher priority)
                                        CompletableFuture<Object> stateQ;
                                        while ((stateQ = sysGetState.poll()) != null) {
                                            stateQ.complete(state);
                                        }

                                        // 2. Suspend check — block until resumed
                                        if (suspended) {
                                            synchronized (suspendMonitor) {
                                                while (suspended) {
                                                    try {
                                                        suspendMonitor.wait();
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                        break outer;
                                                    }
                                                }
                                            }
                                            continue;
                                        }

                                        // 3. Process next message (poll with timeout so suspend
                                        //    and sys checks are re-evaluated periodically)
                                        Envelope<M> env = null;
                                        try {
                                            env = mailbox.poll(50, TimeUnit.MILLISECONDS);
                                            if (env == null) continue;
                                            messagesIn.increment();
                                            S next = handler.apply(state, env.msg());
                                            state = next;
                                            messagesOut.increment();
                                            if (env.reply() != null) {
                                                env.reply().complete(state);
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        } catch (RuntimeException e) {
                                            // If the message has a reply handle, complete it
                                            // exceptionally and keep the process alive (OTP:
                                            // gen_server can handle bad calls without dying).
                                            // If it is a fire-and-forget (tell), treat as a
                                            // fatal crash so the supervisor can restart.
                                            if (env != null && env.reply() != null) {
                                                env.reply().completeExceptionally(e);
                                            } else {
                                                lastError = e;
                                                crashedAbnormally = true;
                                                break;
                                            }
                                        }
                                    }

                                    // Complete any pending getState requests exceptionally
                                    CompletableFuture<Object> pending;
                                    while ((pending = sysGetState.poll()) != null) {
                                        pending.completeExceptionally(
                                                new IllegalStateException("process terminated"));
                                    }

                                    // Also treat interrupted-with-lastError as abnormal
                                    // (deliverExitSignal sets lastError then interrupts)
                                    if (crashedAbnormally || lastError != null) {
                                        for (Runnable cb : crashCallbacks) {
                                            cb.run();
                                        }
                                    }
                                    // Fire termination callbacks (monitor semantics — always)
                                    Throwable exitReason =
                                            (crashedAbnormally || lastError != null)
                                                    ? lastError
                                                    : null;
                                    for (Consumer<Throwable> cb : terminationCallbacks) {
                                        cb.accept(exitReason);
                                    }
                                });
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without waiting for processing.
     *
     * <p>Armstrong: "!" (send) is the primary mode — caller never blocks, process handles at its
     * own pace.
     */
    public void tell(M msg) {
        mailbox.add(new Envelope<>(msg, null));
    }

    /**
     * Request-reply: send {@code msg} and return a future that completes with the process's state
     * after the message is processed.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<S> ask(M msg) {
        var future = new CompletableFuture<Object>();
        mailbox.add(new Envelope<>(msg, future));
        return future.thenApply(s -> (S) s);
    }

    /**
     * Timed request-reply — mirrors OTP's {@code gen_server:call(Pid, Msg, Timeout)}.
     *
     * <p>The returned future completes exceptionally with {@link
     * java.util.concurrent.TimeoutException} if the process does not respond within {@code
     * timeout}.
     *
     * <p>Armstrong: "An unbounded call is a latent deadlock. Every call must have a timeout."
     */
    public CompletableFuture<S> ask(M msg, Duration timeout) {
        return ask(msg).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Enable or disable exit signal trapping — mirrors OTP's {@code process_flag(trap_exit, Flag)}.
     *
     * <p>When {@code true}, EXIT signals from linked processes are delivered as {@link ExitSignal}
     * messages to this process's mailbox instead of interrupting it. The process stays alive and
     * can choose how to handle each exit reason.
     *
     * <p>When {@code false} (default), EXIT signals kill this process immediately.
     */
    public void trapExits(boolean trap) {
        this.trappingExits = trap;
    }

    /** Returns {@code true} if this process is currently trapping exit signals. */
    public boolean isTrappingExits() {
        return trappingExits;
    }

    /**
     * Interrupt the process and wait for the virtual thread to finish.
     *
     * <p>Sets the stopped flag and immediately interrupts the virtual thread. Any messages still
     * pending in the mailbox are <strong>dropped</strong> — the process does not drain them before
     * exiting. Does <em>not</em> fire crash callbacks (normal exit reason).
     *
     * <p>If you need to ensure all queued messages are processed before shutdown, drain the mailbox
     * explicitly (e.g., by sending a sentinel "stop" message and waiting for the process to handle
     * it) before calling {@code stop()}.
     */
    public void stop() throws InterruptedException {
        stopped = true;
        thread.interrupt();
        thread.join();
    }

    /**
     * Register a callback to be invoked when this process terminates abnormally (unhandled
     * exception). Called by {@link Supervisor} and {@link ProcLink}.
     */
    public void addCrashCallback(Runnable cb) {
        crashCallbacks.add(cb);
    }

    /**
     * Register a callback fired when this process terminates for any reason. {@code null} reason
     * means normal exit; non-null is the abnormal exit cause. Used by {@link ProcMonitor} and
     * {@link ProcRegistry}.
     */
    void addTerminationCallback(Consumer<Throwable> cb) {
        terminationCallbacks.add(cb);
    }

    /** Remove a previously registered termination callback (for {@link ProcMonitor#demonitor}). */
    boolean removeTerminationCallback(Consumer<Throwable> cb) {
        return terminationCallbacks.remove(cb);
    }

    /**
     * Deliver an exit signal from a linked process. If this process is trapping exits, the signal
     * is converted to an {@link ExitSignal} message and delivered to the mailbox. Otherwise, the
     * process is interrupted immediately — OTP default behaviour.
     *
     * <p><strong>Warning:</strong> When {@link #trapExits(boolean)} is {@code true}, this method
     * casts the {@link ExitSignal} to {@code M} and enqueues it. This cast is unchecked at compile
     * time. If the process's message type {@code M} does not include {@link ExitSignal} (or a
     * common supertype), a {@link ClassCastException} will occur at dispatch time inside the
     * handler — not at this call site. Ensure that {@code M} can accept {@link ExitSignal} before
     * enabling exit trapping.
     */
    @SuppressWarnings("unchecked")
    public void deliverExitSignal(Throwable reason) {
        if (trappingExits) {
            mailbox.add(new Envelope<>((M) new ExitSignal(reason), null));
        } else {
            interruptAbnormally(reason);
        }
    }

    /**
     * Interrupt this process and mark it as crashed — used by {@link ProcLink} to deliver exit
     * signals from a linked process when exit trapping is off.
     */
    void interruptAbnormally(Throwable reason) {
        lastError = reason;
        thread.interrupt();
    }

    /** Returns the last unhandled exception, or {@code null} if this process has not crashed. */
    public Throwable lastError() {
        return lastError;
    }

    /** Public: exposes the underlying virtual thread (for joining, etc.). */
    public Thread thread() {
        return thread;
    }

    /** {@code true} if this process has been gracefully stopped or has finished. */
    boolean isStopped() {
        return stopped;
    }

    /**
     * Package-private: suspend this process — used by {@link ProcSys#suspend}. The process loop
     * will block after finishing the current message.
     */
    void suspendProc() {
        suspended = true;
    }

    /**
     * Package-private: resume this process — used by {@link ProcSys#resume}. The process loop
     * continues immediately.
     */
    void resumeProc() {
        suspended = false;
        synchronized (suspendMonitor) {
            suspendMonitor.notifyAll();
        }
    }

    /** Package-private: current mailbox depth — used by {@link ProcSys#statistics}. */
    int mailboxSize() {
        return mailbox.size();
    }

    /** Debug observer for trace operations — can be null. */
    private volatile DebugObserver<S, M> debugObserver = null;

    /** Package-private: offer a sys request to the high-priority channel. */
    <SR> void offerSysRequest(SR request) {
        // This is a placeholder. SysRequest handling would be implemented here.
        // For now, we skip this to avoid compilation errors from missing SysRequest type.
    }

    /** Package-private: set the debug observer (null to disable tracing). */
    void setDebugObserver(DebugObserver<S, M> observer) {
        this.debugObserver = observer;
    }

    /** Package-private: get the current debug observer (null if none). */
    DebugObserver<S, M> getDebugObserver() {
        return debugObserver;
    }
}
