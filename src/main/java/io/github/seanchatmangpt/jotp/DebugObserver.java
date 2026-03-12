package io.github.seanchatmangpt.jotp;

import java.util.List;

/**
 * Package-private callback interface installed on a {@link Proc} by {@link ProcSys#trace}.
 *
 * <p>The process loop invokes {@link #onIn} before applying the handler to a message, and {@link
 * #onOut} immediately after. This gives the observer a symmetric view matching OTP's {@code
 * sys:handle_debug} convention for {@code {in, Msg}} and {@code {out, Msg, State}} events.
 *
 * <p>Implementations must be thread-safe: {@link #onIn} and {@link #onOut} are called from the
 * process's virtual thread; {@link #getLog} may be called from any thread.
 *
 * @param <S> process state type
 * @param <M> message type
 */
interface DebugObserver<S, M> {

    /**
     * Called when a message is dequeued from the mailbox, before the handler runs.
     *
     * @param msg the incoming message
     */
    void onIn(M msg);

    /**
     * Called after the handler returns the new state.
     *
     * @param state the new process state
     * @param msg the message that produced this state (same as the preceding {@link #onIn} call)
     */
    void onOut(S state, M msg);

    /**
     * Return an immutable snapshot of logged {@link DebugEvent}s — mirrors OTP's {@code
     * sys:get_log(Pid)}.
     *
     * @return snapshot of the event log (oldest first)
     */
    List<DebugEvent<M>> getLog();
}
