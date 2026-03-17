package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * Manages graceful shutdown of JOTP applications via OS signals and JVM shutdown hooks.
 *
 * <p>Coordinates system shutdown across SIGTERM, SIGINT (Ctrl+C), and JVM shutdown. Each signal
 * triggers a graceful shutdown sequence: stop all applications, flush state, close resources.
 *
 * <p><strong>Erlang equivalence:</strong> In Erlang/OTP, the node controller handles {@code
 * init:stop/0} and system signals. ShutdownManager provides the same contract for Java:
 *
 * <ul>
 *   <li>{@code SIGTERM} (termination signal) → graceful shutdown
 *   <li>{@code SIGINT} (interrupt, Ctrl+C) → graceful shutdown
 *   <li>JVM shutdown hook → graceful shutdown
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var controller = ApplicationController.defaultInstance();
 * var shutdownManager = new ShutdownManager(controller, Duration.ofSeconds(30));
 * shutdownManager.installSignalHandlers();  // Install SIGTERM/SIGINT handlers
 * shutdownManager.installJvmShutdownHook();  // Install JVM shutdown hook
 * }</pre>
 *
 * <p><strong>Shutdown sequence:</strong>
 *
 * <ol>
 *   <li>Signal received (SIGTERM, SIGINT, or JVM shutdown)
 *   <li>ShutdownManager invokes {@code controller.stopAll(timeout)}
 *   <li>Each application is stopped in reverse dependency order
 *   <li>RocksDB state is flushed to disk
 *   <li>Virtual threads are joined
 *   <li>Final statistics are logged
 * </ol>
 *
 * @see ApplicationController
 * @see ApplicationHealth
 * @since 1.0
 * @author JOTP Contributors
 */
public final class ShutdownManager {

  private static final Logger log = Logger.getLogger(ShutdownManager.class.getName());

  private final ApplicationController controller;
  private final Duration shutdownTimeout;
  private volatile boolean shuttingDown = false;

  /**
   * Create a ShutdownManager for an application controller.
   *
   * @param controller the ApplicationController to manage shutdown
   * @param shutdownTimeout the maximum time to allow for graceful shutdown
   */
  public ShutdownManager(ApplicationController controller, Duration shutdownTimeout) {
    this.controller =
        java.util.Objects.requireNonNull(controller, "controller must not be null");
    this.shutdownTimeout =
        java.util.Objects.requireNonNull(shutdownTimeout, "shutdownTimeout must not be null");
  }

  /**
   * Install OS signal handlers for SIGTERM and SIGINT.
   *
   * <p>Registers handlers for:
   * <ul>
   *   <li>SIGTERM (signal 15) — standard termination signal
   *   <li>SIGINT (signal 2) — interrupt signal (Ctrl+C)
   * </ul>
   *
   * <p>Note: On Unix-like systems (Linux, macOS, BSD), these signals are supported. On Windows,
   * only SIGINT is reliably supported. The implementation uses best-effort registration.
   */
  public void installSignalHandlers() {
    try {
      // SIGTERM handler
      sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {
        log.info("SIGTERM received — initiating graceful shutdown");
        performShutdown();
      });

      // SIGINT handler (Ctrl+C)
      sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> {
        log.info("SIGINT received — initiating graceful shutdown");
        performShutdown();
      });

      log.fine("OS signal handlers installed (SIGTERM, SIGINT)");
    } catch (IllegalArgumentException | sun.misc.SignalException e) {
      // Signals may not be available on all platforms (e.g., Windows)
      log.warning("Could not install signal handlers: " + e.getMessage());
    }
  }

  /**
   * Install a JVM shutdown hook for graceful termination.
   *
   * <p>The hook runs when the JVM is shutting down (either normally or via {@code System.exit()}).
   * This ensures applications are properly closed even if the shutdown is triggered by external
   * means (e.g., container orchestration, process manager).
   *
   * <p><strong>Warning:</strong> Shutdown hooks must complete quickly. If shutdown takes longer
   * than the configured timeout, the JVM may force-exit before cleanup is complete.
   */
  public void installJvmShutdownHook() {
    Runtime.getRuntime().addShutdownHook(
        new Thread(
            () -> {
              log.info("JVM shutdown hook triggered — initiating graceful shutdown");
              performShutdown();
            },
            "JOTP-ShutdownHook"));
  }

  /**
   * Perform graceful shutdown of all applications.
   *
   * <p>Called by signal handlers and shutdown hooks. Ensures that:
   * <ol>
   *   <li>stopAll() is called only once (re-entrance is prevented)
   *   <li>All applications are stopped in reverse dependency order
   *   <li>Each application gets the configured timeout to shut down
   *   <li>Final health status is logged
   * </ol>
   */
  private synchronized void performShutdown() {
    if (shuttingDown) {
      log.fine("Shutdown already in progress, skipping duplicate request");
      return;
    }

    shuttingDown = true;

    try {
      log.info("Starting graceful shutdown of all applications (timeout: " + shutdownTimeout + ")");
      controller.stopAll(shutdownTimeout);

      var health = controller.health();
      log.info("Graceful shutdown complete. Final health status: " + health);

      // Log summary
      var statuses = controller.allStatuses();
      for (var status : statuses) {
        if (status.health() == ApplicationHealth.DOWN) {
          log.fine("Application stopped: " + status.name());
        } else {
          log.warning("Application still running: " + status.name());
        }
      }
    } catch (Exception e) {
      log.severe("Error during graceful shutdown: " + e.getMessage());
      e.printStackTrace(System.err);
    }
  }

  /**
   * Check if shutdown is currently in progress.
   *
   * @return {@code true} if shutdown has been initiated, {@code false} otherwise
   */
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  /**
   * Convenience: install both signal handlers and JVM shutdown hook.
   *
   * <p>This is the typical usage pattern for production systems.
   *
   * @return this ShutdownManager for method chaining
   */
  public ShutdownManager installAll() {
    installSignalHandlers();
    installJvmShutdownHook();
    return this;
  }
}
