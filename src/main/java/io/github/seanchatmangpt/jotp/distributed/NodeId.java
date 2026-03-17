package io.github.seanchatmangpt.jotp.distributed;

/**
 * Identifies a node in the distributed cluster.
 *
 * <p>Analogous to Erlang's node name (e.g., {@code cp1@cave}). Each JVM running a {@link
 * DistributedNode} has a unique {@code NodeId} composed of a human-readable name, host, and TCP
 * port.
 *
 * <p>The wire format {@code name:host:port} is used in the TCP protocol for inter-node coordination
 * messages (e.g., takeover announcements).
 */
public record NodeId(String name, String host, int port) {

    /**
     * Parse a {@code NodeId} from the wire format {@code "name:host:port"}.
     *
     * @param wire the wire-encoded node identifier
     * @return parsed NodeId
     * @throws IllegalArgumentException if the format is invalid
     */
    public static NodeId parse(String wire) {
        String[] parts = wire.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid NodeId wire format: " + wire);
        }
        return new NodeId(parts[0], parts[1], Integer.parseInt(parts[2]));
    }

    /**
     * Encode this NodeId in wire format {@code "name:host:port"}.
     *
     * @return wire-encoded string
     */
    public String wire() {
        return name + ":" + host + ":" + port;
    }

    /**
     * Create a NodeId from a simple name (for testing or single-node scenarios).
     *
     * @param name the node name
     * @return a NodeId with default host and port
     */
    public static NodeId of(String name) {
        return new NodeId(name, "localhost", 0);
    }
}
