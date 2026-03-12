package io.github.seanchatmangpt.jotp;

/**
 * OTP application start type — the first argument passed to an {@link ApplicationCallback#start}.
 *
 * <p>In Erlang/OTP, the start type is {@code normal}, {@code {takeover, Node}}, or {@code
 * {failover, Node}}. JOTP models this as a sealed interface with exhaustive pattern matching.
 *
 * <p><strong>Erlang equivalents:</strong>
 *
 * <ul>
 *   <li>{@code normal} → {@link Normal}
 *   <li>{@code {takeover, Node}} → {@link Takeover}
 *   <li>{@code {failover, Node}} → {@link Failover}
 * </ul>
 *
 * <p><strong>Usage in a callback (exhaustive arrow-syntax switch):</strong>
 *
 * <pre>{@code
 * ApplicationCallback<Supervisor> callback = (startType, args) -> {
 *     var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 *     return switch (startType) {
 *         case StartType.Normal()            -> supervisor;              // standard startup
 *         case StartType.Takeover(var node)  -> takeoverFrom(node);     // take over from node
 *         case StartType.Failover(var node)  -> failoverFrom(node);     // replace crashed node
 *     };
 * };
 * }</pre>
 *
 * <p><strong>Compiler-enforced exhaustiveness:</strong> All three variants — {@link Normal}, {@link
 * Takeover}, and {@link Failover} — must be handled in a switch expression. Because {@code
 * StartType} is a sealed interface, the Java compiler rejects any switch that omits a permitted
 * subtype. There is no need for a {@code default} branch; the absence of one is intentional and
 * ensures that adding a new variant in the future produces a compile error at every call site.
 *
 * @see ApplicationCallback
 * @see ApplicationController
 */
public sealed interface StartType permits StartType.Normal, StartType.Takeover, StartType.Failover {

    /**
     * Normal startup — the usual case.
     *
     * <p>Erlang equivalent: the atom {@code normal}.
     */
    record Normal() implements StartType {}

    /**
     * Takeover startup — this node is taking over from {@code node}.
     *
     * <p>Used in distributed Erlang/OTP when a higher-priority node becomes available.
     *
     * @param node the node being taken over
     */
    record Takeover(String node) implements StartType {}

    /**
     * Failover startup — this node is replacing the crashed {@code node}.
     *
     * <p>Used in distributed Erlang/OTP when the primary node crashes.
     *
     * @param node the node that crashed
     */
    record Failover(String node) implements StartType {}
}
