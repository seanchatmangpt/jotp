package io.github.seanchatmangpt.jotp;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable debug state for process-internal instrumentation — mirrors OTP's opaque {@code Deb}
 * structure returned by {@code sys:debug_options/1} and threaded through each call to {@code
 * sys:handle_debug/4}.
 *
 * <p>Like Erlang's {@code Deb}, this record is immutable: each call to {@link ProcSys#handleDebug}
 * returns a new {@code DebugOptions} with the event appended to the log. The process carries the
 * current {@code DebugOptions} as a local variable in its loop.
 *
 * <p>Usage pattern — mirrors the OTP {@code ch4} special-process example:
 *
 * <pre>{@code
 * // Initialise (equiv: Deb = sys:debug_options([])):
 * DebugOptions<Msg> deb = ProcSys.debugOptions();
 *
 * // In message loop — before handling:
 * deb = ProcSys.handleDebug(deb, MyProc::writeDebug, "my_proc", new DebugEvent.In<>(msg));
 * S next = handler.apply(state, msg);
 * // After handling:
 * deb = ProcSys.handleDebug(deb, MyProc::writeDebug, "my_proc", new DebugEvent.Out<>(msg, next));
 * }</pre>
 *
 * @param tracing whether trace output is currently active (equiv: {@code sys:trace(Pid, true)})
 * @param maxLog maximum number of events to retain in {@link #log}; 0 means logging is off
 * @param log immutable snapshot of the most-recent events (oldest first, capped at {@link #maxLog}
 *     entries)
 * @param <M> message type of the observed process
 * @see ProcSys#debugOptions()
 * @see ProcSys#handleDebug
 */
public record DebugOptions<M>(boolean tracing, int maxLog, List<DebugEvent<M>> log) {

    /** Compact canonical constructor — ensures log is always unmodifiable. */
    public DebugOptions {
        log = Collections.unmodifiableList(log);
    }

    /**
     * Create a {@code DebugOptions} with no tracing and no log — equivalent to OTP's {@code
     * sys:debug_options([])}.
     *
     * @param <M> message type
     * @return empty debug options
     */
    public static <M> DebugOptions<M> none() {
        return new DebugOptions<>(false, 0, List.of());
    }

    /**
     * Return a new {@code DebugOptions} with {@code event} appended to the log (if {@link #maxLog}
     * {@code > 0}) and printed to {@code dev} if tracing is on. Called internally by {@link
     * ProcSys#handleDebug} — not intended for direct use.
     *
     * @param event the event to record
     * @param formatter the format function to use for trace output
     * @param dev output device for trace output
     * @param info caller-supplied context (e.g., process name)
     * @return updated debug options
     */
    DebugOptions<M> withEvent(
            DebugEvent<M> event, DebugFormatter<M> formatter, PrintWriter dev, Object info) {
        if (tracing) {
            formatter.format(dev, event, info);
        }
        if (maxLog <= 0) {
            return this;
        }
        var next = new ArrayList<>(log);
        next.add(event);
        if (next.size() > maxLog) {
            next.removeFirst();
        }
        return new DebugOptions<>(tracing, maxLog, next);
    }
}
