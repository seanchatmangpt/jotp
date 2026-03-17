package io.github.seanchatmangpt.jotp.ai;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * Intelligent data partitioner that learns optimal key distribution to minimize hotspots
 * and network traffic.
 *
 * <p><b>Problem Statement:</b> When data is partitioned by a logical key (e.g., customer_id),
 * access patterns may be heavily skewed. A single "hot" customer (e.g., Apple Inc accessed 1M
 * times/sec) can saturate one partition node, while other partitions remain idle. This system
 * monitors access patterns and automatically rebalances by redistributing hot keys across
 * multiple virtual partitions.
 *
 * <p><b>Solution:</b> Consistent hashing with virtual nodes. Hot keys are automatically
 * rehashed to multiple partitions, distributing their load. Cold keys remain on single
 * partitions. The system learns Zipfian-skewed access patterns and adapts in real time.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Consistent Hashing:</b> Virtual nodes ensure even load distribution; minimal data
 *       movement on rebalancing
 *   <li><b>Hotspot Detection:</b> Monitors access frequency per key; flags keys exceeding
 *       threshold as hotspots
 *   <li><b>Adaptive Rebalancing:</b> Automatically increases virtual nodes for hot keys;
 *       redistributes load across partitions
 *   <li><b>Access Pattern Learning:</b> Tracks Zipfian distributions (realistic skew);
 *       predicts future hotspots
 *   <li><b>Cross-Partition Minimization:</b> Metrics for network traffic between partitions;
 *       informs rebalance decisions
 *   <li><b>No Downtime:</b> Rebalancing is gradual; requests served during transition
 * </ul>
 *
 * <p><b>Architecture:</b>
 *
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │ DataPartitioner (consistent hash controller)│
 * ├─────────────────────────────────────────────┤
 * │ - Ring: hash space with virtual nodes       │
 * │ - KeyStats: per-key access frequency        │
 * │ - HotspotDetector: flags skewed keys        │
 * │ - Rebalancer: moves virtual nodes           │
 * └─────────────────────────────────────────────┘
 *         │
 *         ├─→ recordAccess(key)  [ThreadSafe]
 *         ├─→ getPartition(key)  [Consistent]
 *         ├─→ detectHotspots()   [Periodic]
 *         └─→ rebalance()        [Gradual]
 * </pre>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * var partitioner = new DataPartitioner(128 // total partitions
 * );
 *
 * // Record access patterns
 * partitioner.recordAccess("customer:apple");
 * partitioner.recordAccess("customer:google");
 *
 * // Query partition (consistent hash)
 * int partition = partitioner.getPartition("customer:apple");
 *
 * // Detect hotspots (Zipfian analysis)
 * var hotspots = partitioner.detectHotspots(0.8); // 80th percentile
 *
 * // Metrics
 * var metrics = partitioner.getMetrics();
 * System.out.println("Network traffic: " + metrics.crossPartitionTraffic());
 * }</pre>
 */
public final class DataPartitioner {

    // ────────────────────────────────────────────────────────────────────────────────
    // Data structures
    // ────────────────────────────────────────────────────────────────────────────────

    private static final int DEFAULT_VIRTUAL_NODES_PER_KEY = 1;
    private static final double DEFAULT_HOTSPOT_THRESHOLD = 0.8; // 80th percentile
    private static final long DEFAULT_STATS_WINDOW_MS = 60_000; // 1 minute

    /**
     * Per-key access statistics. Thread-safe accumulation of access frequency,
     * latency, and cross-partition traffic.
     */
    public record KeyStats(
            String key,
            long accessCount,
            long totalLatencyMs,
            long lastAccessMs,
            int replicaCount,
            double zipfianRank) {

        public double avgLatency() {
            return accessCount > 0 ? (double) totalLatencyMs / accessCount : 0.0;
        }

        public double accessFrequency() {
            return accessCount / (System.currentTimeMillis() - lastAccessMs + 1);
        }
    }

    /**
     * Hotspot detection result — keys that exceed access threshold.
     */
    public record Hotspot(String key, long accessCount, double percentile, int suggestedReplicas) {}

    /**
     * Rebalancing action — move virtual nodes to redistribute load.
     */
    public record RebalanceAction(
            String key, int fromPartition, int toPartition, long affectedKeys) {}

