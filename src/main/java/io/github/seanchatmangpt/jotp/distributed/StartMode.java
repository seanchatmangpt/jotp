package io.github.seanchatmangpt.jotp.distributed;

/**
 * The mode in which a distributed application starts on a node.
 *
 * <p>Directly maps to Erlang/OTP's {@code Module:start/2} argument:
 *
 * <pre>{@code
 * Erlang OTP                              JOTP
 * ──────────────────────────────────────  ────────────────────────────────────
 * Module:start(normal, StartArgs)      →  callbacks.onStart(new Normal())
 * Module:start({failover, Node}, Args) →  callbacks.onStart(new Failover(fromNode))
 * Module:start({takeover, Node}, Args) →  callbacks.onStart(new Takeover(fromNode))
 * }</pre>
 *
 * <p>{@link Normal} — the application starts for the first time on the highest-priority live node.
 *
 * <p>{@link Failover} — the primary node went down; this node is restarting the application after
 * the configured {@code failoverTimeout}.
 *
 * <p>{@link Takeover} — a higher-priority node rejoined the cluster and reclaims the application
 * from a lower-priority node that was running it during the primary's absence.
 */
public sealed interface StartMode permits StartMode.Normal, StartMode.Failover, StartMode.Takeover {

    /** The application starts normally on the highest-priority live node. */
    record Normal() implements StartMode {}

    /**
     * The application restarts after the primary node failed.
     *
     * @param from the node that went down
     */
    record Failover(NodeId from) implements StartMode {}

    /**
     * A higher-priority node reclaims the application from a lower-priority node.
     *
     * @param from the lower-priority node that was previously running the application
     */
    record Takeover(NodeId from) implements StartMode {}
}
