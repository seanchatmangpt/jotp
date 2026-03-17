package io.github.seanchatmangpt.jotp.testing;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for FaultInjectionSupervisor. */
class FaultInjectionSupervisorTest {

  private Supervisor supervisor;
  private FaultInjectionSupervisor faultInjector;

  @BeforeEach
  void setup() {
    supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
    faultInjector = FaultInjectionSupervisor.wrap(supervisor);
  }

  @Test
  void testWrapSupervisor() {
    assertThat(faultInjector.getDelegate()).isSameAs(supervisor);
  }

  @Test
  void testCrashAtSequence() {
    faultInjector.crashAt("worker-1", 5);

    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();

    for (int i = 0; i < 4; i++) {
      assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
    }

    assertThat(faultInjector.shouldInjectFault("worker-1")).isTrue();
    assertThat(faultInjector.wasCrashed("worker-1")).isTrue();
  }

  @Test
  void testCrashAfterDuration() throws InterruptedException {
    faultInjector.crashAfter("worker-1", Duration.ofMillis(100));

    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();

    await().atMost(Duration.ofMillis(250))
            .until(() -> faultInjector.shouldInjectFault("worker-1"));

    assertThat(faultInjector.wasCrashed("worker-1")).isTrue();
  }

  @Test
  void testMultipleProcesses() {
    faultInjector.crashAt("worker-1", 2);
    faultInjector.crashAt("worker-2", 3);

    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
    assertThat(faultInjector.shouldInjectFault("worker-2")).isFalse();

    faultInjector.shouldInjectFault("worker-1");
    assertThat(faultInjector.shouldInjectFault("worker-1")).isTrue();
    assertThat(faultInjector.shouldInjectFault("worker-2")).isFalse();
  }

  @Test
  void testCrashOnceOnly() {
    faultInjector.crashAt("worker-1", 1);

    assertThat(faultInjector.shouldInjectFault("worker-1")).isTrue();
    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
  }

  @Test
  void testReset() {
    faultInjector.crashAt("worker-1", 2);
    faultInjector.shouldInjectFault("worker-1");
    faultInjector.shouldInjectFault("worker-1");

    assertThat(faultInjector.wasCrashed("worker-1")).isTrue();

    faultInjector.reset();

    assertThat(faultInjector.wasCrashed("worker-1")).isFalse();
    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
  }

  @Test
  void testUnregisteredProcessNeverCrashes() {
    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
    assertThat(faultInjector.wasCrashed("worker-1")).isFalse();
  }

  @Test
  void testDurationAfterCreation() throws InterruptedException {
    Duration delay = Duration.ofMillis(200);
    faultInjector.crashAfter("worker-1", delay);

    assertThat(faultInjector.shouldInjectFault("worker-1")).isFalse();
    await().atMost(Duration.ofMillis(delay.toMillis() + 100))
            .until(() -> faultInjector.shouldInjectFault("worker-1"));
  }

  @Test
  void testMultipleCrashScenarios() {
    faultInjector.crashAt("proc-1", 1);
    faultInjector.crashAt("proc-2", 5);
    faultInjector.crashAt("proc-3", 10);

    for (int i = 0; i < 3; i++) {
      faultInjector.shouldInjectFault("proc-1");
    }
    assertThat(faultInjector.wasCrashed("proc-1")).isTrue();

    for (int i = 0; i < 4; i++) {
      faultInjector.shouldInjectFault("proc-2");
    }
    assertThat(faultInjector.wasCrashed("proc-2")).isFalse();

    faultInjector.shouldInjectFault("proc-2");
    assertThat(faultInjector.wasCrashed("proc-2")).isTrue();
  }

  @Test
  void testCrashAtZero() {
    faultInjector.crashAt("worker-1", 0);

    assertThat(faultInjector.shouldInjectFault("worker-1")).isTrue();
    assertThat(faultInjector.wasCrashed("worker-1")).isTrue();
  }

  @Test
  void testLargeCrashSequence() {
    faultInjector.crashAt("worker-1", 1000);

    for (int i = 0; i < 999; i++) {
      faultInjector.shouldInjectFault("worker-1");
    }

    assertThat(faultInjector.wasCrashed("worker-1")).isFalse();

    faultInjector.shouldInjectFault("worker-1");
    assertThat(faultInjector.wasCrashed("worker-1")).isTrue();
  }
}
