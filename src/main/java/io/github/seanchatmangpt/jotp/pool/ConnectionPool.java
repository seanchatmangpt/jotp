package io.github.seanchatmangpt.jotp.pool;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Generic connection pool with virtual thread support.
 *
 * <p><strong>Connection Pooling Pattern:</strong> Manages a fixed-size pool of reusable
 * connections to reduce the overhead of creating new connections for each request. Instead of
 * creating and destroying connections repeatedly, connections are borrowed from the pool, used,
 * and returned.
 *
 * <p><strong>Virtual Thread Integration:</strong> This pool is optimized for virtual threads
 * (Java 21+), which are extremely lightweight (1KB each). The pool maintains a configurable
 * minimum size and can grow up to a maximum if needed, supporting thousands of concurrent
 * connections with minimal overhead.
 *
 * <p><strong>Connection Lifecycle:</strong>
 *
 * <ul>
 *   <li><strong>Creation:</strong> Connections are created lazily when first borrowed, up to the
 *       minimum pool size.
 *   <li><strong>Validation:</strong> Optional validation can be performed before returning a
 *       connection to ensure it is still healthy.
 *   <li><strong>Eviction:</strong> Idle connections beyond the minimum size are automatically
 *       evicted after a configurable timeout.
 *   <li><strong>Cleanup:</strong> All connections are properly closed when the pool shuts down.
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Create a connection pool
 * var pool = ConnectionPool.<MyConnection>builder("db-pool")
 *     .minConnections(5)
 *     .maxConnections(20)
 *     .connectionFactory(() -> new MyConnection("jdbc:db://localhost"))
 *     .idleTimeout(Duration.ofMinutes(5))
 *     .build();
 *
 * // Borrow a connection
 * Optional<MyConnection> conn = pool.borrow(Duration.ofSeconds(2));
 *
 * if (conn.isPresent()) {
 *     try {
 *         // Use the connection
 *         conn.get().execute("SELECT * FROM users");
 *     } finally {
 *         // Return the connection to the pool
 *         pool.release(conn.get());
 *     }
 * }
 *
 * // Get pool statistics
 * PoolStats stats = pool.stats();
 * System.out.println(stats);
 *
 * // Shutdown the pool
 * pool.shutdown();
 * }</pre>
 *
 * @param <C> connection type
 */
public final class ConnectionPool<C> {

  /** Connection wrapper storing the actual connection and metadata. */
  private static final class PooledConnection<C> {
    private final C connection;
    private volatile Instant lastUsed;
    private volatile boolean inUse;

    PooledConnection(C connection) {
      this.connection = connection;
      this.lastUsed = Instant.now();
      this.inUse = false;
    }
  }

  private final String name;
  private final Supplier<C> connectionFactory;
  private final java.util.function.Consumer<C> connectionCloser;
  private final java.util.function.Predicate<C> validator;
  private final int minConnections;
  private final int maxConnections;
  private final Duration idleTimeout;

  private final LinkedTransferQueue<PooledConnection<C>> available;
  private final List<PooledConnection<C>> allConnections;
  private volatile boolean running = true;
  private final Object shutdownLock = new Object();

  private ConnectionPool(
      String name,
      Supplier<C> connectionFactory,
      java.util.function.Consumer<C> connectionCloser,
      java.util.function.Predicate<C> validator,
      int minConnections,
      int maxConnections,
      Duration idleTimeout) {
    this.name = name;
    this.connectionFactory = connectionFactory;
    this.connectionCloser = connectionCloser;
    this.validator = validator;
    this.minConnections = minConnections;
    this.maxConnections = maxConnections;
    this.idleTimeout = idleTimeout;
    this.available = new LinkedTransferQueue<>();
    this.allConnections = new ArrayList<>();

    // Pre-populate the pool with minimum connections
    for (int i = 0; i < minConnections; i++) {
      try {
        C conn = connectionFactory.get();
        PooledConnection<C> pooled = new PooledConnection<>(conn);
        allConnections.add(pooled);
        available.add(pooled);
      } catch (Exception e) {
        System.err.println("Failed to create initial connection: " + e.getMessage());
      }
    }

    // Start idle connection eviction thread
    startIdleConnectionEviction();
  }

