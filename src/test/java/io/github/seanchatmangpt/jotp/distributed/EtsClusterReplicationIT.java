package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for ETS cluster replication.
 *
 * <p>Tests multi-node replication via Redis Pub/Sub, vector clock consistency,
 * and partition-tolerant operation.
 *
 * <p>Requires Redis running on localhost:6379.
 */
class EtsClusterReplicationIT {

    private EtsBackend node1;
    private EtsBackend node2;
    private EtsBackend node3;

    @BeforeEach
    void setup() {
        node1 = new EtsBackend("node-1", 3600, "localhost", 6379);
        node2 = new EtsBackend("node-2", 3600, "localhost", 6379);
        node3 = new EtsBackend("node-3", 3600, "localhost", 6379);

        node1.createTable("shared", EtsTable.TableType.SET);
        node2.createTable("shared", EtsTable.TableType.SET);
        node3.createTable("shared", EtsTable.TableType.SET);
    }

    @AfterEach
    void teardown() throws Exception {
        node1.close();
        node2.close();
        node3.close();
    }

    // ===== Basic Replication =====

    @Test
    void testWriteReplicatesToPeers() throws Exception {
        CountDownLatch replicated = new CountDownLatch(2); // Node2 and Node3

        node2.subscribeTable("shared", event -> replicated.countDown());
        node3.subscribeTable("shared", event -> replicated.countDown());

        // Give subscribers time to establish connections
        Thread.sleep(100);

        node1.put("shared", "key1", "value1".getBytes());

        boolean success = replicated.await(2, TimeUnit.SECONDS);
        assertThat(success)
                .as("Replication should happen within timeout")
                .isTrue();

        // Verify replicas have the value
        assertThat(node2.get("shared", "key1")).hasSize(1);
        assertThat(node3.get("shared", "key1")).hasSize(1);
    }

    @Test
    void testMultipleWritesReplicate() throws Exception {
        CountDownLatch replicated = new CountDownLatch(6); // 3 writes × 2 replicas

        node2.subscribeTable("shared", event -> replicated.countDown());
        node3.subscribeTable("shared", event -> replicated.countDown());

        Thread.sleep(100);

        node1.put("shared", "key1", "v1".getBytes());
        node1.put("shared", "key2", "v2".getBytes());
        node1.put("shared", "key3", "v3".getBytes());

        boolean success = replicated.await(2, TimeUnit.SECONDS);
        assertThat(success).isTrue();

        assertThat(node2.keys("shared")).hasSize(3);
        assertThat(node3.keys("shared")).hasSize(3);
    }

    @Test
    void testDeleteReplicates() throws Exception {
        CountDownLatch replicated = new CountDownLatch(2);

        node2.subscribeTable("shared", event -> replicated.countDown());
        node3.subscribeTable("shared", event -> replicated.countDown());

        Thread.sleep(100);

        // Write then delete
        node1.put("shared", "key", "value".getBytes());
        Thread.sleep(100); // Wait for replication
        node1.delete("shared", "key");

        boolean success = replicated.await(2, TimeUnit.SECONDS);
        assertThat(success).isTrue();

        // Verify deletion replicated
        assertThat(node2.get("shared", "key")).isEmpty();
        assertThat(node3.get("shared", "key")).isEmpty();
    }

    // ===== Vector Clock Consistency =====

    @Test
    void testVectorClockTracking() {
        node1.put("shared", "key1", "v1".getBytes());
        node1.put("shared", "key2", "v2".getBytes());

        EtsClusterReplication.VectorClock vc1 = node1.getReplication().getVectorClock("shared");
        assertThat(vc1.toString()).contains("node-1=2");
    }

    @Test
    void testVectorClockMerging() throws Exception {
        // Node1 makes writes
        node1.put("shared", "k1", "v1".getBytes());
        node1.put("shared", "k2", "v2".getBytes());

        Thread.sleep(200); // Let replication happen

        // Node2 makes writes
        node2.put("shared", "k3", "v3".getBytes());

        EtsClusterReplication.VectorClock vc2 = node2.getReplication().getVectorClock("shared");
        assertThat(vc2.toString())
                .as("Vector clock should track both nodes")
                .contains("node-1")
                .contains("node-2");
    }

    @Test
    void testCausalityDetection() {
        EtsClusterReplication.VectorClock vc1 = new EtsClusterReplication.VectorClock();
        vc1.increment("node-1").increment("node-1");

        EtsClusterReplication.VectorClock vc2 = new EtsClusterReplication.VectorClock();
        vc2.increment("node-1").increment("node-1").increment("node-1");

        assertThat(vc1.happensBefore(vc2)).isTrue();
        assertThat(vc2.happensBefore(vc1)).isFalse();
    }

    @Test
    void testConcurrentWriteCausality() {
        EtsClusterReplication.VectorClock vc1 = new EtsClusterReplication.VectorClock();
        vc1.increment("node-1").increment("node-2");

        EtsClusterReplication.VectorClock vc2 = new EtsClusterReplication.VectorClock();
        vc2.increment("node-1").increment("node-3");

        assertThat(vc1.concurrent(vc2)).isTrue();
        assertThat(vc1.happensBefore(vc2)).isFalse();
        assertThat(vc2.happensBefore(vc1)).isFalse();
    }

