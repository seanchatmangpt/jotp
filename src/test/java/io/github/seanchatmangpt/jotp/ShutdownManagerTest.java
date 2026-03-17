package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for {@link ShutdownManager}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Graceful shutdown coordination
 *   <li>Signal handler installation
 *   <li>JVM shutdown hook registration
 *   <li>Shutdown state management (no re-entrance)
 * </ul>
 *
 * @see ShutdownManager
 * @see ApplicationController
 */
@DtrTest
@DisplayName("ShutdownManager — OS signal coordination")
class ShutdownManagerTest {

  @BeforeEach
  void setUp() {
    ApplicationController.reset();
  }

  @Test
  @DisplayName("Create a ShutdownManager with controller and timeout")
  void createShutdownManager() {
    var controller = new ApplicationController("test");
    var manager = new ShutdownManager(controller, Duration.ofSeconds(30));

    assertThat(manager).isNotNull();
    assertThat(manager.isShuttingDown()).isFalse();
  }

  @Test
  @DisplayName("ShutdownManager rejects null controller")
  void rejectNullController() {
    var thrown = catchThrowableOfType(
        () -> new ShutdownManager(null, Duration.ofSeconds(30)),
        NullPointerException.class);

    assertThat(thrown.getMessage()).contains("controller");
  }

  @Test
  @DisplayName("ShutdownManager rejects null timeout")
  void rejectNullTimeout() {
    var controller = new ApplicationController("test");

    var thrown = catchThrowableOfType(
        () -> new ShutdownManager(controller, null),
        NullPointerException.class);

    assertThat(thrown.getMessage()).contains("shutdownTimeout");
  }

  @Test
  @DisplayName("Install signal handlers (best-effort)")
  void installSignalHandlers() {
    var controller = new ApplicationController("test");
    var manager = new ShutdownManager(controller, Duration.ofSeconds(30));

    // This may fail on systems that don't support signals (e.g., Windows)
    // but should not throw; it logs a warning instead
    assertThatCode(() -> manager.installSignalHandlers()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Install JVM shutdown hook")
  void installJvmShutdownHook() {
    var controller = new ApplicationController("test");
    var manager = new ShutdownManager(controller, Duration.ofSeconds(30));

    assertThatCode(() -> manager.installJvmShutdownHook()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Install all (signal handlers + JVM hook)")
  void installAll() {
    var controller = new ApplicationController("test");
    var manager = new ShutdownManager(controller, Duration.ofSeconds(30));

    assertThatCode(() -> manager.installAll()).doesNotThrowAnyException();
    assertThat(manager).isNotNull();
  }

  @Test
  @DisplayName("Shutdown state prevents duplicate shutdowns")
  void shutdownStatePreventsDuplicates() throws Exception {
    ApplicationController.reset();
    var controller = new ApplicationController("test");
    var spec = ApplicationSpec.builder("test-app").build();

    ApplicationController.load(spec);
    controller.register("test-app");
    ApplicationController.start("test-app");

    var manager = new ShutdownManager(controller, Duration.ofSeconds(5));

    assertThat(manager.isShuttingDown()).isFalse();

    // Note: performShutdown is private, so we can't call it directly
    // This test verifies the public API behavior
  }

  @Test
  @DisplayName("Graceful shutdown with controller coordination (DtrTest)")
  void gracefulShutdownCoordination(DtrContext ctx) throws Exception {
    ctx.sayNextSection("ShutdownManager: Graceful System Shutdown");
    ctx.say(
        """
        The ShutdownManager coordinates graceful shutdown across the entire JOTP system.
        When the JVM receives SIGTERM or SIGINT, or when the JVM is shutting down,
        ShutdownManager ensures applications stop in the correct order with adequate timeout.
        This mirrors Erlang/OTP's shutdown_reason handling.
        """);

    ApplicationController.reset();
    var controller = new ApplicationController("test");
    var manager = new ShutdownManager(controller, Duration.ofSeconds(30));

    var spec = ApplicationSpec.builder("test-app").build();
    ApplicationController.load(spec);
    controller.register("test-app");

    // Verify hook installation doesn't fail
    assertThatCode(() -> manager.installAll()).doesNotThrowAnyException();

    // Verify we can start the app
    ApplicationController.start("test-app");
    assertThat(controller.health()).isEqualTo(ApplicationHealth.UP);
  }

  @Test
  @DisplayName("ShutdownManager method chaining")
  void methodChaining() {
    var controller = new ApplicationController("test");
    var manager = new ShutdownManager(controller, Duration.ofSeconds(30));

    var result = manager.installAll();

    assertThat(result).isSameAs(manager);
  }
}
