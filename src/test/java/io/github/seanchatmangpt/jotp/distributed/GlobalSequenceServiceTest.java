package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GlobalSequenceService}.
 *
 * <p>Verifies distributed sequence generation with Hybrid Logical Clock (HLC) semantics, ensuring
 * global uniqueness, monotonicity, and clock drift resilience.
 */
@DtrTest
@DisplayName("GlobalSequenceService — OTP distributed sequence generation")
class GlobalSequenceServiceTest {

    @DtrContextField private DtrContext ctx;

    private GlobalSequenceService seqService;
    private StaticNodeDiscovery discovery;

    @BeforeEach
    void setUp() {
        var backend = new InMemoryNodeDiscoveryBackend();
        discovery =
                new StaticNodeDiscovery(
                        "node1",
                        List.of("node1", "node2", "node3"),
                        Map.of(
                                "node1",
                                "localhost:8080",
                                "node2",
                                "localhost:8081",
                                "node3",
                                "localhost:8082"),
                        backend,
                        Duration.ofMillis(100),
                        Duration.ofMillis(200),
                        Duration.ofMillis(300));
        seqService = GlobalSequenceService.create("node1", discovery);
    }

    @AfterEach
    void tearDown() {
        if (discovery != null) {
            discovery.shutdown();
        }
    }

    // ==================== Basic Sequence Generation ====================

    @Test
    @DisplayName("Should generate unique sequence numbers")
    void nextGlobalSeq_generatesUniqueSequences(DtrContext ctx) {
        ctx.say("GlobalSequenceService generates globally unique sequence numbers.");
        ctx.say("Each call to nextGlobalSeq() returns a monotonically increasing value.");
        ctx.say(
                "The HLC algorithm combines physical time, node ID, and logical counter to guarantee uniqueness.");

        Set<Long> sequences = new HashSet<>();
        int count = 1000;

        for (int i = 0; i < count; i++) {
            long seq = seqService.nextGlobalSeq();
            sequences.add(seq);
        }

        assertThat(sequences).hasSize(count);
    }

