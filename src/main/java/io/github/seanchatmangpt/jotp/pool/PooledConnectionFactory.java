package io.github.seanchatmangpt.jotp.pool;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Factory for creating and managing pooled connections.
 *
 * <p><strong>Connection Factory Pattern:</strong> Provides a centralized factory for acquiring
 * connections from a managed pool. Instead of callers creating connections directly, they request
 * them from this factory, which handles pooling, validation, and lifecycle management.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li><strong>Lazy Connection Creation:</strong> Connections are created on-demand up to the
 *       minimum pool size, then up to the maximum as needed.
 *   <li><strong>Connection Validation:</strong> Optional validator ensures connections are healthy
 *       before use.
 *   <li><strong>Try-With-Resources:</strong> Implements AutoCloseable for safe resource management.
 *   <li><strong>Async Borrowing:</strong> CompletableFuture-based borrowing for non-blocking
 *       operation.
 *   <li><strong>Lifecycle Management:</strong> Automatic connection eviction and graceful shutdown.
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Create a pooled connection factory
 * try (var factory = PooledConnectionFactory.<DbConnection>builder()
 *     .poolName("app-db-pool")
 *     .connectionFactory(() -> new DbConnection("jdbc:postgresql://localhost:5432/mydb"))
 *     .connectionCloser(DbConnection::close)
 *     .validator(DbConnection::isAlive)
 *     .minConnections(5)
 *     .maxConnections(20)
 *     .idleTimeout(Duration.ofMinutes(5))
 *     .requestTimeout(Duration.ofSeconds(2))
 *     .build()) {
 *
 *   // Synchronously borrow a connection
 *   Optional<DbConnection> conn = factory.borrowSync();
 *   if (conn.isPresent()) {
 *     try {
 *       // Use the connection
 *       List<User> users = conn.get().query("SELECT * FROM users");
 *     } finally {
 *       // Return to pool
 *       factory.release(conn.get());
 *     }
 *   }
 *
 *   // Asynchronously borrow a connection
 *   CompletableFuture<DbConnection> connAsync = factory.borrowAsync();
 *   connAsync.thenAccept(conn2 -> {
 *     try {
 *       // Use conn2
 *     } finally {
 *       factory.release(conn2);
 *     }
 *   });
 *
 *   // Get pool statistics
 *   var stats = factory.stats();
 *   System.out.println("Pool utilization: " + stats.utilizationPercentage() + "%");
 * }
 * }</pre>
 *
 * @param <C> connection type
 */
public final class PooledConnectionFactory<C> implements AutoCloseable {

  private final ConnectionPool<C> pool;
  private final Duration requestTimeout;

