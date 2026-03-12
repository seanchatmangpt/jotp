package io.github.seanchatmangpt.jotp;

/**
 * OTP application run type — governs cascade behavior when an application terminates.
 *
 * <p>In Erlang/OTP, the run type is supplied as the second argument to {@code application:start/2}.
 * JOTP models this as a Java enum.
 *
 * <p><strong>Erlang equivalents:</strong>
 *
 * <ul>
 *   <li>{@code permanent} → {@link #PERMANENT}
 *   <li>{@code transient} → {@link #TRANSIENT}
 *   <li>{@code temporary} → {@link #TEMPORARY} (default)
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Erlang: application:start(ch_app, permanent).
 * ApplicationController.start("ch-app", RunType.PERMANENT);
 *
 * // Erlang: application:start(ch_app).  % defaults to temporary
 * ApplicationController.start("ch-app");
 * }</pre>
 *
 * @see ApplicationController
 */
public enum RunType {

    /**
     * If a permanent application terminates for any reason, all other running applications and the
     * runtime are also stopped.
     *
     * <p>Use for critical infrastructure that the system cannot operate without.
     */
    PERMANENT,

    /**
     * If a transient application terminates <em>abnormally</em> (with an exception), all other
     * applications are stopped. A <em>normal</em> termination is merely logged.
     *
     * <p>Use for applications that are important but whose clean shutdown should not cascade.
     *
     * <p>Use {@link ApplicationController#stop(String, boolean)} with {@code abnormal=true} to
     * signal an abnormal termination that triggers a cascade; calling {@link
     * ApplicationController#stop(String)} (or {@code stop(name, false)}) treats the termination as
     * normal and does not cascade.
     */
    TRANSIENT,

    /**
     * If a temporary application terminates, the event is logged but no other application is
     * affected.
     *
     * <p>This is the default start type ({@code application:start/1}).
     */
    TEMPORARY
}
