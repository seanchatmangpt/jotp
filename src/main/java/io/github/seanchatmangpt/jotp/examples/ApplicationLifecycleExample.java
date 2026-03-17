package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.ApplicationCallback;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ApplicationSpec;
import io.github.seanchatmangpt.jotp.ShutdownManager;
import java.time.Duration;

/**
 * Example: Complete JOTP Application Lifecycle — startup, dependency ordering, and graceful
 * shutdown.
 *
 * <p>Demonstrates the pattern for bootstrapping a multi-tier JOTP system:
 * <ol>
 *   <li>Define application specs with dependencies
 *   <li>Load specs into ApplicationController
 *   <li>Create an ApplicationController instance for lifecycle management
 *   <li>Register applications with dependency ordering
 *   <li>Start all applications in dependency order
 *   <li>Install signal handlers for graceful shutdown
 * </ol>
 *
 * <p><strong>System architecture (from this example):</strong>
 *
 * <pre>
 *   DatabaseApplication
 *        ↓
 *   CacheApplication (depends on Database)
 *        ↓
 *   APIApplication (depends on Database, Cache)
 * </pre>
 *
 * <p>Startup order: Database → Cache → API
 * Shutdown order: API → Cache → Database
 *
 * @see ApplicationController
 * @see ApplicationSpec
 * @see ShutdownManager
 * @since 1.0
 * @author JOTP Contributors
 */
public final class ApplicationLifecycleExample {

  /**
   * Example: minimal database application.
   *
   * <p>In a real system, this would initialize a connection pool, register ProcRef instances,
   * etc.
   */
  private static ApplicationSpec databaseSpec() {
    var callback = (ApplicationCallback<String>) (startType, args) -> {
      System.out.println("[Database] Starting database application");
      // In production: initialize RocksDB, connection pool, etc.
      return "db-state";
    };

    return ApplicationSpec.builder("database")
        .description("Database application")
        .vsn("1.0")
        .mod(callback)
        .build();
  }

  /**
   * Example: cache application that depends on database.
   *
   * <p>The cache starts only after the database is running.
   */
  private static ApplicationSpec cacheSpec() {
    var callback = (ApplicationCallback<String>) (startType, args) -> {
      System.out.println("[Cache] Starting cache application (database is running)");
      // In production: initialize cache supervisor, spawn cache workers, etc.
      return "cache-state";
    };

    return ApplicationSpec.builder("cache")
        .description("Cache application")
        .vsn("1.0")
        .applications("database") // Declares dependency on database
        .mod(callback)
        .build();
  }

  /**
   * Example: API application that depends on both database and cache.
   *
   * <p>The API starts only after both database and cache are running.
   */
  private static ApplicationSpec apiSpec() {
    var callback = (ApplicationCallback<String>) (startType, args) -> {
      System.out.println("[API] Starting API application (database and cache are running)");
      // In production: start HTTP server, register request handlers, etc.
      return "api-state";
    };

    return ApplicationSpec.builder("api")
        .description("API application")
        .vsn("1.0")
        .applications("database", "cache") // Declares dependencies
        .mod(callback)
        .build();
  }

  /**
   * Main entry point demonstrating the complete application lifecycle.
   *
   * @param args command-line arguments (unused)
   * @throws Exception if startup or shutdown fails
   */
  public static void main(String[] args) throws Exception {
    System.out.println("=".repeat(70));
    System.out.println("JOTP Application Lifecycle Example");
    System.out.println("=".repeat(70));

    // Step 1: Reset global ApplicationController state (for clean start)
    ApplicationController.reset();

    // Step 2: Load application specs into global registry
    System.out.println("\n1. Loading application specs...");
    ApplicationController.load(databaseSpec());
    ApplicationController.load(cacheSpec());
    ApplicationController.load(apiSpec());

    // Step 3: Create instance-based controller for this application system
    System.out.println("2. Creating ApplicationController instance...");
    var controller = new ApplicationController("my-system");

    // Step 4: Register applications with the controller
    // Note: registration order doesn't matter; dependencyOrder() computes startup order
    System.out.println("3. Registering applications...");
    controller.register("api").register("cache").register("database");

    // Step 5: Compute dependency order (topologically sorted)
    System.out.println("4. Computing dependency order...");
    var startOrder = controller.dependencyOrder();
    System.out.println("   Startup order: " + startOrder);

    // Step 6: Start all applications in dependency order
    System.out.println("5. Starting all applications...");
    try {
      controller.startAll();
      System.out.println("   ✓ All applications started");
    } catch (Exception e) {
      System.err.println("   ✗ Startup failed: " + e.getMessage());
      return;
    }

    // Step 7: Check health status
    System.out.println("6. Checking system health...");
    var health = controller.health();
    System.out.println("   Overall health: " + health);
    for (var status : controller.allStatuses()) {
      System.out.println("     - " + status.name() + ": " + status.health());
    }

    // Step 8: Install graceful shutdown handlers
    System.out.println("7. Installing graceful shutdown handlers...");
    var shutdownManager = new ShutdownManager(controller, Duration.ofSeconds(30));
    shutdownManager.installAll();
    System.out.println("   ✓ Signal handlers and JVM shutdown hook installed");

    // Step 9: Run the application
    System.out.println("\n8. Application running...");
    System.out.println("   (Press Ctrl+C or send SIGTERM to gracefully shutdown)");
    System.out.println();

    // Simulate application work
    try {
      Thread.sleep(Long.MAX_VALUE);
    } catch (InterruptedException e) {
      // Expected when interrupted by signal handler
      Thread.currentThread().interrupt();
    }

    // Note: Actual shutdown is handled by ShutdownManager's signal handlers
    // and JVM shutdown hook, so this line may not be reached in normal operation.
  }
}
