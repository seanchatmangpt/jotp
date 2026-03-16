package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import java.util.List;

/**
 * Sealed interface for bulkhead resource limits.
 *
 * <p>Defines boundaries for concurrent requests, queue sizes, memory, and CPU to prevent runaway
 * features from starving other features in a shared system. Each limit represents a different
 * resource dimension that can be constrained.
 *
 * <h2>Limit Types:</h2>
 *
 * <ul>
 *   <li><b>MaxConcurrentRequests</b>: Maximum number of in-flight requests. Primary limit for
 *       throughput control. Typical values: 10-100
 *   <li><b>MaxQueueSize</b>: Maximum number of buffered/queued requests. Prevents unbounded memory
 *       growth. Typical values: 50-500
 *   <li><b>MaxMemoryBytes</b>: Maximum heap memory usage in bytes. Prevents OOM errors. Typical
 *       values: 100MB-1GB
 *   <li><b>MaxCPUPercent</b>: Maximum CPU percentage (0-100). Prevents CPU saturation. Typical
 *       values: 50-90%
 *   <li><b>Composite</b>: Multiple limits combined (all must be respected). Allows
 *       multi-dimensional resource control
 * </ul>
 *
 * <h2>Usage Examples:</h2>
 *
 * <pre>{@code
 * // Limit concurrent requests
 * ResourceLimit limit1 = new ResourceLimit.MaxConcurrentRequests(10);
 *
 * // Limit queue size
 * ResourceLimit limit2 = new ResourceLimit.MaxQueueSize(100);
 *
 * // Limit memory usage
 * ResourceLimit limit3 = new ResourceLimit.MaxMemoryBytes(1024 * 1024 * 100); // 100MB
 *
 * // Limit CPU usage
 * ResourceLimit limit4 = new ResourceLimit.MaxCPUPercent(80.0);
 *
 * // Combine multiple limits
 * ResourceLimit composite = new ResourceLimit.Composite(List.of(
 *     new ResourceLimit.MaxConcurrentRequests(10),
 *     new ResourceLimit.MaxMemoryBytes(1024 * 1024 * 100)
 * ));
 * }</pre>
 *
 * <h2>Selection Guide:</h2>
 *
 * <pre>
 * Control throughput          → MaxConcurrentRequests
 * Prevent queue explosion     → MaxQueueSize
 * Prevent OOM                 → MaxMemoryBytes
 * Prevent CPU saturation      → MaxCPUPercent
 * Multi-dimensional control   → Composite
 * </pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li><b>MaxConcurrentRequests</b>: O(1) check, semaphore-based enforcement
 *   <li><b>MaxQueueSize</b>: O(1) check, bounded queue
 *   <li><b>MaxMemoryBytes</b>: O(1) check, requires JVM heap monitoring
 *   <li><b>MaxCPUPercent</b>: O(1) check, requires OS CPU monitoring
 *   <li><b>Composite</b>: O(n) checks where n = number of limits
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link BulkheadConfig} to define resource boundaries
 *   <li>Enforced by {@link BulkheadIsolationEnterprise} at runtime
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see BulkheadIsolationEnterprise
 * @see BulkheadConfig
 * @since 1.0
 */
public sealed interface ResourceLimit
        permits ResourceLimit.MaxConcurrentRequests,
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
