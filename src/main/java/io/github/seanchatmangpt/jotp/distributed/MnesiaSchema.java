package io.github.seanchatmangpt.jotp.distributed;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Schema definition for a Mnesia-style table.
 *
 * <p>Defines the structure, replication strategy, and attributes of a distributed table. This is
 * the Java 26 equivalent of Erlang's Mnesia schema definition.
 *
 * <p><strong>Mapping to Erlang Mnesia:</strong>
 *
 * <pre>{@code
 * Erlang Mnesia                     Java 26 JOTP
 * ──────────────────────────────    ────────────────────────────────────
 * -record(user, {id, name, email})  record MnesiaSchema(tableName, attributes)
 * {ram_copies, [node1, node2]}      ReplicationType.RAM_COPIES
 * {disc_copies, [node1, node2]}     ReplicationType.DISC_COPIES
 * {record_name, user}               tableName
 * }</pre>
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li><strong>Table Name:</strong> Unique identifier for the table across the cluster
 *   <li><strong>Attributes:</strong> Ordered list of column names (first is implicitly the key)
 *   <li><strong>Replication Type:</strong> COPIES (in-memory), DISC_COPIES (durable), or
 *       RAM_COPIES (replicated in-memory)
 *   <li><strong>Replica Nodes:</strong> List of nodes that hold copies of this table
 *   <li><strong>TTL:</strong> Optional time-to-live for automatic record expiration
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var schema = new MnesiaSchema(
 *     "users",
 *     List.of("id", "name", "email"),
 *     ReplicationType.DISC_COPIES,
 *     List.of("node1", "node2", "node3"),
 *     Optional.of(86400L) // 24 hours TTL
 * );
 * }</pre>
 *
 * @param tableName the name of the table (unique across cluster)
 * @param attributes ordered list of column names
 * @param replicationType replication strategy
 * @param replicaNodes nodes that hold copies of this table
 * @param ttl optional time-to-live in seconds
 * @see ReplicationType
 * @see MnesiaBackend
 */
public record MnesiaSchema(
        String tableName,
        List<String> attributes,
        ReplicationType replicationType,
        List<String> replicaNodes,
        Optional<Long> ttl) {

    public MnesiaSchema {
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(attributes, "attributes cannot be null");
        Objects.requireNonNull(replicationType, "replicationType cannot be null");
        Objects.requireNonNull(replicaNodes, "replicaNodes cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");

        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName cannot be empty");
        }
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("attributes cannot be empty");
        }
        if (replicaNodes.isEmpty()) {
            throw new IllegalArgumentException("replicaNodes cannot be empty");
        }
        if (ttl.isPresent() && ttl.get() <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    /**
     * Replication strategy for Mnesia tables — mapping to Erlang Mnesia's storage models.
     *
     * <ul>
     *   <li><strong>COPIES:</strong> In-memory only (equivalent to ram_copies) — fast but lost on
     *       node crash
     *   <li><strong>DISC_COPIES:</strong> Durable on disk (equivalent to disc_copies) — survives
     *       node crashes, written to PostgreSQL
     *   <li><strong>RAM_COPIES:</strong> Replicated in-memory (equivalent to ram_copies with
     *       replication) — fast and distributed via Redis
     * </ul>
     */
    public enum ReplicationType {
        /** In-memory only, no persistence */
        COPIES,
        /** Durable, persisted to PostgreSQL */
        DISC_COPIES,
        /** Replicated in-memory via Redis, fast access */
        RAM_COPIES
    }

    /**
     * Get the primary key attribute (first attribute).
     *
     * @return the name of the primary key column
     */
    public String primaryKey() {
        return attributes.get(0);
    }

    /**
     * Check if this table has TTL enabled.
     *
     * @return true if TTL is set
     */
    public boolean hasTTL() {
        return ttl.isPresent();
    }

    /**
     * Get the TTL value in seconds.
     *
     * @return TTL in seconds, or 0 if not set
     */
    public long getTTLSeconds() {
        return ttl.orElse(0L);
    }
}
