package io.github.seanchatmangpt.jotp.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PGBinaryObject;

/**
 * PostgreSQL-backed persistence for JOTP clusters with full ACID compliance.
 *
 * <p>Provides durable storage of process state snapshots with:
 *
 * <ul>
 *   <li><strong>ACID Transactions:</strong> Guarantees consistency across crashes
 *   <li><strong>Complex Queries:</strong> SQL access to process state for analytics
 *   <li><strong>Replication:</strong> Multi-region failover via PostgreSQL streaming replication
 *   <li><strong>Event Sourcing:</strong> Immutable append-only event log table
 *   <li><strong>Idempotent Recovery:</strong> Atomic state + ACK writes via transactions
 * </ul>
 *
 * <p><strong>Schema:</strong>
 *
 * <pre>{@code
 * CREATE TABLE jotp_snapshots (
 *   key VARCHAR(255) PRIMARY KEY,
 *   snapshot BYTEA NOT NULL,
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE TABLE jotp_acks (
 *   key VARCHAR(255) PRIMARY KEY,
 *   sequence_number BIGINT NOT NULL,
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE TABLE jotp_events (
 *   id BIGSERIAL PRIMARY KEY,
 *   entity_key VARCHAR(255) NOT NULL,
 *   event_data BYTEA NOT NULL,
 *   event_type VARCHAR(64) NOT NULL,
 *   sequence_number BIGINT NOT NULL,
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE INDEX ON jotp_events(entity_key, sequence_number);
 * }</pre>
 *
 * <p><strong>Features:</strong>
 *
 * <pre>
 * [JOTP Process] ─────────────┐
 * [JOTP Process] ─────────────┤
 * [JOTP Process] ─────────────┼──→ [PostgresBackend]
 *                              │        ↓
 *                              └───→ [PostgreSQL Primary]
 *                                      ↓
 *                            ┌─────────┴─────────┐
 *                            ↓                   ↓
 *                     [Replica 1]         [Replica 2]
 * </pre>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * PostgresBackend backend = new PostgresBackend("localhost", 5432, "jotp_prod");
 * backend.initializeTables();
 * backend.save("proc-001", serializedState);
 * Optional<byte[]> state = backend.load("proc-001");
 * backend.close();
 * }</pre>
 *
 * @see PersistenceBackend
 */
public class PostgresBackend implements PersistenceBackend {

    private static final String SNAPSHOTS_TABLE = "jotp_snapshots";
    private static final String ACKS_TABLE = "jotp_acks";
    private static final String EVENTS_TABLE = "jotp_events";

    private final DataSource dataSource;
    private final Map<String, Long> localAckCache;
    private volatile boolean closed = false;

    /**
     * Create a PostgreSQL backend.
     *
     * @param host PostgreSQL host
     * @param port PostgreSQL port
     * @param database database name
     */
    public PostgresBackend(String host, int port, String database) {
        this(host, port, database, "jotp", "");
    }

    /**
     * Create a PostgreSQL backend with credentials.
     *
     * @param host PostgreSQL host
     * @param port PostgreSQL port
     * @param database database name
     * @param user database user
     * @param password database password
     */
    public PostgresBackend(String host, int port, String database, String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[] {host});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(database);
        source.setUser(user);
        if (password != null && !password.isEmpty()) {
            source.setPassword(password);
        }

        // Connection pool settings
        source.setConnectTimeout(10);
        source.setLoginTimeout(10);