  /**
   * Create a builder for constructing a ConnectionPool.
   *
   * @param name pool name (for identification)
   * @return a ConnectionPoolBuilder
   * @param <C> connection type
   */
  public static <C> ConnectionPoolBuilder<C> builder(String name) {
    return new ConnectionPoolBuilder<>(name);
  }

  /**
   * Builder for fluent configuration of ConnectionPool.
   *
   * @param <C> connection type
   */
  public static final class ConnectionPoolBuilder<C> {
    private final String name;
    private Supplier<C> connectionFactory;
    private java.util.function.Consumer<C> connectionCloser;
    private java.util.function.Predicate<C> validator;
    private int minConnections = 5;
    private int maxConnections = 20;
    private Duration idleTimeout = Duration.ofMinutes(5);

    private ConnectionPoolBuilder(String name) {
      this.name = name;
      this.connectionCloser = c -> {};
      this.validator = c -> true;
    }

    /**
     * Set the connection factory.
     *
     * @param factory the supplier that creates new connections
     * @return this builder
     */
    public ConnectionPoolBuilder<C> connectionFactory(Supplier<C> factory) {
      this.connectionFactory = factory;
      return this;
    }

    /**
     * Set the connection closer (for cleanup).
     *
     * @param closer the consumer that closes a connection
     * @return this builder
     */
    public ConnectionPoolBuilder<C> connectionCloser(java.util.function.Consumer<C> closer) {
      this.connectionCloser = closer;
      return this;
    }

    /**
     * Set the connection validator (for health checks).
     *
     * @param validator the predicate that checks if a connection is still valid
     * @return this builder
     */
    public ConnectionPoolBuilder<C> validator(java.util.function.Predicate<C> validator) {
      this.validator = validator;
      return this;
    }

    /**
     * Set the minimum number of connections to maintain.
     *
     * @param count minimum connections
     * @return this builder
     */
    public ConnectionPoolBuilder<C> minConnections(int count) {
      this.minConnections = count;
      return this;
    }

    /**
     * Set the maximum number of connections.
     *
     * @param count maximum connections
     * @return this builder
     */
    public ConnectionPoolBuilder<C> maxConnections(int count) {
      this.maxConnections = count;
      return this;
    }

    /**
     * Set the idle timeout (connections unused for this duration are evicted).
     *
     * @param timeout idle timeout duration
     * @return this builder
     */
    public ConnectionPoolBuilder<C> idleTimeout(Duration timeout) {
      this.idleTimeout = timeout;
      return this;
    }

    /**
     * Build and return a new ConnectionPool.
     *
     * @return a new, initialized connection pool
     */
    public ConnectionPool<C> build() {
      if (connectionFactory == null) {
        throw new IllegalArgumentException("connectionFactory is required");
      }
      return new ConnectionPool<>(
          name,
          connectionFactory,
          connectionCloser,
          validator,
          minConnections,
          maxConnections,
          idleTimeout);
    }
  }

