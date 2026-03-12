package io.github.seanchatmangpt.jotp;

/**
 * A single instrumentation event recorded during {@link ProcSys#handleDebug} or by the external
 * trace observer attached via {@link ProcSys#trace}.
 *
 * <p>Mirrors OTP's sys event representation: {@code {in,Msg}}, {@code {out,Msg,State}}, or a custom
 * user-defined term. Events are the currency of the debug pipeline — they flow through {@link
 * DebugFormatter} for trace output and accumulate in {@link DebugOptions#log()} for later
 * inspection.
 *
 * <p>Usage with process-internal API (mirrors OTP {@code ch4} special-process example):
 *
 * <pre>{@code
 * // In your message loop — before handling:
 * deb = ProcSys.handleDebug(deb, MyProc::writeDebug, "my_proc", new DebugEvent.In<>(msg));
 * S next = handler.apply(state, msg);
 * // After handling:
 * deb = ProcSys.handleDebug(deb, MyProc::writeDebug, "my_proc", new DebugEvent.Out<>(msg, next));
 * }</pre>
 *
 * @param <M> message type of the observed process
 * @see ProcSys#handleDebug
 * @see ProcSys#trace
 */
public sealed interface DebugEvent<M> {

    /**
     * A message was received by the process — mirrors OTP {@code {in, Msg}} and {@code {in, Msg,
     * From}}.
     *
     * @param msg the received message
     * @param <M> message type
     */
    record In<M>(M msg) implements DebugEvent<M> {}

    /**
     * A message was processed and the handler returned new state — mirrors OTP {@code {out, Msg,
     * To}} or {@code {out, Reply, To, State}}.
     *
     * @param msg the processed message (same as the preceding {@link In})
     * @param state the new process state after the handler returned
     * @param <M> message type
     */
    record Out<M>(M msg, Object state) implements DebugEvent<M> {}

    /**
     * A user-defined event recorded via {@link ProcSys#handleDebug} — any term the process author
     * considers significant. Mirrors OTP's ability to pass arbitrary {@code Event} terms to {@code
     * sys:handle_debug/4}.
     *
     * @param event the user-defined event term
     * @param <M> message type (unused, but required by the sealed hierarchy)
     */
    record Custom<M>(Object event) implements DebugEvent<M> {}
}