        this.dataSource = source;
        this.localAckCache = new ConcurrentHashMap<>();
    }

    /**
     * Initialize database tables. Call once on startup.
     *
     * @throws PersistenceException if table creation fails
     */
    public void initializeTables() {
        checkClosed();

        try (Connection conn = dataSource.getConnection()) {
            // Create snapshots table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS "
                                + SNAPSHOTS_TABLE
                                + " ("
                                + "  key VARCHAR(255) PRIMARY KEY,"
                                + "  snapshot BYTEA NOT NULL,"
                                + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                                + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                                + ")");
            }

            // Create ACKs table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS "
                                + ACKS_TABLE
                                + " ("
                                + "  key VARCHAR(255) PRIMARY KEY,"
                                + "  sequence_number BIGINT NOT NULL,"
                                + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                                + ")");
            }

            // Create events table (for event sourcing)
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS "
                                + EVENTS_TABLE
                                + " ("
                                + "  id BIGSERIAL PRIMARY KEY,"
                                + "  entity_key VARCHAR(255) NOT NULL,"
                                + "  event_data BYTEA NOT NULL,"
                                + "  event_type VARCHAR(64) NOT NULL,"
                                + "  sequence_number BIGINT NOT NULL,"
                                + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                                + ")");
            }

            // Create indexes
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_events_entity_seq ON "
                                + EVENTS_TABLE
                                + " (entity_key, sequence_number)");
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to initialize tables", e);
        }
    }

    @Override
    public void save(String key, byte[] snapshot) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        try (Connection conn = dataSource.getConnection()) {
            String sql =
                    "INSERT INTO "
                            + SNAPSHOTS_TABLE
                            + " (key, snapshot) VALUES (?, ?) "
                            + "ON CONFLICT (key) DO UPDATE SET snapshot = ?, updated_at = CURRENT_TIMESTAMP";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                stmt.setBytes(2, snapshot);
                stmt.setBytes(3, snapshot);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save state for key: " + key, e);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT snapshot FROM " + SNAPSHOTS_TABLE + " WHERE key = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getBytes("snapshot"));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load state for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = "DELETE FROM " + SNAPSHOTS_TABLE + " WHERE key = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, key);
                    stmt.executeUpdate();
                }

                deleteAck(key);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete state for key: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT 1 FROM " + SNAPSHOTS_TABLE + " WHERE key = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to check existence for key: " + key, e);
        }
    }

    @Override
    public Iterable<String> listKeys() {
        checkClosed();

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT key FROM " + SNAPSHOTS_TABLE;

            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                List<String> keys = new ArrayList<>();
                while (rs.next()) {
                    keys.add(rs.getString("key"));
                }
                return keys;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list keys", e);
        }
    }

    @Override
    public void writeAtomic(String key, byte[] stateBytes, byte[] ackBytes) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(stateBytes, "stateBytes cannot be null");
        Objects.requireNonNull(ackBytes, "ackBytes cannot be null");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Write snapshot
                String snapshotSql =
                        "INSERT INTO "
                                + SNAPSHOTS_TABLE
                                + " (key, snapshot) VALUES (?, ?) "
                                + "ON CONFLICT (key) DO UPDATE SET snapshot = ?, updated_at = CURRENT_TIMESTAMP";
                try (PreparedStatement stmt = conn.prepareStatement(snapshotSql)) {
                    stmt.setString(1, key);
                    stmt.setBytes(2, stateBytes);
                    stmt.setBytes(3, stateBytes);
                    stmt.executeUpdate();
                }

                // Write ACK
                long ackSeq = bytesToLong(ackBytes);
                String ackSql =
                        "INSERT INTO " + ACKS_TABLE + " (key, sequence_number) VALUES (?, ?) "
                                + "ON CONFLICT (key) DO UPDATE SET sequence_number = ?, updated_at = CURRENT_TIMESTAMP";
                try (PreparedStatement stmt = conn.prepareStatement(ackSql)) {
                    stmt.setString(1, key);
                    stmt.setLong(2, ackSeq);
                    stmt.setLong(3, ackSeq);
                    stmt.executeUpdate();
                }

                conn.commit();
                localAckCache.put(key, ackSeq);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to write atomic state for key: " + key, e);
        }
    }

    @Override
    public Optional<Long> getAckSequence(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        // Check local cache first
        if (localAckCache.containsKey(key)) {
            return Optional.of(localAckCache.get(key));
        }

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT sequence_number FROM " + ACKS_TABLE + " WHERE key = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long ackSeq = rs.getLong("sequence_number");
                        localAckCache.put(key, ackSeq);
                        return Optional.of(ackSeq);
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to get ACK sequence for key: " + key, e);
        }
    }

    @Override
    public void deleteAck(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Connection conn = dataSource.getConnection()) {
            String sql = "DELETE FROM " + ACKS_TABLE + " WHERE key = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                stmt.executeUpdate();
                localAckCache.remove(key);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete ACK for key: " + key, e);
        }
    }

    /**
     * Append an event to the event sourcing log.
     *
     * @param entityKey the entity this event applies to
     * @param eventData the serialized event
     * @param eventType event type for classification
     * @param sequenceNumber sequence number for idempotence
     * @throws PersistenceException if append fails
     */
    public void appendEvent(String entityKey, byte[] eventData, String eventType, long sequenceNumber) {
        checkClosed();

        try (Connection conn = dataSource.getConnection()) {
            String sql =
                    "INSERT INTO "
                            + EVENTS_TABLE
                            + " (entity_key, event_data, event_type, sequence_number) VALUES (?, ?, ?, ?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, entityKey);
                stmt.setBytes(2, eventData);
                stmt.setString(3, eventType);
                stmt.setLong(4, sequenceNumber);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to append event for entity: " + entityKey, e);
        }
    }

    /**
     * Get events for an entity, optionally since a specific sequence number.
     *
     * @param entityKey the entity key
     * @param sinceSequence minimum sequence number (0 = all events)
     * @return list of events in order
     * @throws PersistenceException if query fails
     */
    public List<byte[]> getEvents(String entityKey, long sinceSequence) {
        checkClosed();

        try (Connection conn = dataSource.getConnection()) {
            String sql =
                    "SELECT event_data FROM "
                            + EVENTS_TABLE
                            + " WHERE entity_key = ? AND sequence_number >= ? ORDER BY sequence_number";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, entityKey);
                stmt.setLong(2, sinceSequence);

                List<byte[]> events = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        events.add(rs.getBytes("event_data"));
                    }
                }
                return events;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to get events for entity: " + entityKey, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        // Connection pool is managed by DataSource
    }

    private void checkClosed() {
        if (closed) {
            throw new PersistenceException("Backend is closed");
        }
    }

    private long bytesToLong(byte[] bytes) {
        if (bytes.length != 8) {
            throw new IllegalArgumentException("ACK bytes must be 8 bytes long");
        }
        return ((long) bytes[0] & 0xFFL) << 56
                | ((long) bytes[1] & 0xFFL) << 48
                | ((long) bytes[2] & 0xFFL) << 40
                | ((long) bytes[3] & 0xFFL) << 32
                | ((long) bytes[4] & 0xFFL) << 24
                | ((long) bytes[5] & 0xFFL) << 16
                | ((long) bytes[6] & 0xFFL) << 8
                | bytes[7] & 0xFFL;
    }
}
