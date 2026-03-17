package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Distributed process registry backed by PostgreSQL.
 *
 * <p>Provides ACID-compliant cluster-wide process registration with:
 *
 * <ul>
 *   <li><strong>Strong Consistency:</strong> Transactions ensure no lost updates
 *   <li><strong>Complex Queries:</strong> SQL for process analytics and reports
 *   <li><strong>Audit Trail:</strong> Full history of registrations/deregistrations
 *   <li><strong>Multi-Region Failover:</strong> PostgreSQL streaming replication
 * </ul>
 *
 * <p><strong>Schema:</strong>
 *
 * <pre>{@code
 * CREATE TABLE jotp_global_registry (
 *   name VARCHAR(255) PRIMARY KEY,
 *   node_name VARCHAR(255) NOT NULL,
 *   process_id BIGINT NOT NULL,
 *   sequence_number BIGINT NOT NULL,
 *   registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE TABLE jotp_registry_audit (
 *   id BIGSERIAL PRIMARY KEY,
 *   action VARCHAR(50) NOT NULL,
 *   name VARCHAR(255) NOT NULL,
 *   node_name VARCHAR(255),
 *   timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE INDEX ON jotp_global_registry(node_name);
 * CREATE INDEX ON jotp_registry_audit(timestamp);
 * }</pre>
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * Node 1: store("svc-1")  ──┐
 * Node 2: lookup("svc-1") ──┼──→ PostgreSQL Primary
 * Node 3: cleanup()        ──┤        ↓
 *                            │    ┌───┴────┐
 *                            │    ↓        ↓
 *                            │  Replica  Replica
 *                            │  (DC1)    (DC2)
 * All nodes query PRIMARY for consistency
 * </pre>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * PostgresGlobalRegistryBackend registry = new PostgresGlobalRegistryBackend(
 *   "localhost", 5432, "jotp_registry");
 * registry.initializeTables();
 * registry.watch(event -> handleRegistryChange(event));
 *
 * GlobalProcRef ref = new GlobalProcRef("node-1", 42, 100);
 * registry.store("my-service", ref);
 *
 * Optional<GlobalProcRef> found = registry.lookup("my-service");
 * }</pre>
 *
 * @see GlobalRegistryBackend
 */
public class PostgresGlobalRegistryBackend implements GlobalRegistryBackend {

    private static final String REGISTRY_TABLE = "jotp_global_registry";
    private static final String AUDIT_TABLE = "jotp_registry_audit";

    private final DataSource dataSource;
    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> watchers =
            new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Create a PostgreSQL registry backend.
     *
     * @param host PostgreSQL host
     * @param port PostgreSQL port
     * @param database database name
     */
    public PostgresGlobalRegistryBackend(String host, int port, String database) {
        this(host, port, database, "jotp", "");
    }

    /**
     * Create a PostgreSQL registry backend with credentials.
     *
     * @param host PostgreSQL host
     * @param port PostgreSQL port
     * @param database database name
     * @param user database user
     * @param password database password
     */
    public PostgresGlobalRegistryBackend(
            String host, int port, String database, String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[] {host});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(database);
        source.setUser(user);
        if (password != null && !password.isEmpty()) {
            source.setPassword(password);
        }

        this.dataSource = source;
    }

    /**
     * Initialize database tables. Call once on startup.
     *
     * @throws RegistryError if table creation fails
     */
    public void initializeTables() {
        checkClosed();

        try (Connection conn = dataSource.getConnection()) {
            // Create registry table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS "
                                + REGISTRY_TABLE
                                + " ("
                                + "  name VARCHAR(255) PRIMARY KEY,"
                                + "  node_name VARCHAR(255) NOT NULL,"
                                + "  process_id BIGINT NOT NULL,"
                                + "  sequence_number BIGINT NOT NULL,"
                                + "  registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                                + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                                + ")");
            }

            // Create audit table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS "
                                + AUDIT_TABLE
                                + " ("
                                + "  id BIGSERIAL PRIMARY KEY,"
                                + "  action VARCHAR(50) NOT NULL,"
                                + "  name VARCHAR(255) NOT NULL,"
                                + "  node_name VARCHAR(255),"
                                + "  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                                + ")");
            }

            // Create indexes
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_registry_node ON " + REGISTRY_TABLE
                        + " (node_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON " + AUDIT_TABLE
                        + " (timestamp)");
            }
        } catch (SQLException e) {
            throw new RegistryError(
                    RegistryError.Type.STORAGE_FAILED, "Failed to initialize tables", e);
        }
    }

    @Override
    public Result<Void, RegistryError> store(String name, GlobalProcRef ref) {
        checkClosed();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql =
                        "INSERT INTO "
                                + REGISTRY_TABLE
                                + " (name, node_name, process_id, sequence_number) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (name) DO UPDATE SET "
                                + "  node_name = ?, process_id = ?, sequence_number = ?, updated_at = CURRENT_TIMESTAMP";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, name);
                    stmt.setString(2, ref.nodeName());
                    stmt.setLong(3, ref.processId());
                    stmt.setLong(4, ref.sequenceNumber());
                    stmt.setString(5, ref.nodeName());
                    stmt.setLong(6, ref.processId());
                    stmt.setLong(7, ref.sequenceNumber());
                    stmt.executeUpdate();
                }

                // Audit log
                auditAction(conn, "STORED", name, ref.nodeName());
                conn.commit();

                // Notify watchers
                notifyWatchers(new RegistryEvent.Registered(name, ref));

                return Result.ok(null);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            return Result.err(new RegistryError(
                    RegistryError.Type.STORAGE_FAILED, "Failed to store: " + name, e));
        }
    }

    @Override
    public Optional<GlobalProcRef> lookup(String name) {
        checkClosed();
        try (Connection conn = dataSource.getConnection()) {
            String sql =
                    "SELECT node_name, process_id, sequence_number FROM "
                            + REGISTRY_TABLE
                            + " WHERE name = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(
                                new GlobalProcRef(
                                        rs.getString("node_name"),
                                        rs.getLong("process_id"),
                                        rs.getLong("sequence_number")));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RegistryError(
                    RegistryError.Type.LOOKUP_FAILED, "Failed to lookup: " + name, e);
        }
    }

    @Override
    public Result<Void, RegistryError> remove(String name) {
        checkClosed();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = "DELETE FROM " + REGISTRY_TABLE + " WHERE name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, name);
                    stmt.executeUpdate();
                }

                // Audit log
                auditAction(conn, "REMOVED", name, null);
                conn.commit();

                // Notify watchers
                notifyWatchers(new RegistryEvent.Unregistered(name));

                return Result.ok(null);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            return Result.err(new RegistryError(
                    RegistryError.Type.STORAGE_FAILED, "Failed to remove: " + name, e));
        }
    }

    @Override
    public Map<String, GlobalProcRef> listAll() {
        checkClosed();
        try (Connection conn = dataSource.getConnection()) {
            String sql =
                    "SELECT name, node_name, process_id, sequence_number FROM "
                            + REGISTRY_TABLE;

            Map<String, GlobalProcRef> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    result.put(
                            rs.getString("name"),
                            new GlobalProcRef(
                                    rs.getString("node_name"),
                                    rs.getLong("process_id"),
                                    rs.getLong("sequence_number")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RegistryError(
                    RegistryError.Type.LOOKUP_FAILED, "Failed to list all entries", e);
        }
    }

    @Override
    public boolean compareAndSwap(String name, Optional<GlobalProcRef> expected, GlobalProcRef newValue) {
        checkClosed();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (expected.isEmpty()) {
                    // Only insert if not exists
                    String sql =
                            "INSERT INTO "
                                    + REGISTRY_TABLE
                                    + " (name, node_name, process_id, sequence_number) VALUES (?, ?, ?, ?) "
                                    + "ON CONFLICT (name) DO NOTHING";

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, name);
                        stmt.setString(2, newValue.nodeName());
                        stmt.setLong(3, newValue.processId());
                        stmt.setLong(4, newValue.sequenceNumber());
                        int affected = stmt.executeUpdate();
                        conn.commit();
                        return affected > 0;
                    }
                } else {
                    // Compare and swap via transaction
                    GlobalProcRef exp = expected.get();
                    String checkSql =
                            "SELECT 1 FROM "
                                    + REGISTRY_TABLE
                                    + " WHERE name = ? AND node_name = ? AND process_id = ? AND sequence_number = ?";

                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                        checkStmt.setString(1, name);
                        checkStmt.setString(2, exp.nodeName());
                        checkStmt.setLong(3, exp.processId());
                        checkStmt.setLong(4, exp.sequenceNumber());

                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                // Expected value matches, perform update
                                String updateSql =
                                        "UPDATE "
                                                + REGISTRY_TABLE
                                                + " SET node_name = ?, process_id = ?, sequence_number = ?, updated_at = CURRENT_TIMESTAMP "
                                                + "WHERE name = ?";

                                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                                    updateStmt.setString(1, newValue.nodeName());
                                    updateStmt.setLong(2, newValue.processId());
                                    updateStmt.setLong(3, newValue.sequenceNumber());
                                    updateStmt.setString(4, name);
                                    int affected = updateStmt.executeUpdate();
                                    conn.commit();
                                    return affected > 0;
                                }
                            } else {
                                conn.commit();
                                return false;
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new RegistryError(
                    RegistryError.Type.STORAGE_FAILED,
                    "Failed to compare-and-swap: " + name,
                    e);
        }
    }

    @Override
    public void watch(Consumer<RegistryEvent> listener) {
        checkClosed();
        watchers.add(listener);
    }

    @Override
    public void cleanupNode(String nodeName) {
        checkClosed();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = "SELECT name FROM " + REGISTRY_TABLE + " WHERE node_name = ?";

                List<String> names = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, nodeName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            names.add(rs.getString("name"));
                        }
                    }
                }

                // Delete all entries for this node
                String deleteSql = "DELETE FROM " + REGISTRY_TABLE + " WHERE node_name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                    stmt.setString(1, nodeName);
                    stmt.executeUpdate();
                }

                // Audit log
                auditAction(conn, "CLEANUP", "*", nodeName);
                conn.commit();

                // Notify watchers
                names.forEach(name -> notifyWatchers(new RegistryEvent.Unregistered(name)));
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new RegistryError(
                    RegistryError.Type.CLEANUP_FAILED, "Failed to cleanup node: " + nodeName, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        // Connection pool managed by DataSource
    }

    private void auditAction(Connection conn, String action, String name, String nodeName)
            throws SQLException {
        String sql =
                "INSERT INTO " + AUDIT_TABLE + " (action, name, node_name) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, action);
            stmt.setString(2, name);
            stmt.setString(3, nodeName);
            stmt.executeUpdate();
        }
    }

    private void notifyWatchers(RegistryEvent event) {
        watchers.forEach(w -> {
            try {
                w.accept(event);
            } catch (Exception e) {
                // Ignore watcher errors
            }
        });
    }

    private void checkClosed() {
        if (closed) {
            throw new RegistryError(RegistryError.Type.BACKEND_CLOSED, "Backend is closed", null);
        }
    }
}
