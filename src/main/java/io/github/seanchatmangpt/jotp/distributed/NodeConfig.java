package io.github.seanchatmangpt.jotp.distributed;

import java.time.Duration;
import java.util.List;

/**
 * Node startup synchronization configuration.
 *
 * <p>Mirrors Erlang/OTP's Kernel configuration parameters for distributed startup:
 *
 * <pre>{@code
 * % Erlang/OTP:
 * [{kernel, [
 *   {sync_nodes_mandatory, [cp2@cave, cp3@cave]},
 *   {sync_nodes_optional,  []},
 *   {sync_nodes_timeout,   5000}
 * ]}]
 * }</pre>
 *
 * <p>The node waits for all {@code syncNodesMandatory} nodes to be reachable before allowing
 * applications to start. If they are not all reachable within {@code syncNodesTimeout}, the node
 * terminates (mandatory) or proceeds anyway (optional).
 *
 * <p>For simple use cases, use {@link #defaults()} which requires no peer synchronization.
 *
 * @param syncNodesMandatory nodes that must be reachable before this node allows app starts
 * @param syncNodesOptional nodes that should be waited for but are not required
 * @param syncNodesTimeout how long to wait for mandatory and optional nodes to come up
 */
public record NodeConfig(
        List<NodeId> syncNodesMandatory,
        List<NodeId> syncNodesOptional,
        Duration syncNodesTimeout) {

    /**
     * Default configuration with no mandatory peer synchronization.
     *
     * <p>Suitable for single-node setups or when synchronization is handled externally.
     *
     * @return default NodeConfig
     */
    public static NodeConfig defaults() {
        return new NodeConfig(List.of(), List.of(), Duration.ZERO);
    }
}
