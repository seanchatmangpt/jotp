package io.github.seanchatmangpt.jotp.stress;

import java.time.Duration;
import java.util.function.LongFunction;

/**
 * Load profile for stress testing — sealed interface representing different load patterns.
 *
 * <p>Each profile defines how to scale load over time, enabling systematic testing of primitive
 * behavior under different stress patterns (constant, ramp-up, spikes, etc.).
 */
public sealed interface LoadProfile {

  /**
   * Get the load (e.g., messages/sec, tasks/sec) for a given elapsed time.
   *
   * @param elapsedMs milliseconds elapsed since test start
   * @return target load for this moment
   */
  long getLoad(long elapsedMs);

  /**
   * Get the test duration for this profile.
   *
   * @return test duration
   */
  Duration getDuration();

  /**
   * Constant load — maintain fixed throughput throughout test.
   *
   * @param load constant throughput (messages/sec, tasks/sec, etc.)
   * @param duration test duration
   */
  record ConstantLoad(long load, Duration duration) implements LoadProfile {
    @Override
    public long getLoad(long elapsedMs) {
      return load;
    }
  }

  /**
   * Ramp load — linearly increase load from start to end.
   *
   * @param startLoad initial throughput
   * @param endLoad target throughput
   * @param duration test duration
   */
  record RampLoad(long startLoad, long endLoad, Duration duration) implements LoadProfile {
    @Override
    public long getLoad(long elapsedMs) {
      if (elapsedMs >= duration.toMillis()) return endLoad;
      long durationMs = duration.toMillis();
      return startLoad + (endLoad - startLoad) * elapsedMs / durationMs;
    }
  }

  /**
   * Spike load — sudden burst followed by return to baseline.
   *
   * @param baselineLoad normal throughput
   * @param peakLoad spike peak throughput
   * @param spikeDurationMs how long the spike lasts
   * @param totalDuration test duration
   */
  record SpikeLoad(long baselineLoad, long peakLoad, long spikeDurationMs, Duration totalDuration)
      implements LoadProfile {
    @Override
    public long getLoad(long elapsedMs) {
      // Spike occurs at 1/3 into the test
      long spikeStartMs = totalDuration.toMillis() / 3;
      long spikeEndMs = spikeStartMs + spikeDurationMs;
      if (elapsedMs >= spikeStartMs && elapsedMs < spikeEndMs) {
        return peakLoad;
      }
      return baselineLoad;
    }

    @Override
    public Duration getDuration() {
      return totalDuration;
    }
  }

  /**
   * Sawtooth load — ramp up and down in cycles.
   *
   * @param minLoad minimum throughput
   * @param maxLoad maximum throughput
   * @param cycleDurationMs how long each ramp up+down cycle takes
   * @param totalDuration test duration
   */
  record SawtoothLoad(
      long minLoad, long maxLoad, long cycleDurationMs, Duration totalDuration)
      implements LoadProfile {
    @Override
    public long getLoad(long elapsedMs) {
      long positionInCycle = elapsedMs % cycleDurationMs;
      long halfCycle = cycleDurationMs / 2;
      if (positionInCycle < halfCycle) {
        // Ramp up
        return minLoad + (maxLoad - minLoad) * positionInCycle / halfCycle;
      } else {
        // Ramp down
        long downProgress = positionInCycle - halfCycle;
        return maxLoad - (maxLoad - minLoad) * downProgress / halfCycle;
      }
    }

    @Override
    public Duration getDuration() {
      return totalDuration;
    }
  }

  /**
   * Custom load — arbitrary load function.
   *
   * @param function lambda returning load for elapsed time
   * @param duration test duration
   */
  record CustomLoad(LongFunction<Long> function, Duration duration) implements LoadProfile {
    @Override
    public long getLoad(long elapsedMs) {
      return function.apply(elapsedMs);
    }
  }
}
