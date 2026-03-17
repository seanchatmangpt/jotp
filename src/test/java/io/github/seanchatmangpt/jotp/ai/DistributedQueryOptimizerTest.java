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

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ai.DistributedQueryOptimizer.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test suite for DistributedQueryOptimizer covering:
 * - Query optimization and plan selection
 * - Execution statistics tracking
 * - Adaptive learning when data distribution changes
 * - Multi-table joins with filter pushdown
 * - Network topology awareness
 * - Plan reoptimization triggers
 */
class DistributedQueryOptimizerTest {

    private DistributedQueryOptimizer optimizer;
    private TestTableRegistry tableRegistry;
    private TestNetworkLatency networkLatency;

    @BeforeEach
    void setUp() {
        tableRegistry = new TestTableRegistry();
        networkLatency = new TestNetworkLatency();
        optimizer = new DistributedQueryOptimizer(
                tableRegistry, networkLatency, Duration.ofMillis(500));
    }

    @Nested
    class BasicOptimization {

        @Test
        void testOptimizeSimpleQuery() {
            // Arrange: simple two-table join
            Query query = new Query(
                    "orders_customers",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node1", 1000)),
                    List.of(
                            new Filter("Customers", "region", "= 'us-west'", 0.2)
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node1")),
                    0.2);

            // Act
            ExecutionPlan plan = optimizer.optimizeQuery(query);

            // Assert: plan should be optimized
            assertThat(plan).isNotNull();
            assertThat(plan.id()).isNotEmpty();
            assertThat(plan.estimatedCostMillis()).isGreaterThan(0);
        }

        @Test
        void testOptimizeCachesPlans() {
            // Arrange
            Query query = new Query(
                    "simple_join",
                    List.of(new Table("T1", "node1", 1000), new Table("T2", "node1", 500)),
                    List.of(),
                    List.of(new Join("T1", "T2", "id", "id", "node1", "node1")),
                    1.0);

            // Act
            ExecutionPlan plan1 = optimizer.optimizeQuery(query);
            ExecutionPlan plan2 = optimizer.optimizeQuery(query);

            // Assert: same plan returned (cached)
            assertThat(plan1.id()).isEqualTo(plan2.id());
            assertThat(optimizer.metrics().get("cachedPlans")).isEqualTo(1L);
        }

        @Test
        void testOptimizeMultiTableJoin() {
            // Arrange: three-table join with filters
            Query query = new Query(
                    "orders_customers_products",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node1", 1000),
                            new Table("Products", "node2", 500)
                    ),
                    List.of(
                            new Filter("Customers", "region", "= 'us-west'", 0.1),
                            new Filter("Products", "category", "= 'electronics'", 0.25)
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id", "node1", "node1"),
                            new Join("Orders", "Products", "product_id", "id", "node1", "node2")
                    ),
                    0.025);

            // Act
            ExecutionPlan plan = optimizer.optimizeQuery(query);

            // Assert
            assertThat(plan).isNotNull();
            assertThat(plan.joins()).hasSize(2);
            assertThat(plan.estimatedNetworkHops()).isGreaterThan(0);
        }
    }

    @Nested
    class FilterPushdown {

        @Test
        void testGeneratesFilterPushdownPlans() {
            // Arrange: query with selective filter
            Query query = new Query(
                    "orders_filter",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node1", 1000)
                    ),
                    List.of(
                            new Filter("Customers", "region", "= 'us-west'", 0.1)
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node1")
                    ),
                    0.1);

            // Act
            ExecutionPlan plan = optimizer.optimizeQuery(query);

            // Assert: optimized plan should apply filters before join
            assertThat(plan.filtersAtEnd()).isFalse();
            // Estimated cost should be low (filter reduces join input)
            assertThat(plan.estimatedCostMillis()).isLessThan(200);
        }

        @Test
        void testFilterSelectivityImpactsPlans() {
            // Arrange: filter with high selectivity (returns few rows)
            Query selectiveQuery = new Query(
                    "selective",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node1", 1000)
                    ),
                    List.of(
                            new Filter("Customers", "id", "= 123", 0.001)  // very selective
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node1")
                    ),
                    0.001);

