package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

/**
 * Sealed interface for bulkhead isolation strategies.
 *
 * <p>Defines how resources are allocated and isolated between features in a shared system. Each
 * strategy represents a different approach to resource management with different trade-offs in
 * isolation, efficiency, and complexity.
 *
 * <h2>Strategy Types:</h2>
 *
 * <ul>
 *   <li><b>ThreadPoolBased</b>: Traditional thread pool per feature. Each feature gets dedicated
 *       threads. High isolation but poor resource utilization. Best for: Features with consistent
 *       load and strict latency requirements
 *   <li><b>ProcessBased</b>: JOTP native: one {@link io.github.seanchatmangpt.jotp.Proc} per
 *       feature (virtual thread based). Lightweight, millions of processes possible. Best for: Most
 *       use cases, recommended default
 *   <li><b>Weighted</b>: Resource-aware isolation with CPU/memory weights. Features compete for
 *       resources based on assigned weights. Best for: Fair resource allocation across features
 *   <li><b>Adaptive</b>: Dynamic sizing based on current load. Expands/contracts based on demand.
 *       Best for: Variable load patterns, efficient resource utilization
 * </ul>
 *
 * <h2>Selection Guide:</h2>
 *
 * <pre>
 * High isolation required         → ThreadPoolBased
 * JOTP native (recommended)       → ProcessBased
 * Fair sharing across features    → Weighted
 * Variable load patterns          → Adaptive
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Process-based: JOTP native, lightweight
 * BulkheadStrategy process = new BulkheadStrategy.ProcessBased();
 *
 * // Thread pool: 10 threads per feature
 * BulkheadStrategy threads = new BulkheadStrategy.ThreadPoolBased(10);
 *
 * // Weighted: prioritize critical features
 * BulkheadStrategy weighted = new BulkheadStrategy.Weighteded(0.7, 0.3);
 *
 * // Adaptive: scale 5-20 based on load
 * BulkheadStrategy adaptive = new BulkheadStrategy.Adaptive(5, 20);
 * }</pre>
 *
 * <h2>Performance Comparison:</h2>
 *
 * <ul>
 *   <li><b>ThreadPoolBased</b>: High memory (~1MB/thread), low context switch overhead
 *   <li><b>ProcessBased</b>: Low memory (~1KB/process), virtual thread scheduling
 *   <li><b>Weighted</b>: Medium memory, fair allocation overhead
 *   <li><b>Adaptive</b>: Dynamic memory, resize overhead
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link BulkheadIsolationEnterprise} to determine resource allocation
 *   <li>ProcessBased leverages JOTP's virtual thread primitives
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see BulkheadIsolationEnterprise
 * @see BulkheadConfig
 * @see io.github.seanchatmangpt.jotp.Proc
 * @since 1.0
 */
public sealed interface BulkheadStrategy
        permits BulkheadStrategy.ThreadPoolBased,
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
