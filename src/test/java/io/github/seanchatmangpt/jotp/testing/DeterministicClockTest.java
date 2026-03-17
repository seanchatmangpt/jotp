package io.github.seanchatmangpt.jotp.testing;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for DeterministicClock. */
class DeterministicClockTest {

  private DeterministicClock clock;

  @BeforeEach
  void setup() {
    clock = DeterministicClock.create();
    DeterministicClock.uninstall();
  }

  @Test
  void testInitialTime() {
    assertThat(clock.nanoTime()).isGreaterThan(0);
  }

  @Test
  void testSetTime() {
    clock.setTime(1000);
    assertThat(clock.nanoTime()).isEqualTo(1000);
  }

  @Test
  void testAdvanceDuration() {
    clock.setTime(1000);
    clock.advance(Duration.ofMillis(100));

    assertThat(clock.nanoTime()).isEqualTo(1000 + 100_000_000L);
  }

  @Test
  void testAdvanceNanos() {
    clock.setTime(0);
    clock.advanceNanos(500);

    assertThat(clock.nanoTime()).isEqualTo(500);
  }

  @Test
  void testAdvanceMillis() {
    clock.setTime(0);
    clock.advanceMillis(50);

    assertThat(clock.nanoTime()).isEqualTo(50_000_000L);
  }

  @Test
  void testElapsedSince() {
    clock.setTime(0);
    long ref = clock.nanoTime();
    clock.advance(Duration.ofMillis(100));

    Duration elapsed = clock.elapsedSince(ref);
    assertThat(elapsed).isEqualTo(Duration.ofMillis(100));
  }

  @Test
  void testInstallAndGet() {
    clock.install();
    assertThat(DeterministicClock.getIfInstalled()).isSameAs(clock);
  }

  @Test
  void testUninstall() {
    clock.install();
    DeterministicClock.uninstall();

    assertThat(DeterministicClock.getIfInstalled()).isNull();
  }

  @Test
  void testReset() {
    clock.setTime(1000);
    long beforeReset = System.nanoTime();
    clock.reset();
    long afterReset = System.nanoTime();

    assertThat(clock.nanoTime()).isGreaterThanOrEqualTo(beforeReset);
    assertThat(clock.nanoTime()).isLessThanOrEqualTo(afterReset + 1_000_000L);
  }

  @Test
  void testMultipleAdvances() {
    clock.setTime(0);

    clock.advance(Duration.ofMillis(10));
    assertThat(clock.nanoTime()).isEqualTo(10_000_000L);

    clock.advance(Duration.ofMillis(20));
    assertThat(clock.nanoTime()).isEqualTo(30_000_000L);

    clock.advanceNanos(500);
    assertThat(clock.nanoTime()).isEqualTo(30_000_500L);
  }

  @Test
  void testClockIndependence() {
    DeterministicClock clock1 = DeterministicClock.create();
    DeterministicClock clock2 = DeterministicClock.create();

    clock1.setTime(1000);
    clock2.setTime(2000);

    assertThat(clock1.nanoTime()).isEqualTo(1000);
    assertThat(clock2.nanoTime()).isEqualTo(2000);

    clock1.advance(Duration.ofMillis(100));
    assertThat(clock1.nanoTime()).isEqualTo(1000 + 100_000_000L);
    assertThat(clock2.nanoTime()).isEqualTo(2000);
  }

  @Test
  void testNegativeTimeJumps() {
    clock.setTime(1000);
    clock.advanceNanos(-500);

    assertThat(clock.nanoTime()).isEqualTo(500);
  }

  @Test
  void testLargeTimeAdvances() {
    clock.setTime(0);
    clock.advance(Duration.ofDays(1));

    assertThat(clock.nanoTime()).isEqualTo(Duration.ofDays(1).toNanos());
  }
}