  /**
   * Borrow a connection from the pool.
   *
   * <p>If a connection is available, it is returned immediately. If no connections are available
   * but the pool has not reached its maximum size, a new connection is created.
   *
   * @param timeout maximum time to wait for a connection
   * @return an Optional containing the connection if available, empty otherwise
   */
  public Optional<C> borrow(Duration timeout) {
    synchronized (shutdownLock) {
      if (!running) {
        return Optional.empty();
      }
    }

    try {
      // Try to get an available connection
      PooledConnection<C> pooled = available.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);

      if (pooled != null) {
        // Validate the connection before returning
        if (validator.test(pooled.connection)) {
          pooled.inUse = true;
          pooled.lastUsed = Instant.now();
          return Optional.of(pooled.connection);
        } else {
          // Connection is invalid, close it and try again
          try {
            connectionCloser.accept(pooled.connection);
          } catch (Exception e) {
            System.err.println("Error closing invalid connection: " + e.getMessage());
          }
          allConnections.remove(pooled);
          return borrow(timeout);
        }
      }

      // Try to create a new connection if under max limit
      synchronized (allConnections) {
        if (allConnections.size() < maxConnections) {
          try {
            C conn = connectionFactory.get();
            PooledConnection<C> newPooled = new PooledConnection<>(conn);
            allConnections.add(newPooled);
            newPooled.inUse = true;
            newPooled.lastUsed = Instant.now();
            return Optional.of(conn);
          } catch (Exception e) {
            System.err.println("Failed to create new connection: " + e.getMessage());
            return Optional.empty();
          }
        }
      }

      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * Return a connection to the pool.
   *
   * @param connection the connection to return
   */
  public void release(C connection) {
    synchronized (allConnections) {
      for (PooledConnection<C> pooled : allConnections) {
        if (pooled.connection == connection) {
          pooled.inUse = false;
          pooled.lastUsed = Instant.now();
          if (running) {
            available.add(pooled);
          }
          return;
        }
      }
    }
  }

  /**
   * Get current pool statistics.
   *
   * @return a ConnectionPoolStats object with current metrics
   */
  public ConnectionPoolStats stats() {
    synchronized (allConnections) {
      int inUse = (int) allConnections.stream().filter(c -> c.inUse).count();
      int available = this.available.size();
      return new ConnectionPoolStats(name, allConnections.size(), inUse, available);
    }
  }

  /**
   * Gracefully shut down the pool and close all connections.
   *
   * @throws InterruptedException if interrupted while waiting for shutdown
   */
  public void shutdown() throws InterruptedException {
    synchronized (shutdownLock) {
      running = false;
    }

    synchronized (allConnections) {
      for (PooledConnection<C> pooled : allConnections) {
        try {
          connectionCloser.accept(pooled.connection);
        } catch (Exception e) {
          System.err.println("Error closing connection: " + e.getMessage());
        }
      }
      allConnections.clear();
    }
    available.clear();
  }

  /**
   * Check if the pool is still running.
   *
   * @return true if the pool is accepting new borrow requests
   */
  public boolean isRunning() {
    synchronized (shutdownLock) {
      return running;
    }
  }

  /** Start a background thread to evict idle connections. */
  private void startIdleConnectionEviction() {
    Thread.ofVirtual()
        .name("connection-pool-eviction-" + name)
        .start(
            () -> {
              while (running) {
                try {
                  Thread.sleep(idleTimeout.toMillis());
                  Instant now = Instant.now();

                  synchronized (allConnections) {
                    List<PooledConnection<C>> toRemove = new ArrayList<>();
                    for (PooledConnection<C> pooled : allConnections) {
                      if (!pooled.inUse
                          && now.minus(idleTimeout).isAfter(pooled.lastUsed)
                          && allConnections.size() > minConnections) {
                        toRemove.add(pooled);
                      }
                    }

                    for (PooledConnection<C> pooled : toRemove) {
                      try {
                        connectionCloser.accept(pooled.connection);
                      } catch (Exception e) {
                        System.err.println("Error evicting idle connection: " + e.getMessage());
                      }
                      allConnections.remove(pooled);
                      available.remove(pooled);
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            });
  }

  /**
   * Statistics for a connection pool.
   *
   * @param name pool name
   * @param totalConnections total number of connections (in use + available)
   * @param inUseConnections number of connections currently in use
   * @param availableConnections number of connections available for borrowing
   */
  public record ConnectionPoolStats(
      String name, int totalConnections, int inUseConnections, int availableConnections) {

    /**
     * Get the utilization percentage.
     *
     * @return percentage of connections in use (0-100)
     */
    public double utilizationPercentage() {
      return totalConnections > 0 ? (100.0 * inUseConnections / totalConnections) : 0;
    }

    @Override
    public String toString() {
      return String.format(
          "ConnectionPoolStats{name=%s, total=%d, inUse=%d, available=%d, utilization=%.0f%%}",
          name, totalConnections, inUseConnections, availableConnections, utilizationPercentage());
    }
  }
}
