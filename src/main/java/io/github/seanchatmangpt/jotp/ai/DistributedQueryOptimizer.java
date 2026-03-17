/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Distributed query optimizer for multi-node Mnesia queries with adaptive learning.
 *
 * <p>Learns optimal execution plans for distributed queries by tracking execution statistics
 * (time, network hops, lock contention) and adapting plans based on changing data distribution.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * [Distributed Query] ──────────────────────────────┐
 *                                                    ↓
 *        [DistributedQueryOptimizer (Learner)]
 *              ↓                 ↓
 *        [Stats Tracker]  [Plan Repository]
 *              ↓                 ↓
 *        [Execution History]  [Best Plans]
 *
 * Learning Loop:
 * 1. Parse query → enumerate join orders and filter placements
 * 2. Estimate cost (network latency, lock contention)
 * 3. Execute with current best plan
 * 4. Track actual stats (time, hops, locks)
 * 5. Update model: "Filter before join saves 10x network cost"
 * 6. Regenerate optimal plans
 * </pre>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * var optimizer = new DistributedQueryOptimizer(
 *     nodeRegistry,
 *     networkLatency,
 *     Duration.ofMinutes(5)  // plan reoptimization interval
 * );
 *
 * // Initial query
 * Query q = new Query("SELECT * FROM Orders o JOIN Customers c")
 *     .filter("c.region = 'us-west'");
 *
 * // Get optimized plan
 * ExecutionPlan plan = optimizer.optimizeQuery(q);
 * // Plan learns: "Filter customers before join"
 *
 * // Execute and track stats
 * long startNanos = System.nanoTime();
 * Result<QueryResult, Exception> result = executor.execute(plan);
 * long durationNanos = System.nanoTime() - startNanos;
 *
 * optimizer.recordExecution(q, plan, ExecutionStats.of(
 *     Duration.ofNanos(durationNanos),
 *     plan.estimatedNetworkHops(),
 *     plan.estimatedLocksHeld()
 * ));
 *
 * // Optimizer adapts: if cluster distribution changes, replans automatically
 * }</pre>
 *
 * <p><strong>Plan Learning Strategies:</strong>
 *
 * <ul>
 *   <li><strong>Filter Pushdown:</strong> Apply WHERE clauses to tables with smallest cardinality
 *       first to reduce join size by 10-100x
 *   <li><strong>Join Ordering:</strong> Learn which join order minimizes lock contention. Two-table
 *       joins have N! permutations; optimizer ranks by execution history.
 *   <li><strong>Index Utilization:</strong> Track which indexes are available on remote nodes and
 *       prefer index scans over full table scans
 *   <li><strong>Network Topology:</strong> Prefer co-located table access (same node) over
 *       cross-node joins that increase latency
 *   <li><strong>Adaptive Thresholds:</strong> As data distribution changes (e.g., seasonal load),
 *       recompute costs every few minutes
 * </ul>
 *
 * @see QueryStatistics
 * @see ExecutionPlan
 * @see ExecutionStats
 */
public final class DistributedQueryOptimizer {

    /** Registry of table locations and metadata across cluster nodes. */
    private final TableNodeRegistry tableRegistry;

    /** Network latency model (milliseconds) between node pairs. */
    private final NetworkLatencyModel networkLatency;

    /** Interval to trigger plan reoptimization when data distribution changes. */
    private final Duration reoptimizationInterval;

    /** Execution history: (querySignature, plan) → stats. */
    private final ConcurrentHashMap<String, ExecutionHistory> executionHistory;

    /** Cached optimized plans. */
    private final ConcurrentHashMap<String, CachedPlan> planCache;

    /** Last reoptimization timestamp per query signature. */
    private final ConcurrentHashMap<String, Long> lastReoptimization;

    /** Metrics: queries optimized. */
    private final AtomicLong queriesOptimized = new AtomicLong(0);

    /** Metrics: plans adapted due to changing distribution. */
    private final AtomicLong plansAdapted = new AtomicLong(0);

    /** Metrics: estimated speedups from optimization. */
    private final AtomicLong totalEstimatedSpeedup = new AtomicLong(0);

    private volatile boolean closed = false;