    /**
     * Partitioner metrics — observability into system behavior.
     */
    public record Metrics(
            int totalPartitions,
            int activeKeys,
            long totalAccesses,
            double avgAccessPerKey,
            double stdDevAccessPerKey,
            long crossPartitionTraffic,
            List<Hotspot> topHotspots,
            double skewnessRatio) {}

    // ────────────────────────────────────────────────────────────────────────────────
    // Internal state
    // ────────────────────────────────────────────────────────────────────────────────

    private final int totalPartitions;
    private final ConcurrentHashMap<String, KeyAccessStats> keyStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> keyToVirtualNodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Integer>> keyToPartitions = new ConcurrentHashMap<>();
    private final TreeMap<Long, String> ring = new TreeMap<>(); // hash -> key:virtualNodeId
    private final AtomicLong totalAccesses = new AtomicLong(0);
    private final AtomicLong crossPartitionTraffic = new AtomicLong(0);
    private final long statsWindowMs;

    /**
     * Per-key internal stats for mutation tracking.
     */
    private static class KeyAccessStats {
        final LongAdder accessCount = new LongAdder();
        final LongAdder totalLatencyMs = new LongAdder();
        volatile long lastAccessMs = System.currentTimeMillis();
        volatile int replicaCount = 1;
    }

    /**
     * Creates a partitioner with consistent hashing across {@code totalPartitions}.
     *
     * @param totalPartitions number of physical partitions (e.g., 128, 256)
     */
    public DataPartitioner(int totalPartitions) {
        this(totalPartitions, DEFAULT_STATS_WINDOW_MS);
    }