    @Test
    @DisplayName("Should generate monotonically increasing sequences")
    void nextGlobalSeq_generatesMonotonicSequences(DtrContext ctx) {
        ctx.say("Sequence numbers are strictly monotonically increasing.");
        ctx.say("Each subsequent call returns a value greater than the previous one.");
        ctx.say("This property is critical for ordering events in distributed systems.");

        long previous = Long.MIN_VALUE;
        int count = 1000;

        for (int i = 0; i < count; i++) {
            long current = seqService.nextGlobalSeq();
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("Should update high-water mark")
    void nextGlobalSeq_updatesHighWaterMark(DtrContext ctx) {
        ctx.say("High-water mark tracks the highest sequence number generated.");
        ctx.say("After generating sequences, currentHighWaterMark() returns the maximum value.");
        ctx.say("This enables cross-node coordination and conflict detection.");

        long initialMark = seqService.currentHighWaterMark();
        assertThat(initialMark).isEqualTo(0);

        long seq1 = seqService.nextGlobalSeq();
        assertThat(seqService.currentHighWaterMark()).isGreaterThanOrEqualTo(seq1);

        long seq2 = seqService.nextGlobalSeq();
        assertThat(seqService.currentHighWaterMark()).isGreaterThanOrEqualTo(seq2);

        long seq3 = seqService.nextGlobalSeq();
        assertThat(seqService.currentHighWaterMark()).isGreaterThanOrEqualTo(seq3);
    }

    @Test
    @DisplayName("Should pack sequence number components correctly")
    void nextGlobalSeq_packsComponentsCorrectly(DtrContext ctx) {
        ctx.say("HLC sequence numbers use [timestamp:48][nodeId:16][counter:16] format.");
        ctx.say("Each component can be extracted using utility methods for debugging.");
        ctx.say("This format provides ~285K year range, 65K nodes, and 65K sequences/ms per node.");

        long seq = seqService.nextGlobalSeq();

        long timestamp = HybridLogicalClockSequenceService.extractTimestampFromSeq(seq);
        int nodeId = HybridLogicalClockSequenceService.extractNodeIdHashFromSeq(seq);
        int counter = HybridLogicalClockSequenceService.extractCounterFromSeq(seq);

        // Verify components are within valid ranges
        assertThat(timestamp).isPositive();
        assertThat(nodeId).isBetween(0, 65535);
        assertThat(counter).isBetween(0, 65535);

        // Verify reconstruction matches original
        long reconstructed =
                ((timestamp & 0xFFFFFFFFFFFFL) << 32)
                        | ((nodeId & 0xFFFFL) << 16)
                        | (counter & 0xFFFFL);
        assertThat(reconstructed).isEqualTo(seq);
    }

    // ==================== Clock Drift Handling ====================

    @Test
    @DisplayName("Should handle normal clock progression")
    void nextGlobalSeq_handlesNormalClockProgression(DtrContext ctx) {
        ctx.say("Under normal conditions, physical time advances between calls.");
        ctx.say("The HLC timestamp updates to current time and counter resets to 0.");
        ctx.say("This provides natural time-based ordering for distributed events.");

        long seq1 = seqService.nextGlobalSeq();
        long time1 = HybridLogicalClockSequenceService.extractTimestampFromSeq(seq1);
        int counter1 = HybridLogicalClockSequenceService.extractCounterFromSeq(seq1);

        // Wait a bit to ensure time advances
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long seq2 = seqService.nextGlobalSeq();
        long time2 = HybridLogicalClockSequenceService.extractTimestampFromSeq(seq2);
        int counter2 = HybridLogicalClockSequenceService.extractCounterFromSeq(seq2);

        // Time should have advanced or stayed same, counter may have reset
        assertThat(time2).isGreaterThanOrEqualTo(time1);
        assertThat(seq2).isGreaterThan(seq1);
    }

    @Test
    @DisplayName("Should handle rapid same-millisecond sequences")
    void nextGlobalSeq_handlesSameMillisecond(DtrContext ctx) {
        ctx.say(
                "When multiple sequences are generated in the same millisecond, the counter increments.");
        ctx.say("This supports high-throughput scenarios with 65K sequences per ms per node.");
        ctx.say("The timestamp remains constant while the counter provides uniqueness.");

        List<Long> sequences = new ArrayList<>();
        int count = 100;

        // Generate sequences rapidly (likely same millisecond)
        for (int i = 0; i < count; i++) {
            sequences.add(seqService.nextGlobalSeq());
        }

        // All should be unique
        assertThat(sequences).doesNotHaveDuplicates();

        // All should be monotonically increasing
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isGreaterThan(sequences.get(i - 1));
        }

        // Extract components to verify counter increment behavior
        long firstSeq = sequences.get(0);
        long lastSeq = sequences.get(sequences.size() - 1);

        long firstTime = HybridLogicalClockSequenceService.extractTimestampFromSeq(firstSeq);
        long lastTime = HybridLogicalClockSequenceService.extractTimestampFromSeq(lastSeq);
        int lastCounter = HybridLogicalClockSequenceService.extractCounterFromSeq(lastSeq);

        // Time should be same or advanced
        assertThat(lastTime).isGreaterThanOrEqualTo(firstTime);

        // Counter should have incremented significantly
        assertThat(lastCounter).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle counter overflow at 65,536")
    void nextGlobalSeq_handlesCounterOverflow(DtrContext ctx) {
        ctx.say("The 16-bit counter overflows after 65,536 sequences per millisecond.");
        ctx.say("When overflow occurs, HLC advances time by 1ms and resets counter.");
        ctx.say("This is extremely rare but must be handled correctly for correctness.");

        var impl = (HybridLogicalClockSequenceService) seqService;

        // Generate 70K sequences rapidly to trigger overflow
        List<Long> sequences = new ArrayList<>();
        Set<Long> uniqueCheck = new HashSet<>();
        int count = 70000;

        for (int i = 0; i < count; i++) {
            long seq = seqService.nextGlobalSeq();
            sequences.add(seq);
            uniqueCheck.add(seq);
        }

        // All sequences should be unique
        assertThat(uniqueCheck).hasSize(count);

        // Verify all are monotonically increasing
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isGreaterThan(sequences.get(i - 1));
        }
    }

    @Test
    @DisplayName("Should extract HLC components for monitoring")
    void getHlcComponents_returnsCurrentState(DtrContext ctx) {
        ctx.say("HLC maintains separate timestamp and counter components.");
        ctx.say("These can be accessed for monitoring and debugging via getCurrentHlcTimestamp().");
        ctx.say("The counter component is accessible via getCurrentHlcCounter().");

        var impl = (HybridLogicalClockSequenceService) seqService;

        // Generate some sequences
        seqService.nextGlobalSeq();
        seqService.nextGlobalSeq();
        seqService.nextGlobalSeq();

        long hlcTimestamp = impl.getCurrentHlcTimestamp();
        int hlcCounter = impl.getCurrentHlcCounter();

        assertThat(hlcTimestamp).isPositive();
        assertThat(hlcCounter).isGreaterThanOrEqualTo(0);
    }

    // ==================== Cross-Node Coordination ====================

    @Test
    @DisplayName("Should synchronize with empty peer list")
    void synchronizeWithPeers_handlesEmptyList(DtrContext ctx) {
        ctx.say("Synchronization with an empty peer list is valid for single-node clusters.");
        ctx.say("The service ensures its HLC is at least at current physical time.");
        ctx.say("This provides a safe baseline for sequence generation.");

        assertThatCode(() -> seqService.synchronizeWithPeers(List.of())).doesNotThrowAnyException();

        // Verify service still works after sync
        long seq = seqService.nextGlobalSeq();
        assertThat(seq).isNotZero();
    }

    @Test
    @DisplayName("Should synchronize with peer nodes")
    void synchronizeWithPeers_updatesState(DtrContext ctx) {
        ctx.say("Synchronization ensures this node's HLC is aware of cluster state.");
        ctx.say("On startup, nodes sync to prevent sequence collisions after restart.");
        ctx.say("The HLC is advanced to at least current physical time.");

        var impl = (HybridLogicalClockSequenceService) seqService;

        long beforeTimestamp = impl.getCurrentHlcTimestamp();

        // Simulate peer synchronization
        List<String> peers = List.of("node2", "node3");
        seqService.synchronizeWithPeers(peers);

        long afterTimestamp = impl.getCurrentHlcTimestamp();

        // Timestamp should be at least at current time
        assertThat(afterTimestamp).isGreaterThanOrEqualTo(beforeTimestamp);

        // Service should still work
        long seq = seqService.nextGlobalSeq();
        assertThat(seq).isNotZero();
    }

    @Test
    @DisplayName("Should handle null peer list with exception")
    void synchronizeWithPeers_handlesNullList(DtrContext ctx) {
        ctx.say("Synchronization requires a non-null peer list for safety.");
        ctx.say("Passing null throws NullPointerException to fail fast.");
        ctx.say("This prevents subtle bugs in distributed coordination logic.");

        assertThatThrownBy(() -> seqService.synchronizeWithPeers(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("peerNodes must not be null");
    }

    @Test
    @DisplayName("Should maintain high-water mark across synchronization")
    void currentHighWaterMark_reflectsSynchronization(DtrContext ctx) {
        ctx.say("High-water mark tracks the highest sequence known to this node.");
        ctx.say("After synchronization, it reflects the cluster state.");
        ctx.say("This enables conflict detection and recovery coordination.");

        long seq1 = seqService.nextGlobalSeq();
        long mark1 = seqService.currentHighWaterMark();
        assertThat(mark1).isGreaterThanOrEqualTo(seq1);

        // Synchronize
        seqService.synchronizeWithPeers(List.of("node2"));

        long mark2 = seqService.currentHighWaterMark();
        assertThat(mark2).isGreaterThanOrEqualTo(mark1);

        // Generate more sequences
        long seq2 = seqService.nextGlobalSeq();
        assertThat(seqService.currentHighWaterMark()).isGreaterThanOrEqualTo(seq2);
    }

    // ==================== Concurrency ====================

    @Test
    @DisplayName("Should handle concurrent sequence generation")
    void nextGlobalSeq_handlesConcurrency(DtrContext ctx) throws InterruptedException {
        ctx.say("Multiple threads can generate sequences concurrently without coordination.");
        ctx.say("The HLC algorithm uses CAS loops to ensure thread safety.");
        ctx.say("All generated sequences are unique despite concurrent access.");

        int threadCount = 10;
        int sequencesPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Long> allSequences = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < sequencesPerThread; j++) {
                                long seq = seqService.nextGlobalSeq();
                                if (!allSequences.add(seq)) {
                                    duplicateCount.incrementAndGet();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(duplicateCount.get()).isEqualTo(0);
        assertThat(allSequences).hasSize(threadCount * sequencesPerThread);
    }

    @Test
    @DisplayName("Should maintain monotonicity under concurrent load")
    void nextGlobalSeq_maintainsMonotonicityUnderConcurrency(DtrContext ctx)
            throws InterruptedException {
        ctx.say("Each thread observes locally monotonic sequence generation.");
        ctx.say("Global monotonicity is maintained across all threads via CAS.");
        ctx.say("This property is critical for distributed ordering consistency.");

        int threadCount = 8;
        int sequencesPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<List<Long>> threadSequences = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            List<Long> sequences = new ArrayList<>();
            threadSequences.add(sequences);

            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < sequencesPerThread; j++) {
                                sequences.add(seqService.nextGlobalSeq());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Verify each thread's sequences are monotonic
        for (List<Long> sequences : threadSequences) {
            for (int i = 1; i < sequences.size(); i++) {
                assertThat(sequences.get(i)).isGreaterThan(sequences.get(i - 1));
            }
        }
    }

    @Test
    @DisplayName("Should handle high-throughput concurrent generation")
    void nextGlobalSeq_handlesHighThroughput(DtrContext ctx) throws InterruptedException {
        ctx.say("The service supports high-throughput scenarios with many concurrent threads.");
        ctx.say("100K+ sequences per second is achievable with virtual threads.");
        ctx.say("This performance enables use in high-volume distributed systems.");

        int threadCount = 20;
        int sequencesPerThread = 5000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong totalGenerated = new AtomicLong(0);
        Set<Long> uniqueSequences = ConcurrentHashMap.newKeySet();

        long startTime = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < sequencesPerThread; j++) {
                                long seq = seqService.nextGlobalSeq();
                                uniqueSequences.add(seq);
                                totalGenerated.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long duration = System.nanoTime() - startTime;
        double seconds = duration / 1_000_000_000.0;
        double throughput = totalGenerated.get() / seconds;

        assertThat(uniqueSequences).hasSize((int) totalGenerated.get());

        // Log performance for monitoring
        ctx.say(
                String.format(
                        "Generated %d sequences in %.2f seconds", totalGenerated.get(), seconds));
        ctx.say(String.format("Throughput: %.0f sequences/second", throughput));
        ctx.say(String.format("Per-thread: %.0f sequences/second", throughput / threadCount));

        // Verify we achieved reasonable throughput
        assertThat(throughput).isGreaterThan(10000); // At least 10K/sec
    }

    // ==================== Extreme Scenarios ====================

    @Test
    @DisplayName("Should handle rapid sequence generation burst")
    void nextGlobalSeq_handlesRapidBurst(DtrContext ctx) {
        ctx.say("Burst scenarios generate many sequences in rapid succession.");
        ctx.say("The counter handles same-millisecond generation efficiently.");
        ctx.say("100K sequences in a burst should complete quickly without blocking.");

        long startTime = System.nanoTime();
        int count = 100_000;
        Set<Long> sequences = new HashSet<>();

        for (int i = 0; i < count; i++) {
            long seq = seqService.nextGlobalSeq();
            sequences.add(seq);
        }

        long duration = System.nanoTime() - startTime;
        double seconds = duration / 1_000_000_000.0;
        double throughput = count / seconds;

        assertThat(sequences).hasSize(count);

        // Verify reasonable throughput
        assertThat(throughput).isGreaterThan(50000);

        ctx.say(String.format("Burst: %d sequences in %.3f seconds", count, seconds));
        ctx.say(String.format("Burst throughput: %.0f sequences/second", throughput));
    }

    @Test
    @DisplayName("Should handle multiple nodes with unique sequences")
    void nextGlobalSeq_generatesUniqueSequencesAcrossNodes(DtrContext ctx) {
        ctx.say("Each node has a unique node ID hash in its sequence numbers.");
        ctx.say("This ensures global uniqueness even with clock drift.");
        ctx.say("The 16-bit node ID field supports up to 65,536 nodes.");

        var node2 = GlobalSequenceService.create("node2", discovery);
        var node3 = GlobalSequenceService.create("node3", discovery);

        Set<Long> allSequences = new HashSet<>();

        // Generate sequences from each node
        for (int i = 0; i < 100; i++) {
            allSequences.add(seqService.nextGlobalSeq());
            allSequences.add(node2.nextGlobalSeq());
            allSequences.add(node3.nextGlobalSeq());
        }

        // All should be unique
        assertThat(allSequences).hasSize(300);

        // Verify node IDs differ
        long seq1 = seqService.nextGlobalSeq();
        long seq2 = node2.nextGlobalSeq();
        long seq3 = node3.nextGlobalSeq();

        int nodeId1 = HybridLogicalClockSequenceService.extractNodeIdHashFromSeq(seq1);
        int nodeId2 = HybridLogicalClockSequenceService.extractNodeIdHashFromSeq(seq2);
        int nodeId3 = HybridLogicalClockSequenceService.extractNodeIdHashFromSeq(seq3);

        // At least one node ID should be different (hash collisions are possible but rare)
        assertThat(nodeId1 == nodeId2 && nodeId2 == nodeId3 && nodeId3 == nodeId1).isFalse();
    }

    @Test
    @DisplayName("Should maintain monotonicity across many calls")
    void nextGlobalSeq_maintainsMonotonicityOverLongPeriod(DtrContext ctx) {
        ctx.say("Monotonicity is maintained over long sequences of generation.");
        ctx.say("This test verifies the invariant holds across 10K sequences.");
        ctx.say("No sequence should ever be less than or equal to a previous one.");

        long previous = Long.MIN_VALUE;
        int count = 10_000;

        for (int i = 0; i < count; i++) {
            long current = seqService.nextGlobalSeq();
            assertThat(current)
                    .withFailMessage(
                            "Sequence at index %d (%d) is not greater than previous (%d)",
                            i, current, previous)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("Should handle service creation with valid parameters")
    void create_createsValidService(DtrContext ctx) {
        ctx.say("GlobalSequenceService.create() factory creates HLC-based services.");
        ctx.say("The service is initialized with current time and ready to use.");
        ctx.say("Node ID must be unique across the cluster.");

        var service = GlobalSequenceService.create("test-node", discovery);

        assertThat(service).isNotNull();
        // Sequence numbers can be negative due to bit layout (48-bit timestamp shifts)
        // They are still unique and monotonically increasing
        long seq = service.nextGlobalSeq();
        assertThat(seq).isNotZero();
    }

    @Test
    @DisplayName("Should reject null node ID")
    void create_handlesNullNodeId(DtrContext ctx) {
        ctx.say("Node ID is required for unique sequence generation.");
        ctx.say("Null node ID throws NullPointerException to fail fast.");
        ctx.say("This prevents runtime errors in distributed coordination.");

        assertThatThrownBy(() -> GlobalSequenceService.create(null, discovery))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nodeId must not be null");
    }

    @Test
    @DisplayName("Should reject empty node ID")
    void create_handlesEmptyNodeId(DtrContext ctx) {
        ctx.say("Node ID must be non-empty for uniqueness guarantees.");
        ctx.say("Empty string throws IllegalArgumentException.");
        ctx.say("This prevents invalid cluster configuration.");

        assertThatThrownBy(() -> GlobalSequenceService.create("", discovery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId must not be empty");
    }

    @Test
    @DisplayName("Should reject null discovery service")
    void create_handlesNullDiscovery(DtrContext ctx) {
        ctx.say("NodeDiscovery service is required for cluster coordination.");
        ctx.say("Null discovery throws NullPointerException.");
        ctx.say("This ensures the service can synchronize with peers.");

        assertThatThrownBy(() -> GlobalSequenceService.create("node1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("discovery must not be null");
    }

    @Test
    @DisplayName("Should provide node ID for debugging")
    void getNodeId_returnsNodeId(DtrContext ctx) {
        ctx.say("Node ID is accessible for debugging and monitoring.");
        ctx.say("This helps identify which node generated a sequence.");
        ctx.say("Useful in distributed tracing and log analysis.");

        var impl = (HybridLogicalClockSequenceService) seqService;

        assertThat(impl.getNodeId()).isEqualTo("node1");
    }
}
