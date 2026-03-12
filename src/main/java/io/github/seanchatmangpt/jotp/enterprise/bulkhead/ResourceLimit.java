package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import java.util.List;

/**
 * Sealed interface for bulkhead resource limits.
 *
 * Defines boundaries for concurrent requests, queue sizes, memory, and CPU.
 */
public sealed interface ResourceLimit permits
    ResourceLimit.MaxConcurrentRequests,
    ResourceLimit.MaxQueueSize,
    ResourceLimit.MaxMemoryBytes,
    ResourceLimit.MaxCPUPercent,
    ResourceLimit.Composite {

  /** Limit on concurrent in-flight requests. */
  record MaxConcurrentRequests(int maxCount) implements ResourceLimit {}

  /** Limit on buffered/queued messages. */
  record MaxQueueSize(int maxSize) implements ResourceLimit {}

  /** Limit on heap memory usage (bytes). */
  record MaxMemoryBytes(long maxBytes) implements ResourceLimit {}

  /** Limit on CPU percentage (0-100). */
  record MaxCPUPercent(double percent) implements ResourceLimit {}

  /** Composite of multiple limits (all must be respected). */
  record Composite(List<ResourceLimit> limits) implements ResourceLimit {}
}