  private PooledConnectionFactory(ConnectionPool<C> pool, Duration requestTimeout) {
    this.pool = pool;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Create a builder for PooledConnectionFactory.
   *
   * @return a PooledConnectionFactoryBuilder
   * @param <C> connection type
   */
  public static <C> PooledConnectionFactoryBuilder<C> builder() {
    return new PooledConnectionFactoryBuilder<>();
  }

  /**
   * Builder for fluent configuration of PooledConnectionFactory.
   *
   * @param <C> connection type
   */
  public static final class PooledConnectionFactoryBuilder<C> {
    private String poolName = "unnamed-pool";
    private Supplier<C> connectionFactory;
    private Consumer<C> connectionCloser = c -> {};
    private Predicate<C> validator = c -> true;
    private int minConnections = 5;
    private int maxConnections = 20;
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration requestTimeout = Duration.ofSeconds(5);

    /**
     * Set the pool name.
     *
     * @param name the pool name
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> poolName(String name) {
      this.poolName = name;
      return this;
    }

    /**
     * Set the connection factory.
     *
     * @param factory the supplier that creates new connections
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> connectionFactory(Supplier<C> factory) {
      this.connectionFactory = factory;
      return this;
    }

    /**
     * Set the connection closer.
     *
     * @param closer the consumer that closes connections
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> connectionCloser(Consumer<C> closer) {
      this.connectionCloser = closer;
      return this;
    }

    /**
     * Set the connection validator.
     *
     * @param validator the predicate that validates connections
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> validator(Predicate<C> validator) {
      this.validator = validator;
      return this;
    }

    /**
     * Set the minimum number of connections.
     *
     * @param count minimum connections
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> minConnections(int count) {
      this.minConnections = count;
      return this;
    }

    /**
     * Set the maximum number of connections.
     *
     * @param count maximum connections
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> maxConnections(int count) {
      this.maxConnections = count;
      return this;
    }

    /**
     * Set the idle timeout.
     *
     * @param timeout idle timeout duration
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> idleTimeout(Duration timeout) {
      this.idleTimeout = timeout;
      return this;
    }

    /**
     * Set the request timeout (when borrowing).
     *
     * @param timeout request timeout duration
     * @return this builder
     */
    public PooledConnectionFactoryBuilder<C> requestTimeout(Duration timeout) {
      this.requestTimeout = timeout;
      return this;
    }

    /**
     * Build and return a new PooledConnectionFactory.
     *
     * @return a new, initialized pooled connection factory
     */
    public PooledConnectionFactory<C> build() {
      if (connectionFactory == null) {
        throw new IllegalArgumentException("connectionFactory is required");
      }

      var pool =
          ConnectionPool.<C>builder(poolName)
              .connectionFactory(connectionFactory)
              .connectionCloser(connectionCloser)
              .validator(validator)
              .minConnections(minConnections)
              .maxConnections(maxConnections)
              .idleTimeout(idleTimeout)
              .build();

      return new PooledConnectionFactory<>(pool, requestTimeout);
    }
  }

  /**
   * Synchronously borrow a connection from the pool.
   *
   * <p>Blocks for up to the configured request timeout waiting for a connection.
   *
   * @return an Optional containing a connection if available, empty if timeout occurred
   */
  public Optional<C> borrowSync() {
    return pool.borrow(requestTimeout);
  }

  /**
   * Synchronously borrow a connection with a custom timeout.
   *
   * @param timeout maximum time to wait for a connection
   * @return an Optional containing a connection if available, empty if timeout occurred
   */
  public Optional<C> borrowSync(Duration timeout) {
    return pool.borrow(timeout);
  }

  /**
   * Asynchronously borrow a connection from the pool.
   *
   * <p>Returns a CompletableFuture that completes with a connection or fails if the pool is
   * shut down or the request times out.
   *
   * @return a CompletableFuture that completes with a connection
   */
  public CompletableFuture<C> borrowAsync() {
    return CompletableFuture.supplyAsync(
        () -> {
          Optional<C> conn = pool.borrow(requestTimeout);
          if (conn.isEmpty()) {
            throw new IllegalStateException("Failed to borrow connection within timeout");
          }
          return conn.get();
        });
  }

  /**
   * Asynchronously borrow a connection with a custom timeout.
   *
   * @param timeout maximum time to wait for a connection
   * @return a CompletableFuture that completes with a connection
   */
  public CompletableFuture<C> borrowAsync(Duration timeout) {
    return CompletableFuture.supplyAsync(
        () -> {
          Optional<C> conn = pool.borrow(timeout);
          if (conn.isEmpty()) {
            throw new IllegalStateException("Failed to borrow connection within timeout");
          }
          return conn.get();
        });
  }

  /**
   * Return a connection to the pool.
   *
   * @param connection the connection to return
   */
  public void release(C connection) {
    pool.release(connection);
  }

  /**
   * Get current pool statistics.
   *
   * @return a ConnectionPoolStats object with current metrics
   */
  public ConnectionPool.ConnectionPoolStats stats() {
    return pool.stats();
  }

  /**
   * Check if the factory (and underlying pool) is still running.
   *
   * @return true if the pool is accepting new borrow requests
   */
  public boolean isRunning() {
    return pool.isRunning();
  }

  /**
   * Gracefully shut down the factory and close all connections.
   *
   * @throws Exception if an error occurs during shutdown
   */
  @Override
  public void close() throws Exception {
    pool.shutdown();
  }
}
