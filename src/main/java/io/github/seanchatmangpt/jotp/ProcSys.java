package io.github.seanchatmangpt.jotp;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * OTP {@code sys} module — process introspection, debug tracing, and hot code change without
 * stopping the process.
 *
 * <p>Joe Armstrong: "You must be able to look inside a running process without stopping it. A
 * process you cannot inspect is a black box you cannot trust in production."
 *
 * <p>OTP's {@code sys} module is what makes {@code gen_server}/{@code gen_statem} processes
 * <em>observable</em> at runtime. This class provides the Java 26 equivalents:
 *
 * <ul>
 *   <li>{@code sys:get_state(Pid)} → {@link #getState(Proc)} — snapshot current state
 *   <li>{@code sys:suspend(Pid)} → {@link #suspend(Proc)} — pause message processing
 *   <li>{@code sys:resume(Pid)} → {@link #resume(Proc)} — resume message processing
 *   <li>{@code sys:statistics(Pid, get)} → {@link #statistics(Proc)} — throughput snapshot
 *   <li>{@code sys:trace(Pid, true/false)} → {@link #trace(Proc, boolean)} — live event tracing
 *   <li>{@code sys:get_log(Pid)} → {@link #getLog(Proc)} — retrieve logged events
 *   <li>{@code sys:handle_debug/4} → {@link #handleDebug} — process-internal debug pipeline
 *   <li>{@code sys:debug_options/1} → {@link #debugOptions()} etc. — create debug state
 *   <li>{@code system_code_change/4} → {@link #codeChange(Proc, Function)} — hot state transform
 * </ul>
 *
 * <p><strong>System message protocol:</strong> All {@code getState} and {@code codeChange}
 * operations enqueue a {@link SysRequest} into the process's high-priority sys channel rather than
 * accessing internal fields directly. This mirrors OTP's {@code {system, From, Request}} protocol
 * where the <em>process itself</em> handles system messages between user messages.
 *
 * <p>All operations are non-blocking for the caller unless noted. {@link #getState} and {@link
 * #codeChange} return futures (or block until the process acts) so the caller gets a consistent
 * snapshot guaranteed to be taken between two user messages.
 */
public final class ProcSys {

    private ProcSys() {}

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    /**
     * OTP {@code sys:statistics(Pid, get)} snapshot.
     *
     * @param messagesIn total messages received since process start
     * @param messagesOut total messages processed (state transitions) since process start
     * @param queueDepth current number of messages waiting in the mailbox
     */
    public record Stats(long messagesIn, long messagesOut, int queueDepth) {}

    /**
     * Get a point-in-time statistics snapshot — mirrors OTP {@code sys:statistics(Pid, get)}.
     *
     * <p>Reads atomic counters maintained by the process loop. The snapshot is not transactionally
     * consistent (counters are read independently), but is accurate within one message processing
     * cycle.
     *
     * @param proc the process to inspect
     * @return statistics snapshot
     */
    public static Stats statistics(Proc<?, ?> proc) {
        return new Stats(proc.messagesIn.sum(), proc.messagesOut.sum(), proc.mailboxSize());
    }

    // -------------------------------------------------------------------------
    // get_state / suspend / resume
    // -------------------------------------------------------------------------

    /**
     * Asynchronously fetch the current state of {@code proc} — mirrors OTP {@code
     * sys:get_state(Pid)}.
     *
     * <p>Enqueues a {@link SysRequest.GetState} into the process's high-priority sys channel. The
     * future completes after the process finishes its current in-flight message, ensuring a
     * consistent snapshot. If the process has already terminated, the future completes
     * exceptionally with {@link IllegalStateException}.
     *
     * @param proc the target process
     * @param <S> state type
     * @param <M> message type
     * @return future completing with the process state
     */
    @SuppressWarnings("unchecked")
    public static <S, M> CompletableFuture<S> getState(Proc<S, M> proc) {
        if (!proc.thread().isAlive()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("process is not alive"));
        }
        var future = new CompletableFuture<Object>();
        proc.offerSysRequest(new SysRequest.GetState(future));
        return future.thenApply(s -> (S) s);
    }

    /**
     * Pause message processing — mirrors OTP {@code sys:suspend(Pid)}.
     *
     * <p>After this call returns, the process will not dequeue any further messages until {@link
     * #resume} is called. Messages continue to accumulate in the mailbox. Used in OTP for hot-code
     * upgrades: suspend → swap code → resume.
     *
     * @param proc the process to suspend
     */
    public static void suspend(Proc<?, ?> proc) {
        proc.suspendProc();
    }

    /**
     * Resume message processing after a {@link #suspend} — mirrors OTP {@code sys:resume(Pid)}.
     *
     * @param proc the process to resume
     */
    public static void resume(Proc<?, ?> proc) {
        proc.resumeProc();
    }

    // -------------------------------------------------------------------------
    // sys:trace / sys:get_log  (external trace API)
    // -------------------------------------------------------------------------

    /**
     * Enable or disable live event tracing on {@code proc} using the default stdout formatter —
     * mirrors OTP {@code sys:trace(Pid, true|false)}.
     *
     * <p>When enabled, every message received and processed by {@code proc} is printed to {@code
     * System.out} in the format {@code "proc event = <event>"}, matching the OTP {@code
     * write_debug/3} convention. Up to 100 recent events are retained for {@link #getLog}.
     *
     * <p>Calling with {@code enable = false} detaches the observer immediately; subsequent messages
     * produce no output.
     *
     * @param proc the process to trace
     * @param enable {@code true} to attach, {@code false} to detach
     * @param <S> state type
     * @param <M> message type
     */
    public static <S, M> void trace(Proc<S, M> proc, boolean enable) {
        trace(proc, enable, DebugFormatter.defaultFormatter());
    }

    /**
     * Enable or disable live event tracing with a custom formatter — mirrors OTP {@code
     * sys:trace(Pid, true|false)} with a user-supplied {@code write_debug/3} function.
     *
     * <p>When {@code enable} is {@code false}, the {@code formatter} argument is ignored and any
     * existing observer is removed.
     *
     * @param proc the process to trace
     * @param enable {@code true} to attach, {@code false} to detach
     * @param formatter the format function for trace output
     * @param <S> state type
     * @param <M> message type
     */
    public static <S, M> void trace(Proc<S, M> proc, boolean enable, DebugFormatter<M> formatter) {
        if (!enable) {
            proc.setDebugObserver(null);
            return;
        }
        var out = new PrintWriter(System.out, true);
        proc.setDebugObserver(new DefaultDebugObserver<>(formatter, out, 100));
    }

    /**
     * Enable event tracing on {@code proc} with a custom output writer and process name — useful
     * for directing trace output to a file or test capture buffer.
     *
     * @param proc the process to trace
     * @param enable {@code true} to attach, {@code false} to detach
     * @param formatter the format function for trace output
     * @param out output destination
     * @param procName process name included in trace output as the {@code info} argument
     * @param <S> state type
     * @param <M> message type
     */
    public static <S, M> void trace(
            Proc<S, M> proc,
            boolean enable,
            DebugFormatter<M> formatter,
            PrintWriter out,
            Object procName) {
        if (!enable) {
            proc.setDebugObserver(null);
            return;
        }
        proc.setDebugObserver(new DefaultDebugObserver<>(formatter, out, procName, 100));
    }

    /**
     * Retrieve the event log from the observer attached by {@link #trace} — mirrors OTP {@code
     * sys:get_log(Pid)}.
     *
     * <p>Returns an immutable snapshot of the most-recent events (oldest first, capped at 100).
     * Returns an empty list if no observer is installed.
     *
     * @param proc the process to query
     * @param <S> state type
     * @param <M> message type
     * @return immutable list of logged events
     */
    public static <S, M> List<DebugEvent<M>> getLog(Proc<S, M> proc) {
        DebugObserver<S, M> obs = proc.getDebugObserver();
        if (obs == null) return List.of();
        return obs.getLog();
    }

    // -------------------------------------------------------------------------
    // sys:debug_options / sys:handle_debug  (process-internal debug API)
    // -------------------------------------------------------------------------

    /**
     * Create a {@link DebugOptions} with tracing and logging off — mirrors OTP {@code
     * sys:debug_options([])}.
     *
     * @param <M> message type
     * @return empty debug options
     */
    public static <M> DebugOptions<M> debugOptions() {
        return DebugOptions.none();
    }

    /**
     * Create a {@link DebugOptions} with optional tracing enabled and no log — mirrors OTP {@code
     * sys:debug_options([trace])}.
     *
     * @param trace {@code true} to enable trace output
     * @param <M> message type
     * @return debug options with tracing set accordingly
     */
    public static <M> DebugOptions<M> debugOptions(boolean trace) {
        return new DebugOptions<>(trace, 0, List.of());
    }

    /**
     * Create a {@link DebugOptions} with optional tracing and a log of the last {@code maxLog}
     * events — mirrors OTP {@code sys:debug_options([trace, {log, N}])}.
     *
     * @param trace {@code true} to enable trace output
     * @param maxLog maximum number of events to retain in the log (0 = no log)
     * @param <M> message type
     * @return debug options with tracing and log configured
     */
    public static <M> DebugOptions<M> debugOptions(boolean trace, int maxLog) {
        return new DebugOptions<>(trace, maxLog, List.of());
    }

    /**
     * Record a debug event and (if tracing is on) print it — mirrors OTP {@code
     * sys:handle_debug(Deb, Func, Info, Event)}.
     *
     * <p>Returns a new {@link DebugOptions} with the event appended to the log and/or printed.
     * Intended for use in custom/special processes that instrument themselves, matching the OTP
     * {@code ch4} example:
     *
     * <pre>{@code
     * // Erlang:
     * Deb2 = sys:handle_debug(Deb, fun ch4:write_debug/3, ch4, {in, alloc, From}),
     *
     * // Java:
     * deb = ProcSys.handleDebug(deb, Ch4::writeDebug, "ch4", new DebugEvent.In<>(msg));
     * }</pre>
     *
     * @param deb current debug state
     * @param formatter format function (OTP: {@code Func})
     * @param info caller-supplied context, e.g., process name (OTP: {@code Info})
     * @param event the debug event to record (OTP: {@code Event})
     * @param <M> message type
     * @return updated debug state with event recorded
     */
    public static <M> DebugOptions<M> handleDebug(
            DebugOptions<M> deb, DebugFormatter<M> formatter, Object info, DebugEvent<M> event) {
        var out = new PrintWriter(System.out, true);
        return deb.withEvent(event, formatter, out, info);
    }

    /**
     * Record a debug event directed to a specific output writer — overload of {@link
     * #handleDebug(DebugOptions, DebugFormatter, Object, DebugEvent)} for test capture or file
     * logging.
     *
     * @param deb current debug state
     * @param formatter format function
     * @param info caller-supplied context
     * @param event the debug event to record
     * @param out output destination
     * @param <M> message type
     * @return updated debug state
     */
    public static <M> DebugOptions<M> handleDebug(
            DebugOptions<M> deb,
            DebugFormatter<M> formatter,
            Object info,
            DebugEvent<M> event,
            PrintWriter out) {
        return deb.withEvent(event, formatter, out, info);
    }

    // -------------------------------------------------------------------------
    // system_code_change  (hot state transformation)
    // -------------------------------------------------------------------------

    /**
     * Apply a state-transformation function to a running process atomically between messages —
     * mirrors OTP's {@code system_code_change/4} hot code upgrade protocol.
     *
     * <p>Enqueues a {@link SysRequest.CodeChange} into the process's sys channel. The process
     * applies {@code transformer} to its current state between two user messages and completes the
     * returned future with the new state. The process is never paused — the transformation is
     * applied at the next sys-drain checkpoint, which happens before every user message.
     *
     * <p>Joe Armstrong: "Hot code upgrade is the ability to change a running system without
     * stopping it. The key is that the process itself decides when it is safe to upgrade — between
     * message boundaries."
     *
     * <p>Example — migrate state from old schema {@code v1} to new schema {@code v2}:
     *
     * <pre>{@code
     * // Erlang: system_code_change(OldState, _Module, _OldVsn, _Extra) ->
     * //     {ok, migrate(OldState)}.
     *
     * // Java:
     * S newState = ProcSys.codeChange(proc, old -> new NewState(old.field1(), 0));
     * }</pre>
     *
     * @param proc the target process
     * @param transformer state transformation function — called with the current state; must return
     *     the new state
     * @param <S> state type
     * @param <M> message type
     * @return the new state after transformation
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws IllegalStateException if the process has terminated or the transformation threw
     */
    @SuppressWarnings("unchecked")
    public static <S, M> S codeChange(Proc<S, M> proc, Function<S, S> transformer)
            throws InterruptedException {
        var future = new CompletableFuture<Object>();
        proc.offerSysRequest(new SysRequest.CodeChange(s -> transformer.apply((S) s), future));
        try {
            return (S) future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("codeChange failed", e.getCause());
        }
    }

    // -------------------------------------------------------------------------
    // Internal: DefaultDebugObserver
    // -------------------------------------------------------------------------

    /**
     * Default implementation of {@link DebugObserver} installed by {@link #trace}. Logs events in a
     * bounded ring buffer and prints each event via the supplied {@link DebugFormatter}.
     */
    private static final class DefaultDebugObserver<S, M> implements DebugObserver<S, M> {

        private final DebugFormatter<M> formatter;
        private final PrintWriter out;
        private final Object procName;
        private final int maxLog;

        // Ring buffer — accessed from process virtual thread (onIn/onOut) and
        // potentially other threads (getLog). Synchronize on `this`.
        private final ArrayDeque<DebugEvent<M>> log;

        DefaultDebugObserver(DebugFormatter<M> formatter, PrintWriter out, int maxLog) {
            this(formatter, out, "proc", maxLog);
        }

        DefaultDebugObserver(
                DebugFormatter<M> formatter, PrintWriter out, Object procName, int maxLog) {
            this.formatter = formatter;
            this.out = out;
            this.procName = procName;
            this.maxLog = maxLog;
            this.log = new ArrayDeque<>(maxLog + 1);
        }

        @Override
        public void onIn(M msg) {
            record(new DebugEvent.In<>(msg));
        }

        @Override
        public void onOut(S state, M msg) {
            record(new DebugEvent.Out<>(msg, state));
        }

        private void record(DebugEvent<M> event) {
            formatter.format(out, event, procName);
            synchronized (this) {
                log.addLast(event);
                if (log.size() > maxLog) log.removeFirst();
            }
        }

        @Override
        public synchronized List<DebugEvent<M>> getLog() {
            return List.copyOf(new ArrayList<>(log));
        }
    }
}
