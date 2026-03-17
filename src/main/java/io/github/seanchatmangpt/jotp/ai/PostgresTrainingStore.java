package io.github.seanchatmangpt.jotp.ai;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * PostgreSQL-backed training data store for ML model persistence.
 *
 * <p>Stores and retrieves training samples asynchronously. Table schema:
 *
 * <pre>{@code
 * CREATE TABLE cluster_training_samples (
 *   id BIGSERIAL PRIMARY KEY,
 *   created_at TIMESTAMP DEFAULT NOW(),
 *   features DOUBLE PRECISION[] NOT NULL,
 *   actual_load DOUBLE PRECISION NOT NULL,
 *   predicted_load DOUBLE PRECISION NOT NULL,
 *   node_count INTEGER NOT NULL,
 *   error DOUBLE PRECISION NOT NULL
 * );
 *
 * CREATE INDEX idx_training_samples_created_at ON cluster_training_samples(created_at DESC);
 * }</pre>
 *
 * <p>Uses a bounded queue for async writes to avoid blocking the optimizer during database
 * operations.
 */
public interface PostgresTrainingStore extends AutoCloseable {

  /**
   * Fetch training samples for retraining, optionally filtered by recency.
   *
   * @param limit maximum number of samples to fetch (ordered by most recent first)
   * @return immutable list of training samples, or empty if no data
   */
  List<ClusterOptimizer.TrainingSample> fetchTrainingSamples(int limit);

  /**
   * Fetch samples within a specific time range.
   *
   * @param startTime earliest sample to fetch
   * @param endTime latest sample to fetch
   * @return samples in the time range
   */
  List<ClusterOptimizer.TrainingSample> fetchTrainingSamplesBetween(Instant startTime, Instant endTime);

  /**
   * Save a training sample asynchronously.
   *
   * <p>The write is queued and will be flushed to PostgreSQL in the background. If the queue
   * fills up, the oldest samples are dropped.
   *
   * @param sample data point to persist
   */
  void saveSampleAsync(ClusterOptimizer.TrainingSample sample);

  /**
   * Force-flush all pending samples to PostgreSQL synchronously.
   *
   * <p>Blocks until all queued samples have been written.
   *
   * @return number of samples flushed, or -1 if flush failed
   */
  int flushPending();

  /**
   * Clear old training samples (older than retentionDays).
   *
   * <p>Called periodically to prevent unbounded table growth.
   *
   * @param retentionDays keep samples from the last N days only
   * @return number of samples deleted
   */
  int purgeOldSamples(int retentionDays);

  /**
   * Count total training samples in the store.
   *
   * @return total row count
   */
  long countSamples();

  /**
   * Get basic statistics on stored samples.
   *
   * @return map with "min_error", "max_error", "avg_error", "count"
   */
  Map<String, Double> getStatistics();
}
