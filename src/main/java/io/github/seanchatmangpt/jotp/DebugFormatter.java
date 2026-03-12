package io.github.seanchatmangpt.jotp;

import java.io.PrintWriter;

/**
 * Pluggable format function for debug event output — mirrors OTP's {@code fun write_debug/3}.
 *
 * <p>Called by {@link ProcSys#handleDebug} for each instrumentation event when tracing is enabled,
 * and by the observer installed via {@link ProcSys#trace}. The function receives an output device,
 * the event, and caller-supplied info (typically a process name).
 *
 * <p>OTP equivalent:
 *
 * <pre>{@code
 * write_debug(Dev, Event, Name) ->
 *     io:format(Dev, "~p event = ~p~n", [Name, Event]).
 * }</pre>
 *
 * <p>Java equivalent:
 *
 * <pre>{@code
 * DebugFormatter<Msg> fmt = (dev, event, info) ->
 *     dev.printf("%s event = %s%n", info, event);
 * }</pre>
 *
 * @param <M> message type of the observed process
 * @see ProcSys#handleDebug
 * @see ProcSys#trace(Proc, boolean, DebugFormatter)
 */
@FunctionalInterface
public interface DebugFormatter<M> {

    /**
     * Format and write a single debug event.
     *
     * @param dev output destination (backed by stdout for external trace, or any writer)
     * @param event the debug event — {@link DebugEvent.In}, {@link DebugEvent.Out}, or {@link
     *     DebugEvent.Custom}
     * @param info caller-supplied context, typically a process name or identifier
     */
    void format(PrintWriter dev, DebugEvent<M> event, Object info);

    /**
     * Default formatter that prints {@code "info event = event\n"} — matches the OTP convention
     * from the {@code write_debug/3} example in the sys/proc_lib documentation.
     *
     * @param <M> message type
     * @return default format function
     */
    static <M> DebugFormatter<M> defaultFormatter() {
        return (dev, event, info) -> dev.printf("%s event = %s%n", info, event);
    }
}