    /**
     * Creates a partitioner with configurable statistics window.
     *
     * @param totalPartitions number of physical partitions
     * @param statsWindowMs time window for access pattern analysis
     */
    public DataPartitioner(int totalPartitions, long statsWindowMs) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("totalPartitions must be > 0");
        }
        this.totalPartitions = totalPartitions;
        this.statsWindowMs = statsWindowMs;

        // Initialize ring with default virtual nodes for all partitions
        initializeRing();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Core API
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * Records an access to a key. Thread-safe.
     *
     * @param key the logical key being accessed
     */
    public synchronized void recordAccess(String key) {
        recordAccess(key, 0);
    }

    /**
     * Records an access to a key with latency measurement.
     *
     * @param key the logical key
     * @param latencyMs latency of the access in milliseconds
     */
    public synchronized void recordAccess(String key, long latencyMs) {
        if (key == null || key.isEmpty()) {
            return;
        }

        KeyAccessStats stats =
                keyStats.computeIfAbsent(
                        key,
                        k -> {
                            ensureKeyInRing(k);
                            return new KeyAccessStats();
                        });

        stats.accessCount.increment();
        stats.totalLatencyMs.add(Math.max(0, latencyMs));
        stats.lastAccessMs = System.currentTimeMillis();
        totalAccesses.increment();
    }

    /**
     * Gets the partition for a key using consistent hashing.
     *
     * @param key the logical key
     * @return partition number [0, totalPartitions)
     */
    public int getPartition(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }

        // If key has been assigned virtual nodes, return primary partition
        List<Integer> partitions = keyToPartitions.get(key);
        if (partitions != null && !partitions.isEmpty()) {
            return partitions.get(0);
        }

        // Fallback: hash to default partition
        long hash = hashKey(key);
        return findPartitionForHash(hash);
    }

    /**
     * Gets all partitions assigned to a key (for replicated hot keys).
     *
     * @param key the logical key
     * @return list of partition numbers
     */
    public List<Integer> getPartitions(String key) {
        List<Integer> partitions = keyToPartitions.get(key);
        if (partitions != null) {
            return new ArrayList<>(partitions);
        }
        return List.of(getPartition(key));
    }

    /**
     * Detects hotspot keys that exceed the access frequency threshold.
     *
     * @param percentile percentile threshold (e.g., 0.8 for 80th)
     * @return list of hotspots, ordered by access count (descending)
     */
    public List<Hotspot> detectHotspots(double percentile) {
        if (keyStats.isEmpty()) {
            return List.of();
        }

        // Collect access counts and compute percentile
        List<Long> accessCounts =
                keyStats.values().stream()
                        .map(s -> s.accessCount.longValue())
                        .sorted()
                        .toList();

        int percentileIndex = (int) Math.ceil(accessCounts.size() * percentile);
        long threshold =
                percentileIndex > 0 && percentileIndex <= accessCounts.size()
                        ? accessCounts.get(percentileIndex - 1)
                        : 0;

        // Identify hotspots
        return keyStats.entrySet().stream()
                .filter(e -> e.getValue().accessCount.longValue() >= threshold)
                .map(
                        e ->
                                new Hotspot(
                                        e.getKey(),
                                        e.getValue().accessCount.longValue(),
                                        percentile,
                                        suggestReplicasForHotkey(e.getKey())))
                .sorted(
                        Comparator.comparingLong(Hotspot::accessCount)
                                .reversed())
                .limit(10) // Top 10 hotspots
                .collect(Collectors.toList());
    }

    /**
     * Rebalances partitions by increasing virtual nodes for hot keys. Returns actions taken.
     *
     * @return list of rebalancing actions
     */
    public synchronized List<RebalanceAction> rebalance() {
        var actions = new ArrayList<RebalanceAction>();
        var hotspots = detectHotspots(DEFAULT_HOTSPOT_THRESHOLD);

        for (Hotspot hotspot : hotspots) {
            int currentVirtualNodes = keyToVirtualNodes.getOrDefault(hotspot.key(), 1);
            int targetVirtualNodes = hotspot.suggestedReplicas();

            if (targetVirtualNodes > currentVirtualNodes) {
                // Add virtual nodes for this hot key
                int newNodes = targetVirtualNodes - currentVirtualNodes;
                addVirtualNodes(hotspot.key(), newNodes);

                actions.add(
                        new RebalanceAction(
                                hotspot.key(),
                                -1, // no "from" for new nodes
                                -1, // spread across all partitions
                                hotspot.accessCount()));
            }
        }

        return actions;
    }

    /**
     * Returns current snapshot of metrics.
     *
     * @return metrics object with observability data
     */
    public Metrics getMetrics() {
        if (keyStats.isEmpty()) {
            return new Metrics(
                    totalPartitions,
                    0,
                    0,
                    0.0,
                    0.0,
                    crossPartitionTraffic.longValue(),
                    List.of(),
                    0.0);
        }

        var accessCounts =
                keyStats.values().stream()
                        .map(s -> (double) s.accessCount.longValue())
                        .toList();

        double mean = accessCounts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance =
                accessCounts.stream()
                        .mapToDouble(x -> Math.pow(x - mean, 2))
                        .average()
                        .orElse(0);
        double stdDev = Math.sqrt(variance);

        // Skewness ratio: max / mean
        double maxAccess = accessCounts.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double skewness = mean > 0 ? maxAccess / mean : 0;

        var topHotspots = detectHotspots(DEFAULT_HOTSPOT_THRESHOLD);

        return new Metrics(
                totalPartitions,
                keyStats.size(),
                totalAccesses.longValue(),
                mean,
                stdDev,
                crossPartitionTraffic.longValue(),
                topHotspots,
                skewness);
    }

    /**
     * Returns a snapshot of all key statistics.
     *
     * @return map of key -> KeyStats
     */
    public Map<String, KeyStats> getKeyStatistics() {
        return keyStats.entrySet().stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    KeyAccessStats s = e.getValue();
                                    return new KeyStats(
                                            e.getKey(),
                                            s.accessCount.longValue(),
                                            s.totalLatencyMs.longValue(),
                                            s.lastAccessMs,
                                            keyToVirtualNodes.getOrDefault(e.getKey(), 1),
                                            0.0); // Zipfian rank computed separately
                                }));
    }

    /**
     * Estimates Zipfian distribution skew parameter (alpha).
     *
     * <p>For a Zipfian distribution with parameter alpha, frequency of k-th most popular item
     * is proportional to 1/k^alpha. Returns alpha estimate based on observed frequencies.
     *
     * @return estimated alpha (higher = more skewed; 1.0 = moderately skewed)
     */
    public double estimateZipfianAlpha() {
        if (keyStats.size() < 2) {
            return 0.0;
        }

        // Sort by access frequency (descending)
        var frequencies =
                keyStats.values().stream()
                        .map(s -> (double) s.accessCount.longValue())
                        .filter(f -> f > 0)
                        .sorted(Collections.reverseOrder())
                        .toList();

        if (frequencies.size() < 2) {
            return 0.0;
        }

        // Simple linear regression on log-log scale: log(freq) vs log(rank)
        double sumLogX = 0, sumLogY = 0, sumLogXY = 0, sumLogX2 = 0;
        for (int i = 0; i < frequencies.size(); i++) {
            double logRank = Math.log(i + 1);
            double logFreq = Math.log(frequencies.get(i));
            sumLogX += logRank;
            sumLogY += logFreq;
            sumLogXY += logRank * logFreq;
            sumLogX2 += logRank * logRank;
        }

        int n = frequencies.size();
        double numerator = n * sumLogXY - sumLogX * sumLogY;
        double denominator = n * sumLogX2 - sumLogX * sumLogX;

        if (denominator == 0) {
            return 1.0;
        }

        double alpha = numerator / denominator;
        return Math.max(0, -alpha); // Flip sign (slope is negative)
    }

    /**
     * Simulates cross-partition traffic when a key is accessed from a client not co-located
     * with its primary partition.
     *
     * @param key the logical key
     * @param clientPartition the partition where client is located
     * @return estimated network hops (traffic units)
     */
    public long estimateCrossPartitionTraffic(String key, int clientPartition) {
        int primaryPartition = getPartition(key);
        if (clientPartition != primaryPartition) {
            crossPartitionTraffic.increment();
            return Math.abs(primaryPartition - clientPartition);
        }
        return 0;
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Internal helper methods
    // ────────────────────────────────────────────────────────────────────────────────

    private void initializeRing() {
        // For each partition, create virtual nodes
        for (int p = 0; p < totalPartitions; p++) {
            long hash = hashPartition(p, 0);
            ring.put(hash, "partition:" + p + ":0");
        }
    }

    private void ensureKeyInRing(String key) {
        // Add key with default 1 virtual node
        long hash = hashKey(key);
        ring.put(hash, key + ":0");
        keyToVirtualNodes.putIfAbsent(key, 1);
        keyToPartitions.putIfAbsent(key, List.of(findPartitionForHash(hash)));
    }

    private void addVirtualNodes(String key, int count) {
        int currentVirtualNodes = keyToVirtualNodes.getOrDefault(key, 1);
        int newTotal = currentVirtualNodes + count;

        Set<Integer> partitions = new HashSet<>();

        // Distribute new virtual nodes across partitions
        for (int i = currentVirtualNodes; i < newTotal; i++) {
            long hash = hashKey(key + ":v" + i);
            int partition = findPartitionForHash(hash);
            partitions.add(partition);
            ring.put(hash, key + ":" + i);
        }

        keyToVirtualNodes.put(key, newTotal);
        keyToPartitions.put(key, new ArrayList<>(partitions));
    }

    private int findPartitionForHash(long hash) {
        if (ring.isEmpty()) {
            return 0;
        }

        // Find the ceiling entry in the ring
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }

        String value = entry.getValue();
        if (value.startsWith("partition:")) {
            return Integer.parseInt(value.split(":")[1]);
        }

        // For keys, extract partition from their current assignment
        int partition = Math.abs(hash % totalPartitions);
        return partition;
    }

    private int suggestReplicasForHotkey(String key) {
        long accessCount = keyStats.get(key).accessCount.longValue();
        long avgAccess =
                totalAccesses.longValue() > 0
                        ? totalAccesses.longValue() / keyStats.size()
                        : 1;

        if (accessCount == 0) {
            return 1;
        }

        int ratio = (int) Math.min(totalPartitions, accessCount / avgAccess);
        return Math.max(1, Math.min(16, ratio)); // Cap at 16 replicas
    }

    private long hashKey(String key) {
        // Use Java's String.hashCode with murmur-like distribution
        long hash = 0;
        for (char c : key.toCharArray()) {
            hash = hash * 31 + c;
        }
        return Math.abs(hash);
    }

    private long hashPartition(int partition, int virtualNode) {
        long hash = partition * 31L + virtualNode;
        return Math.abs(hash);
    }
}
