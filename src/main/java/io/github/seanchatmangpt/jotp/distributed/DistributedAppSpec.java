package io.github.seanchatmangpt.jotp.distributed;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a distributed application — which nodes can run it and in what priority order.
 *
 * <p>Mirrors Erlang/OTP's {@code distributed} kernel configuration parameter:
 *
 * <pre>{@code
 * % Erlang/OTP:
 * {distributed, [{myapp, 5000, [cp1@cave, {cp2@cave, cp3@cave}]}]}
 *
 * // JOTP equivalent:
 * new DistributedAppSpec("myapp",
 *     List.of(List.of(node1), List.of(node2, node3)),
 *     Duration.ofSeconds(5))
 * }</pre>
 *
 * <p>The {@code nodes} list is priority-ordered: the first group has the highest priority. Within
 * each group (inner list), priority order is undefined — any live node in the group can run the
 * app. When the first group's node(s) all go down, the second group takes over, and so on.
 *
 * @param name the application name (must match across all nodes)
 * @param nodes priority-ordered groups of nodes; inner list = same-priority peers
 * @param failoverTimeout how long to wait before restarting the app on another node after the
 *     primary goes down; Erlang default is 0 (immediate)
 */
public record DistributedAppSpec(String name, List<List<NodeId>> nodes, Duration failoverTimeout) {

    public DistributedAppSpec {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nodes, "nodes must not be null");
        Objects.requireNonNull(failoverTimeout, "failoverTimeout must not be null");
        if (nodes.isEmpty()) throw new IllegalArgumentException("nodes must not be empty");
        if (failoverTimeout.isNegative())
            throw new IllegalArgumentException("failoverTimeout must not be negative");
    }

    /**
     * Flattens the priority groups into a single ordered list.
     *
     * <p>Nodes are ordered by group priority (lowest index = highest priority). Within each group,
     * the list order is used as a deterministic tiebreaker.
     *
     * @return flat priority-ordered list of all nodes
     */
    public List<NodeId> priorityList() {
        return nodes.stream().flatMap(List::stream).toList();
    }

    /**
     * Convenience constructor with zero failover timeout.
     *
     * @param name application name
     * @param nodes priority-ordered node groups
     */
    public static DistributedAppSpec immediate(String name, List<List<NodeId>> nodes) {
        return new DistributedAppSpec(name, nodes, Duration.ZERO);
    }
}