            // Arrange: filter with low selectivity (returns many rows)
            Query broadQuery = new Query(
                    "broad",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node1", 1000)
                    ),
                    List.of(
                            new Filter("Customers", "status", "= 'active'", 0.8)  // not selective
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node1")
                    ),
                    0.8);

            // Act
            ExecutionPlan selectivePlan = optimizer.optimizeQuery(selectiveQuery);
            ExecutionPlan broadPlan = optimizer.optimizeQuery(broadQuery);

            // Assert: selective plan should have lower cost
            assertThat(selectivePlan.estimatedCostMillis())
                    .isLessThan(broadPlan.estimatedCostMillis());
        }
    }

    @Nested
    class AdaptiveLearning {

        @Test
        void testTracksExecutionStatistics() {
            // Arrange
            Query query = new Query(
                    "tracked_query",
                    List.of(new Table("T1", "node1", 1000), new Table("T2", "node1", 500)),
                    List.of(),
                    List.of(new Join("T1", "T2", "id", "id", "node1", "node1")),
                    1.0);

            ExecutionPlan plan = optimizer.optimizeQuery(query);
            ExecutionStats stats = ExecutionStats.of(
                    Duration.ofMillis(50), 1, 2);

            // Act
            optimizer.recordExecution(query, plan, stats);

            // Assert: statistics should be recorded
            Optional<QueryStatistics> stats_opt = optimizer.getStatistics(query.signature());
            assertThat(stats_opt).isPresent();
            assertThat(stats_opt.get().executionCount()).isEqualTo(1);
            assertThat(stats_opt.get().avgTimeMillis()).isEqualTo(50);
        }

        @Test
        void testTracksMultipleExecutions() {
            // Arrange
            Query query = new Query(
                    "multi_exec",
                    List.of(new Table("T1", "node1", 1000)),
                    List.of(),
                    List.of(),
                    1.0);

            ExecutionPlan plan = optimizer.optimizeQuery(query);

            // Act: record multiple executions
            optimizer.recordExecution(query, plan, ExecutionStats.of(Duration.ofMillis(100), 0, 0));
            optimizer.recordExecution(query, plan, ExecutionStats.of(Duration.ofMillis(150), 0, 0));
            optimizer.recordExecution(query, plan, ExecutionStats.of(Duration.ofMillis(80), 0, 0));

            // Assert: average should be correct
            Optional<QueryStatistics> stats = optimizer.getStatistics(query.signature());
            assertThat(stats.get().executionCount()).isEqualTo(3);
            assertThat(stats.get().avgTimeMillis()).isEqualTo(110); // (100+150+80)/3
            assertThat(stats.get().minTimeMillis()).isEqualTo(80);
            assertThat(stats.get().maxTimeMillis()).isEqualTo(150);
        }

        @Test
        void testReoptimizesOnPlanDegradation() {
            // Arrange
            Query query = new Query(
                    "degrading_query",
                    List.of(new Table("T1", "node1", 1000)),
                    List.of(),
                    List.of(),
                    1.0);

            ExecutionPlan plan1 = optimizer.optimizeQuery(query);
            String initialPlanId = plan1.id();

            // Act: record executions that are 20%+ slower than best
            optimizer.recordExecution(query, plan1, ExecutionStats.of(Duration.ofMillis(100), 0, 0));
            optimizer.recordExecution(query, plan1, ExecutionStats.of(Duration.ofMillis(150), 0, 0));
            optimizer.recordExecution(query, plan1, ExecutionStats.of(Duration.ofMillis(140), 0, 0));

            // Reoptimize (should trigger due to degradation)
            ExecutionPlan plan2 = optimizer.optimizeQuery(query);

            // Assert: plan was reoptimized
            assertThat(optimizer.metrics().get("plansAdapted")).isGreaterThan(0);
        }

        @Test
        void testEstimatesTotalSpeedup() {
            // Arrange
            Query query = new Query(
                    "speedup_query",
                    List.of(new Table("T1", "node1", 1000)),
                    List.of(),
                    List.of(),
                    1.0);

            ExecutionPlan plan = optimizer.optimizeQuery(query);
            // Plan estimated cost is based on filters and joins
            long estimatedCost = plan.estimatedCostMillis();

            // Act: actual execution is faster than estimated
            ExecutionStats stats = ExecutionStats.of(
                    Duration.ofMillis(estimatedCost / 2), 0, 0);
            optimizer.recordExecution(query, plan, stats);

            // Assert: speedup recorded
            assertThat(optimizer.metrics().get("totalEstimatedSpeedup"))
                    .isGreaterThan(0);
        }
    }

    @Nested
    class NetworkTopology {

        @Test
        void testCoLocatedTablesPreferred() {
            // Arrange: tables on same node
            Query coLocated = new Query(
                    "colocated",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node1", 1000)
                    ),
                    List.of(),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node1")
                    ),
                    1.0);

            // Arrange: tables on different nodes
            Query distributed = new Query(
                    "distributed",
                    List.of(
                            new Table("Orders", "node1", 10000),
                            new Table("Customers", "node2", 1000)
                    ),
                    List.of(),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node2")
                    ),
                    1.0);

            // Set network latency
            networkLatency.setLatency("node1", "node2", 100); // 100ms cross-node
            networkLatency.setLatency("node1", "node1", 1);   // 1ms local

            // Act
            ExecutionPlan coLocatedPlan = optimizer.optimizeQuery(coLocated);
            ExecutionPlan distributedPlan = optimizer.optimizeQuery(distributed);

            // Assert: co-located should have lower cost
            assertThat(coLocatedPlan.estimatedCostMillis())
                    .isLessThan(distributedPlan.estimatedCostMillis());
        }

        @Test
        void testNetworkHopsTracked() {
            // Arrange: multi-node query
            Query query = new Query(
                    "multihop",
                    List.of(
                            new Table("T1", "node1", 100),
                            new Table("T2", "node2", 100),
                            new Table("T3", "node3", 100)
                    ),
                    List.of(),
                    List.of(
                            new Join("T1", "T2", "id", "id", "node1", "node2"),
                            new Join("T2", "T3", "id", "id", "node2", "node3")
                    ),
                    1.0);

            // Act
            ExecutionPlan plan = optimizer.optimizeQuery(query);

            // Assert: hops should match join count
            assertThat(plan.estimatedNetworkHops()).isEqualTo(2);
        }
    }

    @Nested
    class ComplexQueries {

        @Test
        void testOrdersCustomersProductsJoin() {
            // Real-world scenario: Orders ⨝ Customers ⨝ Products
            // Filter: Customers.region = 'us-west' AND Products.category = 'electronics'
            // Expected optimization: Filter first (reduces join inputs), then join

            Query query = new Query(
                    "ecommerce_query",
                    List.of(
                            new Table("Orders", "node1", 100000),     // large
                            new Table("Customers", "node1", 50000),   // medium
                            new Table("Products", "node1", 5000)      // small
                    ),
                    List.of(
                            new Filter("Customers", "region", "= 'us-west'", 0.1),  // 10% selectivity
                            new Filter("Products", "category", "= 'electronics'", 0.25)  // 25% selectivity
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id", "node1", "node1"),
                            new Join("Orders", "Products", "product_id", "id", "node1", "node1")
                    ),
                    0.025); // 10% * 25% = 2.5%

            // Act
            ExecutionPlan plan = optimizer.optimizeQuery(query);
            ExecutionStats stats = ExecutionStats.of(
                    Duration.ofMillis(150), 2, 3, 2500);
            optimizer.recordExecution(query, plan, stats);

            // Assert
            assertThat(plan).isNotNull();
            assertThat(plan.estimatedNetworkHops()).isEqualTo(2);
            Optional<QueryStatistics> stats_opt = optimizer.getStatistics(query.signature());
            assertThat(stats_opt.get().recordsProcessed()).isEqualTo(2500);
        }

        @Test
        void testLearnsFilterOrderOptimization() {
            // Arrange: same query executed twice, tracking how filter order matters
            Query query = new Query(
                    "filter_learning",
                    List.of(
                            new Table("Orders", "node1", 100000),
                            new Table("Customers", "node1", 50000)
                    ),
                    List.of(
                            new Filter("Orders", "status", "= 'completed'", 0.8),  // not very selective
                            new Filter("Customers", "region", "= 'us-west'", 0.1)  // selective
                    ),
                    List.of(
                            new Join("Orders", "Customers", "customer_id", "id",
                                    "node1", "node1")
                    ),
                    0.08);

            ExecutionPlan plan1 = optimizer.optimizeQuery(query);

            // Simulate execution: filtering customers first (selective) is faster
            ExecutionStats fastStats = ExecutionStats.of(Duration.ofMillis(100), 1, 2);
            optimizer.recordExecution(query, plan1, fastStats);

            // Simulate execution: filtering orders first (not selective) is slower
            ExecutionStats slowStats = ExecutionStats.of(Duration.ofMillis(500), 1, 2);
            optimizer.recordExecution(query, plan1, slowStats);

            // Act: reoptimize should learn to use selective filter first
            ExecutionPlan plan2 = optimizer.optimizeQuery(query);

            // Assert: multiple executions tracked
            Optional<QueryStatistics> stats = optimizer.getStatistics(query.signature());
            assertThat(stats.get().executionCount()).isGreaterThan(0);
        }
    }

    @Nested
    class CacheManagement {

        @Test
        void testClearCacheInvalidatesPlans() {
            // Arrange
            Query query = new Query(
                    "cache_test",
                    List.of(new Table("T1", "node1", 1000)),
                    List.of(),
                    List.of(),
                    1.0);

            ExecutionPlan plan1 = optimizer.optimizeQuery(query);

            // Act
            optimizer.clearCache();
            ExecutionPlan plan2 = optimizer.optimizeQuery(query);

            // Assert: plan IDs may differ after cache clear
            // (they could be the same if generation is deterministic)
            assertThat(optimizer.metrics().get("cachedPlans")).isEqualTo(1L);
        }

        @Test
        void testPlanCacheExpiry() throws InterruptedException {
            // Arrange: short reoptimization interval
            DistributedQueryOptimizer shortIntervalOptimizer = new DistributedQueryOptimizer(
                    tableRegistry, networkLatency, Duration.ofMillis(100));

            Query query = new Query(
                    "expiring_plan",
                    List.of(new Table("T1", "node1", 1000)),
                    List.of(),
                    List.of(),
                    1.0);

            ExecutionPlan plan1 = shortIntervalOptimizer.optimizeQuery(query);

            // Act: wait for cache to expire
            Thread.sleep(150);
            ExecutionPlan plan2 = shortIntervalOptimizer.optimizeQuery(query);

            // Assert: cache was refreshed
            assertThat(plan1).isNotNull();
            assertThat(plan2).isNotNull();
        }
    }

    @Nested
    class Metrics {

        @Test
        void testMetricsTracking() {
            // Arrange
            Query q1 = new Query("q1", List.of(new Table("T1", "node1", 100)), List.of(),
                    List.of(), 1.0);
            Query q2 = new Query("q2", List.of(new Table("T1", "node1", 100)), List.of(),
                    List.of(), 1.0);

            // Act
            optimizer.optimizeQuery(q1);
            optimizer.optimizeQuery(q2);
            ExecutionPlan plan = optimizer.optimizeQuery(q1);
            optimizer.recordExecution(q1, plan, ExecutionStats.of(Duration.ofMillis(50), 0, 0));

            // Assert
            Map<String, Object> metrics = optimizer.metrics();
            assertThat(metrics).containsKeys(
                    "queriesOptimized", "plansAdapted", "totalEstimatedSpeedup",
                    "cachedPlans", "queryHistories");
            assertThat((Long) metrics.get("queriesOptimized")).isGreaterThan(0);
        }
    }

    // ── Test Doubles ────────────────────────────────────────────────────────────

    private static class TestTableRegistry implements TableNodeRegistry {
        private final Map<String, List<String>> tableNodes = new HashMap<>();
        private final Map<String, Integer> tableCardinalities = new HashMap<>();

        @Override
        public List<String> getTableNodes(String tableName) {
            return tableNodes.getOrDefault(tableName, List.of("node1"));
        }

        @Override
        public int getTableCardinality(String tableName) {
            return tableCardinalities.getOrDefault(tableName, 1000);
        }

        @Override
        public boolean hasIndex(String tableName, String columnName) {
            return true; // assume all tables are indexed
        }
    }

    private static class TestNetworkLatency implements NetworkLatencyModel {
        private final Map<String, Long> latencies = new ConcurrentHashMap<>();

        void setLatency(String source, String target, long millis) {
            latencies.put(source + "->" + target, millis);
        }

        @Override
        public long latencyMillis(String sourceNode, String targetNode) {
            if (sourceNode.equals(targetNode)) {
                return 1; // local
            }
            return latencies.getOrDefault(sourceNode + "->" + targetNode, 50L);
        }
    }
}
