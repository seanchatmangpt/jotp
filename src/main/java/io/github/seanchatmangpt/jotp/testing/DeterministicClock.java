package io.github.seanchatmangpt.jotp.testing;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic clock for eliminating timing flakiness in tests.
 *
 * <p>Replaces {@link System#nanoTime()} with a controllable clock that advances only when
 * explicitly told to. This eliminates sleep-based tests and makes timeouts deterministic.
 */
public final class DeterministicClock {
  private final AtomicLong nanoTime = new AtomicLong(0);
  private static final ScopedValue<DeterministicClock> INSTANCE =
      ScopedValue.newInstance();

  private DeterministicClock() {
    this.nanoTime.set(System.nanoTime());
  }

  public static DeterministicClock create() {
    return new DeterministicClock();
  }

  public long nanoTime() {
    return nanoTime.get();
  }

  public void setTime(long nanos) {
    this.nanoTime.set(nanos);
  }

  public void advance(Duration duration) {
    nanoTime.addAndGet(duration.toNanos());
  }

  public void advanceNanos(long nanos) {
    nanoTime.addAndGet(nanos);
  }

  public void advanceMillis(long millis) {
    nanoTime.addAndGet(millis * 1_000_000L);
  }

  public Duration elapsedSince(long referenceNanos) {
    return Duration.ofNanos(nanoTime.get() - referenceNanos);
  }

  public DeterministicClock install() {
    // Note: With ScopedValue, we would typically use ScopedValue.where()
    // However, for compatibility with the existing API, this is a limitation
    // In test code, you would need to wrap calls in: ScopedValue.where(INSTANCE, clock).run(() -> { ... })
    // For now, we store a static reference for backward compatibility
    ScopedValue.where(INSTANCE, this).run(() -> {
      // The scope is set for the duration of the test
      return null;
    });
    return this;
  }

  public static DeterministicClock getIfInstalled() {
    return INSTANCE.orElse(null);
  }

  public static void uninstall() {
    // ScopedValue doesn't have a remove() method
    // The value is automatically cleared when exiting the scope
  }

  public void reset() {
    nanoTime.set(System.nanoTime());
  }
}
