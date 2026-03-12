package io.github.seanchatmangpt.jotp;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Sealed hierarchy of system-message requests sent through the high-priority sys channel of a
 * {@link Proc} — the Java equivalent of OTP's {@code {system, From, Request}} message protocol.
 *
 * <p>In Erlang, OTP processes receive {@code {system, From, Request}} and delegate to {@code
 * sys:handle_system_msg/6}. In JOTP, {@link ProcSys} enqueues {@code SysRequest} values into {@link
 * Proc#sysQueue}; the process loop drains this queue before each user message, ensuring sys
 * operations are served promptly without being blocked by a flooded user mailbox.
 *
 * <p>This type is package-private. Callers use {@link ProcSys} public methods; the sealed hierarchy
 * here is the internal wire protocol.
 */
sealed interface SysRequest permits SysRequest.GetState, SysRequest.CodeChange {

    /**
     * Request the current state of the process — mirrors {@code sys:get_state(Pid)}.
     *
     * <p>The process loop completes {@link #reply} with the current state (as {@code Object} to
     * avoid unchecked-cast noise at the call site; {@link ProcSys#getState} casts back via {@code
     * thenApply}).
     */
    record GetState(CompletableFuture<Object> reply) implements SysRequest {}

    /**
     * Apply a state-transformation function atomically between two user messages — mirrors {@code
     * system_code_change/4}.
     *
     * <p>The function is called with the current state (as {@code Object}); it must return the new
     * state. After application the process continues with the transformed state. {@link #reply} is
     * completed with the new state value so the caller of {@link ProcSys#codeChange} can observe
     * the result.
     *
     * <p>Joe Armstrong: "Hot code upgrade is the ability to change a running system without
     * stopping it. The key is that the process itself decides when it is safe to upgrade — between
     * message boundaries."
     */
    record CodeChange(Function<Object, Object> fn, CompletableFuture<Object> reply)
            implements SysRequest {}
}
