package io.github.seanchatmangpt.jotp.ai;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * In-memory training data store for testing and development.
 *
 * <p>Suitable for development and testing. For production, use a real PostgreSQL backend via
 * PostgresTrainingStoreImpl.
 */
public class InMemoryTrainingStore implements PostgresTrainingStore {

  private final ConcurrentHashMap<Long, ClusterOptimizer.TrainingSample> samples =
      new ConcurrentHashMap<>();
  private final LinkedBlockingQueue<ClusterOptimizer.TrainingSample> asyncQueue =
      new LinkedBlockingQueue<>(10000);
  private final ScheduledExecutorService asyncWriter = Executors.newScheduledThreadPool(1);
  private volatile long nextId = 1;

  public InMemoryTrainingStore() {
    // Async writer thread: flush queue every 5 seconds
    asyncWriter.scheduleAtFixedRate(this::flushPending, 5, 5, TimeUnit.SECONDS);
  }

  @Override
  public List<ClusterOptimizer.TrainingSample> fetchTrainingSamples(int limit) {
    return samples.values().stream()
        .sorted(Comparator.comparing(ClusterOptimizer.TrainingSample::timestamp).reversed())
        .limit(limit)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public List<ClusterOptimizer.TrainingSample> fetchTrainingSamplesBetween(
      Instant startTime, Instant endTime) {
    return samples.values().stream()
        .filter(
            s -> !s.timestamp().isBefore(startTime) && !s.timestamp().isAfter(endTime))
        .sorted(Comparator.comparing(ClusterOptimizer.TrainingSample::timestamp))
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public void saveSampleAsync(ClusterOptimizer.TrainingSample sample) {
    // Try to add to queue; if queue is full, drop oldest
    if (!asyncQueue.offer(sample)) {
      asyncQueue.poll(); // Drop oldest
      asyncQueue.offer(sample);
    }
  }

  @Override
  public int flushPending() {
    var flushed = 0;
    var sample = asyncQueue.poll();
    while (sample != null) {
      samples.put(nextId++, sample);
      flushed++;
      sample = asyncQueue.poll();
    }
    return flushed;
  }

  @Override
  public int purgeOldSamples(int retentionDays) {
    var cutoff = Instant.now().minusSeconds((long) retentionDays * 86400);
    var keysToRemove = new ArrayList<Long>();

    for (var entry : samples.entrySet()) {
      if (entry.getValue().timestamp().isBefore(cutoff)) {
        keysToRemove.add(entry.getKey());
      }
    }

    keysToRemove.forEach(samples::remove);
    return keysToRemove.size();
  }

  @Override
  public long countSamples() {
    return samples.size();
  }

  @Override
  public Map<String, Double> getStatistics() {
    if (samples.isEmpty()) {
      return Map.of(
          "min_error", 0.0,
          "max_error", 0.0,
          "avg_error", 0.0,
          "count", 0.0);
    }

    var errors =
        samples.values().stream()
            .mapToDouble(
                s -> Math.abs(s.actualLoad() - s.predictedLoad()))
            .toArray();

    var min = Arrays.stream(errors).min().orElse(0.0);
    var max = Arrays.stream(errors).max().orElse(0.0);
    var avg = Arrays.stream(errors).average().orElse(0.0);

    return Map.of(
        "min_error", min,
        "max_error", max,
        "avg_error", avg,
        "count", (double) errors.length);
  }

  @Override
  public void close() {
    flushPending();
    asyncWriter.shutdown();
    try {
      if (!asyncWriter.awaitTermination(5, TimeUnit.SECONDS)) {
        asyncWriter.shutdownNow();
      }
    } catch (InterruptedException e) {
      asyncWriter.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
