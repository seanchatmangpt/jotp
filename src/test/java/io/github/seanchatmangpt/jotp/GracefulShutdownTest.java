package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for GracefulShutdownManager. */
class GracefulShutdownTest {

  private GracefulShutdownManager manager;

  @BeforeEach
  void setup() {
    manager = GracefulShutdownManager.create();
  }

  @Test
  void testInitialState() {
    assertThat(manager.getInFlightCount()).isZero();
    assertThat(manager.isShuttingDown()).isFalse();
  }

  @Test
  void testTrackingMessages() {
    manager.onMessageSent("proc-1");
    manager.onMessageSent("proc-1");
    manager.onMessageSent("proc-2");

    assertThat(manager.getInFlightCount()).isEqualTo(3);
    assertThat(manager.getInFlightCount("proc-1")).isEqualTo(2);
    assertThat(manager.getInFlightCount("proc-2")).isEqualTo(1);
  }

  @Test
  void testDrainingMessages() {
    manager.onMessageSent("proc-1");
    manager.onMessageSent("proc-1");
    manager.onMessageSent("proc-2");

    manager.onMessageHandled("proc-1");
    assertThat(manager.getInFlightCount()).isEqualTo(2);

    manager.onMessageHandled("proc-1");
    manager.onMessageHandled("proc-2");
    assertThat(manager.getInFlightCount()).isZero();
  }

  @Test
  void testDrainSuccess() {
    manager.onMessageSent("proc-1");
    manager.onMessageSent("proc-2");

    new Thread(
            () -> {
              try {
                Thread.sleep(100);
                manager.onMessageHandled("proc-1");
                Thread.sleep(50);
                manager.onMessageHandled("proc-2");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            })
        .start();

    boolean drained = manager.drain(Duration.ofSeconds(2));
    assertThat(drained).isTrue();
    assertThat(manager.isShuttingDown()).isTrue();
  }

  @Test
  void testDrainTimeout() {
    manager.onMessageSent("proc-1");

    boolean drained = manager.drain(Duration.ofMillis(100));
    assertThat(drained).isFalse();
    assertThat(manager.isShuttingDown()).isTrue();
  }

  @Test
  void testRejectNewMessagesAfterShutdown() {
    manager.onMessageSent("proc-1");
    manager.drain(Duration.ofMillis(10));

    assertThatThrownBy(() -> manager.onMessageSent("proc-2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Shutdown in progress");
  }

  @Test
  void testReset() {
    manager.onMessageSent("proc-1");
    manager.drain(Duration.ofMillis(10));

    manager.reset();

    assertThat(manager.isShuttingDown()).isFalse();
    assertThat(manager.getInFlightCount()).isZero();

    manager.onMessageSent("proc-2");
    assertThat(manager.getInFlightCount()).isEqualTo(1);
  }

  @Test
  void testDrainImmediatelyReturnsIfNoInFlight() {
    boolean drained = manager.drain(Duration.ofMillis(10));
    assertThat(drained).isTrue();
  }

  @Test
  void testMultipleProcessesIndependent() {
    manager.onMessageSent("proc-1");
    manager.onMessageSent("proc-2");
    manager.onMessageSent("proc-3");

    manager.onMessageHandled("proc-1");
    assertThat(manager.getInFlightCount("proc-1")).isZero();
    assertThat(manager.getInFlightCount("proc-2")).isEqualTo(1);
    assertThat(manager.getInFlightCount("proc-3")).isEqualTo(1);

    manager.onMessageHandled("proc-2");
    manager.onMessageHandled("proc-3");
    assertThat(manager.getInFlightCount()).isZero();
  }
}