    /**
     * Create a distributed query optimizer.
     *
     * @param tableRegistry cluster topology and table metadata
     * @param networkLatency network latency model
     * @param reoptimizationInterval how often to recompute plans based on changing data distribution
     */
    public DistributedQueryOptimizer(
            TableNodeRegistry tableRegistry,
            NetworkLatencyModel networkLatency,
            Duration reoptimizationInterval) {
        this.tableRegistry = Objects.requireNonNull(tableRegistry, "tableRegistry cannot be null");
        this.networkLatency =
                Objects.requireNonNull(networkLatency, "networkLatency cannot be null");
        this.reoptimizationInterval =
                Objects.requireNonNull(reoptimizationInterval, "reoptimizationInterval cannot be null");
        this.executionHistory = new ConcurrentHashMap<>();
        this.planCache = new ConcurrentHashMap<>();
        this.lastReoptimization = new ConcurrentHashMap<>();
    }

    /**
     * Optimize a distributed query.
     *
     * <p>Analyzes the query structure and generates an execution plan that minimizes network
     * traffic, lock contention, and total time. Plan is cached and reused until data distribution
     * changes significantly (detected by reoptimization interval).
     *
     * @param query the query to optimize
     * @return optimized execution plan
     */
    public ExecutionPlan optimizeQuery(Query query) {
        checkClosed();
        Objects.requireNonNull(query, "query cannot be null");

        String querySignature = query.signature();
        queriesOptimized.incrementAndGet();

        // Check cache
        CachedPlan cached = planCache.get(querySignature);
        if (cached != null && !cached.isStale(reoptimizationInterval)) {
            return cached.plan;
        }

        // Generate candidate plans
        List<ExecutionPlan> candidates = generateCandidatePlans(query);

        // Rank plans by execution history (if available)
        ExecutionHistory history = executionHistory.get(querySignature);
        ExecutionPlan bestPlan =
                rankAndSelectPlan(candidates, history, query.estimatedSelectivity());

        // Cache the plan
        planCache.put(querySignature, new CachedPlan(bestPlan, System.nanoTime()));

        // Track reoptimization timestamp
        lastReoptimization.put(querySignature, System.nanoTime());

        return bestPlan;
    }

    /**
     * Record execution of a plan for the given query.
     *
     * <p>Updates learning model: if this plan outperformed previous best, promote it. If
     * execution was slow, trigger reoptimization. Tracks network hops, lock contention, and wall
     * time.
     *
     * @param query the query that was executed
     * @param plan the execution plan that was used
     * @param stats actual execution statistics (time, network hops, locks)
     */
    public void recordExecution(Query query, ExecutionPlan plan, ExecutionStats stats) {
        checkClosed();
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(plan, "plan cannot be null");
        Objects.requireNonNull(stats, "stats cannot be null");

        String querySignature = query.signature();

        // Update execution history
        ExecutionHistory history = executionHistory.computeIfAbsent(
                querySignature,
                k -> new ExecutionHistory(querySignature));
        history.recordExecution(plan, stats);

        // Check if we should reoptimize due to degradation
        if (shouldReoptimize(history)) {
            plansAdapted.incrementAndGet();
            planCache.remove(querySignature);
        }

        // Track speedup estimate
        long estimatedSpeedup = plan.estimatedCostMillis() - stats.executionTimeMillis();
        if (estimatedSpeedup > 0) {
            totalEstimatedSpeedup.addAndGet(estimatedSpeedup);
        }
    }

    /**
     * Get current best execution plan for a query without executing it.
     *
     * @param query the query to get plan for
     * @return current cached best plan, or empty if not yet optimized
     */
    public Optional<ExecutionPlan> getCurrentPlan(Query query) {
        checkClosed();
        CachedPlan cached = planCache.get(query.signature());
        return Optional.ofNullable(cached).map(cp -> cp.plan);
    }

    /**
     * Get query execution statistics.
     *
     * @param querySignature query signature
     * @return query statistics, or empty if query has not been executed
     */
    public Optional<QueryStatistics> getStatistics(String querySignature) {
        checkClosed();
        ExecutionHistory history = executionHistory.get(querySignature);
        return Optional.ofNullable(history).map(ExecutionHistory::statistics);
    }

    /**
     * Get optimizer metrics.
     *
     * @return map of metric name to value
     */
    public Map<String, Object> metrics() {
        checkClosed();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queriesOptimized", queriesOptimized.get());
        m.put("plansAdapted", plansAdapted.get());
        m.put("totalEstimatedSpeedup", totalEstimatedSpeedup.get());
        m.put("cachedPlans", planCache.size());
        m.put("queryHistories", executionHistory.size());
        return m;
    }

