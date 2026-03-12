package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

/**
 * Sealed interface for bulkhead isolation strategies.
 *
 * Defines how resources are allocated and isolated between features.
 */
public sealed interface BulkheadStrategy permits
    BulkheadStrategy.ThreadPoolBased,
    BulkheadStrategy.ProcessBased,
    BulkheadStrategy.Weighted,
    BulkheadStrategy.Adaptive {

  /** Traditional thread pool based isolation. */
  record ThreadPoolBased(int poolSize) implements BulkheadStrategy {}

  /** JOTP native: one Proc per feature (virtual thread based). */
  record ProcessBased() implements BulkheadStrategy {}

  /** Resource-aware isolation with CPU/memory weights. */
  record Weighted(double cpuWeight, double memoryWeight) implements BulkheadStrategy {}

  /** Dynamic sizing based on current load. */
  record Adaptive(int minSize, int maxSize) implements BulkheadStrategy {}
}