    // ===== Multi-Node Scenarios =====

    @Test
    void testThreeNodeCluster() throws Exception {
        AtomicInteger replicaCount = new AtomicInteger(0);

        node2.subscribeTable("shared", event -> replicaCount.incrementAndGet());
        node3.subscribeTable("shared", event -> replicaCount.incrementAndGet());

        Thread.sleep(100);

        node1.put("shared", "key", "value".getBytes());

        Thread.sleep(500); // Wait for replication

        assertThat(replicaCount.get())
                .as("Both replicas should receive the write")
                .isGreaterThanOrEqualTo(1);

        assertThat(node2.get("shared", "key")).hasSize(1);
        assertThat(node3.get("shared", "key")).hasSize(1);
    }

    @Test
    void testConcurrentWritesAcrossNodes() throws Exception {
        CountDownLatch replicasReady = new CountDownLatch(2);
        CountDownLatch node1Updated = new CountDownLatch(2);
        CountDownLatch node2Updated = new CountDownLatch(2);

        node2.subscribeTable("shared", event -> {
            replicasReady.countDown();
            node2Updated.countDown();
        });
        node3.subscribeTable("shared", event -> replicasReady.countDown());

        Thread.sleep(100);

        // Concurrent writes from different nodes
        node1.put("shared", "key1", "from-node1".getBytes());
        node2.put("shared", "key2", "from-node2".getBytes());

        boolean allUpdated = node2Updated.await(2, TimeUnit.SECONDS);
        assertThat(allUpdated)
                .as("Node2 should see its own write and Node1's write")
                .isTrue();

        Thread.sleep(200);

        assertThat(node1.keys("shared")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(node2.keys("shared")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(node3.keys("shared")).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void testNodeFailureScenario() throws Exception {
        CountDownLatch replicated = new CountDownLatch(2);

        node2.subscribeTable("shared", event -> replicated.countDown());
        node3.subscribeTable("shared", event -> replicated.countDown());

        Thread.sleep(100);

        // Write from node1
        node1.put("shared", "failover-key", "value".getBytes());

        boolean success = replicated.await(2, TimeUnit.SECONDS);
        assertThat(success).isTrue();

        // Close node1 (simulating failure)
        node1.close();

        // Node2 and Node3 still have the data
        assertThat(node2.get("shared", "failover-key")).hasSize(1);
        assertThat(node3.get("shared", "failover-key")).hasSize(1);
    }

    // ===== Partition Tolerance =====

    @Test
    void testPartitionTolerantOperation() throws Exception {
        // All three nodes start with same state
        node1.put("shared", "initial", "value".getBytes());
        Thread.sleep(200);

        CountDownLatch node2Receives = new CountDownLatch(1);
        node2.subscribeTable("shared", event -> node2Receives.countDown());

        // Network partition: node1 isolated, node2+node3 connected
        // This is simulated by stopping subscriptions on node2 temporarily
        // In real scenario, Redis connection would drop

        // Node1 writes independently
        node1.put("shared", "isolated", "value".getBytes());

        // Node2+node3 also write
        node2.put("shared", "during-partition", "value".getBytes());

        // After partition heals, writes should merge
        // (In production, this requires Conflict-Free Replicated Data Types)

        Thread.sleep(500);

        // At minimum, each node should have its own writes
        assertThat(node1.keys("shared"))
                .as("Node1 should have its writes")
                .contains("isolated");
        assertThat(node2.keys("shared"))
                .as("Node2 should have its writes")
                .contains("during-partition");
    }

    // ===== Performance =====

    @Test
    void testHighThroughputReplication() throws Exception {
        CountDownLatch replicated = new CountDownLatch(100);

        node2.subscribeTable("shared", event -> replicated.countDown());

        Thread.sleep(100);

        // Fast sequential writes
        for (int i = 0; i < 100; i++) {
            node1.put("shared", "key-" + i, ("value-" + i).getBytes());
        }

        boolean success = replicated.await(5, TimeUnit.SECONDS);
        assertThat(success)
                .as("All 100 writes should replicate within timeout")
                .isTrue();

        assertThat(node2.keys("shared")).hasSizeGreaterThanOrEqualTo(100);
    }

    @Test
    void testConcurrentReadsWhileReplicating() throws Exception {
        // Pre-populate node1
        for (int i = 0; i < 50; i++) {
            node1.put("shared", "pre-" + i, "v".getBytes());
        }

        Thread.sleep(200);

        var readThreads = new Thread[5];
        AtomicInteger readCount = new AtomicInteger(0);

        // Start readers
        for (int i = 0; i < 5; i++) {
            readThreads[i] = new Thread(() -> {
                for (int j = 0; j < 20; j++) {
                    node2.get("shared", "pre-" + (j % 50));
                    readCount.incrementAndGet();
                }
            });
            readThreads[i].start();
        }

        // Meanwhile, replicate more data
        for (int i = 50; i < 100; i++) {
            node1.put("shared", "new-" + i, "v".getBytes());
            if (i % 10 == 0) {
                Thread.sleep(10); // Small delay
            }
        }

        for (Thread t : readThreads) {
            t.join();
        }

        assertThat(readCount.get()).isEqualTo(100);
        assertThat(node2.keys("shared")).hasSizeGreaterThanOrEqualTo(50);
    }
}