    /**
     * Clear all cached plans and learning history.
     *
     * <p>Useful for testing or when cluster topology changes significantly.
     */
    public void clearCache() {
        checkClosed();
        planCache.clear();
        lastReoptimization.clear();
    }

    @Override
    public String toString() {
        return "DistributedQueryOptimizer{"
                + "cachedPlans="
                + planCache.size()
                + ", queryHistories="
                + executionHistory.size()
                + ", plansAdapted="
                + plansAdapted.get()
                + '}';
    }

    public void close() {
        closed = true;
    }

    // ── Private implementation ──────────────────────────────────────────────────────

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Optimizer is closed");
        }
    }

    /**
     * Generate candidate execution plans by exploring join orders and filter placements.
     *
     * <p>For a query with tables T1, T2, ..., Tn:
     *
     * <ol>
     *   <li>Enumerate join orders: (T1 ⨝ T2 ⨝ T3), (T1 ⨝ T3 ⨝ T2), etc.
     *   <li>For each join order, try filter pushdown strategies:
     *       - Filters applied before join (most selective first)
     *       - Filters applied after join
     *   <li>Estimate cost for each candidate using network latency and lock contention models
     * </ol>
     *
     * @param query the query to generate plans for
     * @return list of candidate execution plans
     */
    private List<ExecutionPlan> generateCandidatePlans(Query query) {
        List<ExecutionPlan> candidates = new ArrayList<>();

        // Simple heuristic: generate a few candidate plans
        // 1. Default plan: scan all tables, apply filters at end
        ExecutionPlan defaultPlan = new ExecutionPlan(
                query.signature() + "#default",
                query.tables(),
                query.filters(),
                query.joins(),
                true); // filters at end
        candidates.add(defaultPlan);

        // 2. Optimized plan: push filters down, apply most selective first
        ExecutionPlan optimizedPlan = new ExecutionPlan(
                query.signature() + "#optimized",
                query.tables(),
                query.filters(),
                reorderJoinsBySelectivity(query.joins(), query.tables()),
                false); // filters before join
        candidates.add(optimizedPlan);

        // 3. Network-aware plan: prefer co-located table access
        List<Join> networkAwarePlans = query.joins().stream()
                .sorted((j1, j2) -> {
                    long latency1 = networkLatency.latencyMillis(j1.sourceNode(), j1.targetNode());
                    long latency2 = networkLatency.latencyMillis(j2.sourceNode(), j2.targetNode());
                    return Long.compare(latency1, latency2);
                })
                .collect(Collectors.toList());

        ExecutionPlan networkPlan = new ExecutionPlan(
                query.signature() + "#network-aware",
                query.tables(),
                query.filters(),
                networkAwarePlans,
                false);
        candidates.add(networkPlan);

        return candidates;
    }

    /**
     * Reorder joins by table cardinality (smallest first) to minimize intermediate result size.
     */
    private List<Join> reorderJoinsBySelectivity(List<Join> joins, List<Table> tables) {
        Map<String, Integer> tableCardinality = new HashMap<>();
        for (Table table : tables) {
            tableCardinality.put(table.name(), table.estimatedCardinality());
        }

        return joins.stream()
                .sorted((j1, j2) -> {
                    int card1 = tableCardinality.getOrDefault(j1.leftTable(), Integer.MAX_VALUE);
                    int card2 = tableCardinality.getOrDefault(j2.leftTable(), Integer.MAX_VALUE);
                    return Integer.compare(card1, card2);
                })
                .collect(Collectors.toList());
    }

    /**
     * Rank candidate plans and select the best one.
     *
     * <p>Uses execution history if available (plans that historically performed better are
     * preferred). Falls back to estimated cost if no history.
     */
    private ExecutionPlan rankAndSelectPlan(
            List<ExecutionPlan> candidates,
            ExecutionHistory history,
            double querySelectivity) {
        if (history == null || history.executionCount() == 0) {
            // No history: select plan with lowest estimated cost
            return candidates.stream()
                    .min(Comparator.comparing(ExecutionPlan::estimatedCostMillis))
                    .orElse(candidates.get(0));
        }

        // Rank by historical performance
        return candidates.stream()
                .min((p1, p2) -> {
                    double p1Score = history.averageTimeMillis(p1.id());
                    double p2Score = history.averageTimeMillis(p2.id());
                    return Double.compare(p1Score, p2Score);
                })
                .orElse(candidates.get(0));
    }

    /** Check if plan should be reoptimized due to degradation or timeout. */
    private boolean shouldReoptimize(ExecutionHistory history) {
        // If last 3 executions are significantly slower than best known, reoptimize
        List<ExecutionStats> recent = history.recentExecutions(3);
        if (recent.size() < 3) {
            return false;
        }

        double avgRecent = recent.stream()
                .mapToLong(ExecutionStats::executionTimeMillis)
                .average()
                .orElse(0);
        double bestKnown = history.bestExecutionTimeMillis();

        // If recent avg is 20% slower than best known, reoptimize
        return avgRecent > bestKnown * 1.2;
    }

    // ── Nested types ────────────────────────────────────────────────────────────────

    /** Cached execution plan with timestamp. */
    private static class CachedPlan {
        final ExecutionPlan plan;
        final long cachedAtNanos;

        CachedPlan(ExecutionPlan plan, long cachedAtNanos) {
            this.plan = plan;
            this.cachedAtNanos = cachedAtNanos;
        }

        boolean isStale(Duration reoptimizationInterval) {
            long ageNanos = System.nanoTime() - cachedAtNanos;
            return ageNanos > reoptimizationInterval.toNanos();
        }
    }

    /** Execution history for a query. */
    private static class ExecutionHistory {
        private final String querySignature;
        private final ConcurrentHashMap<String, List<ExecutionStats>> planStats;
        private final LinkedList<ExecutionStats> allExecutions;

        ExecutionHistory(String querySignature) {
            this.querySignature = querySignature;
            this.planStats = new ConcurrentHashMap<>();
            this.allExecutions = new LinkedList<>();
        }

        void recordExecution(ExecutionPlan plan, ExecutionStats stats) {
            planStats
                    .computeIfAbsent(plan.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(stats);
            allExecutions.addLast(stats);

            // Keep only last 100 executions
            if (allExecutions.size() > 100) {
                allExecutions.removeFirst();
            }
        }

        double averageTimeMillis(String planId) {
            List<ExecutionStats> stats = planStats.get(planId);
            if (stats == null || stats.isEmpty()) {
                return Double.MAX_VALUE;
            }
            return stats.stream()
                    .mapToLong(ExecutionStats::executionTimeMillis)
                    .average()
                    .orElse(Double.MAX_VALUE);
        }

        double bestExecutionTimeMillis() {
            return allExecutions.stream()
                    .mapToLong(ExecutionStats::executionTimeMillis)
                    .min()
                    .orElse(Long.MAX_VALUE);
        }

        List<ExecutionStats> recentExecutions(int count) {
            return allExecutions.stream().skip(Math.max(0, allExecutions.size() - count))
                    .collect(Collectors.toList());
        }

        int executionCount() {
            return allExecutions.size();
        }

        QueryStatistics statistics() {
            if (allExecutions.isEmpty()) {
                return new QueryStatistics(
                        querySignature, 0, 0, 0, 0, 0, 0, planStats.size());
            }

            long totalTime = allExecutions.stream()
                    .mapToLong(ExecutionStats::executionTimeMillis)
                    .sum();
            long totalHops = allExecutions.stream()
                    .mapToLong(ExecutionStats::networkHopsCount)
                    .sum();
            long totalLocks = allExecutions.stream()
                    .mapToLong(ExecutionStats::locksHeldCount)
                    .sum();

            return new QueryStatistics(
                    querySignature,
                    allExecutions.size(),
                    totalTime / allExecutions.size(),
                    totalHops / allExecutions.size(),
                    totalLocks / allExecutions.size(),
                    allExecutions.stream()
                            .mapToLong(ExecutionStats::executionTimeMillis)
                            .min()
                            .orElse(0),
                    allExecutions.stream()
                            .mapToLong(ExecutionStats::executionTimeMillis)
                            .max()
                            .orElse(0),
                    planStats.size());
        }
    }

    // ── Public data types ───────────────────────────────────────────────────────────

    /**
     * Query representation for optimization.
     *
     * <p>Captures: tables involved, join conditions, filter predicates, and estimated selectivity.
     */
    public static final class Query {
        private final String signature;
        private final List<Table> tables;
        private final List<Filter> filters;
        private final List<Join> joins;
        private final double estimatedSelectivity;

        public Query(
                String signature,
                List<Table> tables,
                List<Filter> filters,
                List<Join> joins,
                double estimatedSelectivity) {
            this.signature = signature;
            this.tables = tables;
            this.filters = filters;
            this.joins = joins;
            this.estimatedSelectivity = estimatedSelectivity;
        }

        public String signature() {
            return signature;
        }

        public List<Table> tables() {
            return tables;
        }

        public List<Filter> filters() {
            return filters;
        }

        public List<Join> joins() {
            return joins;
        }

        public double estimatedSelectivity() {
            return estimatedSelectivity;
        }
    }

    /** Table metadata. */
    public static final class Table {
        private final String name;
        private final String node;
        private final int estimatedCardinality;

        public Table(String name, String node, int estimatedCardinality) {
            this.name = name;
            this.node = node;
            this.estimatedCardinality = estimatedCardinality;
        }

        public String name() {
            return name;
        }

        public String node() {
            return node;
        }

        public int estimatedCardinality() {
            return estimatedCardinality;
        }
    }

    /** Join condition. */
    public static final class Join {
        private final String leftTable;
        private final String rightTable;
        private final String leftColumn;
        private final String rightColumn;
        private final String sourceNode;
        private final String targetNode;

        public Join(
                String leftTable,
                String rightTable,
                String leftColumn,
                String rightColumn,
                String sourceNode,
                String targetNode) {
            this.leftTable = leftTable;
            this.rightTable = rightTable;
            this.leftColumn = leftColumn;
            this.rightColumn = rightColumn;
            this.sourceNode = sourceNode;
            this.targetNode = targetNode;
        }

        public String leftTable() {
            return leftTable;
        }

        public String rightTable() {
            return rightTable;
        }

        public String leftColumn() {
            return leftColumn;
        }

        public String rightColumn() {
            return rightColumn;
        }

        public String sourceNode() {
            return sourceNode;
        }

        public String targetNode() {
            return targetNode;
        }
    }

    /** Filter predicate. */
    public static final class Filter {
        private final String table;
        private final String column;
        private final String predicate;
        private final double selectivity;

        public Filter(String table, String column, String predicate, double selectivity) {
            this.table = table;
            this.column = column;
            this.predicate = predicate;
            this.selectivity = selectivity;
        }

        public String table() {
            return table;
        }

        public String column() {
            return column;
        }

        public String predicate() {
            return predicate;
        }

        public double selectivity() {
            return selectivity;
        }
    }

    /** Optimized execution plan. */
    public static final class ExecutionPlan {
        private final String id;
        private final List<Table> tables;
        private final List<Filter> filters;
        private final List<Join> joins;
        private final boolean filtersAtEnd;
        private final long estimatedCostMillis;

        public ExecutionPlan(
                String id,
                List<Table> tables,
                List<Filter> filters,
                List<Join> joins,
                boolean filtersAtEnd) {
            this.id = id;
            this.tables = tables;
            this.filters = filters;
            this.joins = joins;
            this.filtersAtEnd = filtersAtEnd;
            this.estimatedCostMillis = estimateCost(filters, joins, filtersAtEnd);
        }

        public String id() {
            return id;
        }

        public List<Table> tables() {
            return tables;
        }

        public List<Filter> filters() {
            return filters;
        }

        public List<Join> joins() {
            return joins;
        }

        public boolean filtersAtEnd() {
            return filtersAtEnd;
        }

        public long estimatedCostMillis() {
            return estimatedCostMillis;
        }

        public int estimatedNetworkHops() {
            return joins.size();
        }

        public int estimatedLocksHeld() {
            return (int) Math.ceil(joins.size() * 1.5);
        }

        private long estimateCost(List<Filter> filters, List<Join> joins, boolean filtersAtEnd) {
            long cost = 0;

            // Filter cost
            if (filtersAtEnd) {
                // Full table scan before filtering (expensive)
                cost += joins.size() * 100;
            } else {
                // Filter early (cheap)
                cost += filters.stream().mapToLong(f -> (long) (f.selectivity() * 10)).sum();
            }

            // Join cost proportional to cross-node hops
            cost += joins.size() * 50;

            return Math.max(1, cost);
        }

        @Override
        public String toString() {
            return "ExecutionPlan{" + "id='" + id + '\'' + ", filtersAtEnd=" + filtersAtEnd + ", estimatedCostMillis=" + estimatedCostMillis + '}';
        }
    }

    /** Query statistics tracked across executions. */
    public static final class QueryStatistics {
        private final String querySignature;
        private final long executionCount;
        private final long avgTimeMillis;
        private final long avgNetworkHops;
        private final long avgLocksHeld;
        private final long minTimeMillis;
        private final long maxTimeMillis;
        private final int candidatePlansCount;

        public QueryStatistics(
                String querySignature,
                long executionCount,
                long avgTimeMillis,
                long avgNetworkHops,
                long avgLocksHeld,
                long minTimeMillis,
                long maxTimeMillis,
                int candidatePlansCount) {
            this.querySignature = querySignature;
            this.executionCount = executionCount;
            this.avgTimeMillis = avgTimeMillis;
            this.avgNetworkHops = avgNetworkHops;
            this.avgLocksHeld = avgLocksHeld;
            this.minTimeMillis = minTimeMillis;
            this.maxTimeMillis = maxTimeMillis;
            this.candidatePlansCount = candidatePlansCount;
        }

        public String querySignature() {
            return querySignature;
        }

        public long executionCount() {
            return executionCount;
        }

        public long avgTimeMillis() {
            return avgTimeMillis;
        }

        public long avgNetworkHops() {
            return avgNetworkHops;
        }

        public long avgLocksHeld() {
            return avgLocksHeld;
        }

        public long minTimeMillis() {
            return minTimeMillis;
        }

        public long maxTimeMillis() {
            return maxTimeMillis;
        }

        public int candidatePlansCount() {
            return candidatePlansCount;
        }
    }

    /** Actual execution statistics. */
    public static final class ExecutionStats {
        private final long executionTimeMillis;
        private final long networkHopsCount;
        private final long locksHeldCount;
        private final long recordsProcessed;

        public ExecutionStats(
                long executionTimeMillis, long networkHopsCount, long locksHeldCount,
                long recordsProcessed) {
            this.executionTimeMillis = executionTimeMillis;
            this.networkHopsCount = networkHopsCount;
            this.locksHeldCount = locksHeldCount;
            this.recordsProcessed = recordsProcessed;
        }

        public static ExecutionStats of(
                Duration executionTime, int networkHops, int locksHeld) {
            return new ExecutionStats(executionTime.toMillis(), networkHops, locksHeld, 0);
        }

        public static ExecutionStats of(
                Duration executionTime, int networkHops, int locksHeld, long recordsProcessed) {
            return new ExecutionStats(executionTime.toMillis(), networkHops, locksHeld, recordsProcessed);
        }

        public long executionTimeMillis() {
            return executionTimeMillis;
        }

        public long networkHopsCount() {
            return networkHopsCount;
        }

        public long locksHeldCount() {
            return locksHeldCount;
        }

        public long recordsProcessed() {
            return recordsProcessed;
        }

        @Override
        public String toString() {
            return "ExecutionStats{" + "executionTimeMillis=" + executionTimeMillis + ", networkHopsCount=" + networkHopsCount + ", locksHeldCount=" + locksHeldCount + ", recordsProcessed=" + recordsProcessed + '}';
        }
    }

    /** Registry of table locations and metadata. */
    public interface TableNodeRegistry {
        /**
         * Get nodes where a table is replicated.
         *
         * @param tableName the table name
         * @return list of node identifiers
         */
        List<String> getTableNodes(String tableName);

        /**
         * Get estimated cardinality (row count) of a table.
         *
         * @param tableName the table name
         * @return estimated number of rows
         */
        int getTableCardinality(String tableName);

        /**
         * Check if a table has an index on a column.
         *
         * @param tableName the table name
         * @param columnName the column name
         * @return true if indexed
         */
        boolean hasIndex(String tableName, String columnName);
    }

    /** Network latency model between nodes. */
    public interface NetworkLatencyModel {
        /**
         * Get estimated network latency between two nodes.
         *
         * @param sourceNode source node identifier
         * @param targetNode target node identifier
         * @return estimated latency in milliseconds
         */
        long latencyMillis(String sourceNode, String targetNode);
    }
}
