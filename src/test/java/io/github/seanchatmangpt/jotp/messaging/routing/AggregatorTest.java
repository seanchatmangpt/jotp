package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Comprehensive tests for the Aggregator pattern with DTR documentation.
 *
 * <p>The Aggregator pattern (EIP) combines multiple related messages into a single aggregate
 * message. Messages are correlated by a correlation ID and the aggregation strategy determines when
 * to emit the result.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Basic aggregation (collecting all parts into one message)
 *   <li>Out-of-order part arrival (sequence numbers handled correctly)
 *   <li>Timeout handling (incomplete aggregations are discarded)
 *   <li>Type safety and immutability
 *   <li>Multiple concurrent aggregations (different correlation IDs)
 *   <li>Duplicate part handling
 *   <li>Metadata validation
 * </ul>
 */
@DtrTest
@DisplayName("Aggregator Pattern (EIP)")
class AggregatorTest {

    record Item(String id, int value) {}

    record CompleteMsg(List<Item> items) {}

    private Aggregator<Item> aggregator;

    @BeforeEach
    void setup() {
        ApplicationController.reset();
        aggregator = Aggregator.create(3, Duration.ofSeconds(5), items -> new CompleteMsg(items));
    }

    @DisplayName("Basic aggregation: all parts arrive in order")
    @Test
    void testBasicAggregationInOrder(DtrContext ctx) throws Exception {
        ctx.sayNextSection("Aggregator Pattern");
        ctx.say(
                "The Aggregator pattern combines multiple related messages into a single aggregate"
                        + " message. Parts are correlated by a correlation ID and assembled in sequence"
                        + " order.");
        ctx.sayCode(
                """
                var aggregator = Aggregator.create(3, Duration.ofSeconds(5), items -> new CompleteMsg(items));

                UUID corrId = UUID.randomUUID();
                aggregator.aggregate(createPart(new Item("1", 10), 1, 3, corrId));
                aggregator.aggregate(createPart(new Item("2", 20), 2, 3, corrId));
                Optional<CompleteMsg> result = aggregator.aggregate(createPart(new Item("3", 30), 3, 3, corrId));

                assertThat(result).isPresent();
                assertThat(result.get().items()).extracting(Item::id).containsExactly("1", "2", "3");
                """,
                "java");
        ctx.sayMermaid(
                """
                graph LR
                    A[Part 1: seq=1/3] --> D[Aggregator]
                    B[Part 2: seq=2/3] --> D
                    C[Part 3: seq=3/3] --> D
                    D -->|complete| E[Aggregate Message]
                """);
        ctx.sayNote(
                "Use when you need to collect multiple related messages before processing, such as"
                        + " gathering quotes from multiple vendors or assembling order lines.");

        UUID corrId = UUID.randomUUID();

        var part1 = createPart(new Item("1", 10), 1, 3, corrId);
        var part2 = createPart(new Item("2", 20), 2, 3, corrId);
        var part3 = createPart(new Item("3", 30), 3, 3, corrId);

        Optional<CompleteMsg> result1 = aggregator.aggregate(part1);
        assertThat(result1).isEmpty();

        Optional<CompleteMsg> result2 = aggregator.aggregate(part2);
        assertThat(result2).isEmpty();

        Optional<CompleteMsg> result3 = aggregator.aggregate(part3);
        assertThat(result3).isPresent();
        assertThat(result3.get().items()).extracting(Item::id).containsExactly("1", "2", "3");
    }

    @DisplayName("Aggregation: parts arrive out of order")
    @Test
    void testOutOfOrderAggregation(DtrContext ctx) {
        ctx.sayNextSection("Aggregator: Out-of-Order Arrival");
        ctx.say(
                "The aggregator correctly handles parts arriving out of order, assembling them in"
                        + " sequence order regardless of arrival order.");
        ctx.sayCode(
                """
                // Send in order: 3, 1, 2
                aggregator.aggregate(part3);  // seq=3/3
                aggregator.aggregate(part1);  // seq=1/3
                Optional<CompleteMsg> result = aggregator.aggregate(part2);  // seq=2/3

                // Items should be in sequence order (1, 2, 3), not arrival order
                assertThat(result.get().items()).extracting(Item::id).containsExactly("1", "2", "3");
                """,
                "java");
        ctx.sayNote(
                "This is essential for distributed systems where network latency can cause messages"
                        + " to arrive out of order.");

        UUID corrId = UUID.randomUUID();

        var part1 = createPart(new Item("1", 10), 1, 3, corrId);
        var part2 = createPart(new Item("2", 20), 2, 3, corrId);
        var part3 = createPart(new Item("3", 30), 3, 3, corrId);

        // Send in order: 3, 1, 2
        Optional<CompleteMsg> res3 = aggregator.aggregate(part3);
        assertThat(res3).isEmpty();

        Optional<CompleteMsg> res1 = aggregator.aggregate(part1);
        assertThat(res1).isEmpty();

        Optional<CompleteMsg> res2 = aggregator.aggregate(part2);
        assertThat(res2).isPresent();
        // Items should be in sequence order (1, 2, 3), not arrival order
        assertThat(res2.get().items()).extracting(Item::id).containsExactly("1", "2", "3");
    }

    @DisplayName("Multiple concurrent aggregations with different correlation IDs")
    @Test
    void testMultipleConcurrentAggregations(DtrContext ctx) {
        ctx.sayNextSection("Aggregator: Concurrent Aggregations");
        ctx.say(
                "Multiple aggregations can be in progress simultaneously, tracked by their unique"
                        + " correlation IDs.");
        ctx.sayCode(
                """
                UUID corrId1 = UUID.randomUUID();
                UUID corrId2 = UUID.randomUUID();

                // Aggregation 1: send part 1
                aggregator.aggregate(part1a);  // corrId1
                assertThat(aggregator.activeCorrelations()).isEqualTo(1);

                // Aggregation 2: send part 1
                aggregator.aggregate(part1b);  // corrId2
                assertThat(aggregator.activeCorrelations()).isEqualTo(2);

                // Complete aggregation 1
                aggregator.aggregate(part2a);  // corrId1 complete
                assertThat(aggregator.activeCorrelations()).isEqualTo(1);
                """,
                "java");
        ctx.sayNote(
                "This enables processing multiple split messages concurrently without interference.");

        UUID corrId1 = UUID.randomUUID();
        UUID corrId2 = UUID.randomUUID();

        // Aggregation 1: send part 1
        var part1a = createPart(new Item("1a", 10), 1, 2, corrId1);
        var res1 = aggregator.aggregate(part1a);
        assertThat(res1).isEmpty();
        assertThat(aggregator.activeCorrelations()).isEqualTo(1);

        // Aggregation 2: send part 1
        var part1b = createPart(new Item("1b", 20), 1, 2, corrId2);
        var res2 = aggregator.aggregate(part1b);
        assertThat(res2).isEmpty();
        assertThat(aggregator.activeCorrelations()).isEqualTo(2);

        // Complete aggregation 1
        var part2a = createPart(new Item("2a", 30), 2, 2, corrId1);
        var res1b = aggregator.aggregate(part2a);
        assertThat(res1b).isPresent();
        assertThat(aggregator.activeCorrelations()).isEqualTo(1);

        // Complete aggregation 2
        var part2b = createPart(new Item("2b", 40), 2, 2, corrId2);
        var res2b = aggregator.aggregate(part2b);
        assertThat(res2b).isPresent();
        assertThat(aggregator.activeCorrelations()).isEqualTo(0);
    }

    @DisplayName("Timeout: incomplete aggregation throws after timeout")
    @Test
    void testTimeoutOnIncompleteAggregation(DtrContext ctx) throws Exception {
        ctx.sayNextSection("Aggregator: Timeout Handling");
        ctx.say(
                "Aggregations that don't complete within the timeout period are discarded to prevent"
                        + " resource leaks.");
        ctx.sayCode(
                """
                var shortTimeoutAgg = Aggregator.create(2, Duration.ofMillis(200), items -> new CompleteMsg(items));

                UUID corrId = UUID.randomUUID();
                shortTimeoutAgg.aggregate(part1);
                assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(1);

                Thread.sleep(300);  // Wait for timeout

                // Try to add part 2 after timeout
                assertThatThrownBy(() -> shortTimeoutAgg.aggregate(part2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timed out");

                // Aggregation state should be cleaned up
                assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(0);
                """,
                "java");
        ctx.sayNote(
                "Timeouts are essential for preventing memory leaks from incomplete aggregations."
                        + " Choose timeout values based on expected processing times.");

        var shortTimeoutAgg =
                Aggregator.create(2, Duration.ofMillis(200), items -> new CompleteMsg(items));

        UUID corrId = UUID.randomUUID();
        var part1 = createPart(new Item("1", 10), 1, 2, corrId);

        Optional<CompleteMsg> res1 = shortTimeoutAgg.aggregate(part1);
        assertThat(res1).isEmpty();
        assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(1);

        // Wait for timeout to elapse
        Thread.sleep(300);

        // Try to add part 2 after timeout
        var part2 = createPart(new Item("2", 20), 2, 2, corrId);
        assertThatThrownBy(() -> shortTimeoutAgg.aggregate(part2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");

        // Aggregation state should be cleaned up
        assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(0);
    }

    @DisplayName("Metadata mismatch: totalParts differs from expected")
    @Test
    void testMetadataMismatch(DtrContext ctx) {
        ctx.sayNextSection("Aggregator: Metadata Validation");
        ctx.say(
                "The aggregator validates that part metadata matches the expected configuration,"
                        + " preventing programming errors.");
        ctx.sayCode(
                """
                // Aggregator expects 3, but metadata says 5
                var part = createPart(new Item("1", 10), 1, 5, corrId);

                assertThatThrownBy(() -> aggregator.aggregate(part))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("totalParts");
                """,
                "java");
        ctx.sayNote(
                "Validation catches mismatches early, ensuring that all parts of a split message are"
                        + " aggregated correctly.");

        UUID corrId = UUID.randomUUID();

        // Aggregator expects 3, but metadata says 5
        var part = createPart(new Item("1", 10), 1, 5, corrId);

        assertThatThrownBy(() -> aggregator.aggregate(part))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalParts");
    }

    @DisplayName("Assembly happens exactly once per correlation")
    @Test
    void testAssemblyOnce(DtrContext ctx) {
        ctx.sayNextSection("Aggregator: Single Assembly");
        ctx.say("The assembly function is called exactly once when all parts arrive.");
        ctx.sayCode(
                """
                var callCounter = new AtomicInteger(0);

                var agg = Aggregator.create(2, Duration.ofSeconds(5), items -> {
                    callCounter.incrementAndGet();
                    return new CompleteMsg(items);
                });

                agg.aggregate(part1);
                agg.aggregate(part2);

                assertThat(callCounter.get()).isEqualTo(1);
                """,
                "java");

        var callCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        var agg =
                Aggregator.create(
                        2,
                        Duration.ofSeconds(5),
                        items -> {
                            callCounter.incrementAndGet();
                            return new CompleteMsg(items);
                        });

        UUID corrId = UUID.randomUUID();
        var part1 = createPart(new Item("1", 10), 1, 2, corrId);
        var part2 = createPart(new Item("2", 20), 2, 2, corrId);

        agg.aggregate(part1);
        agg.aggregate(part2);

        assertThat(callCounter.get()).isEqualTo(1);
    }

    @DisplayName("Duplicate part sequence number: overwrites previous")
    @Test
    void testDuplicatePartSequence(DtrContext ctx) {
        ctx.sayNextSection("Aggregator: Duplicate Handling");
        ctx.say(
                "If a part with the same sequence number arrives twice, the newer part overwrites"
                        + " the older one.");
        ctx.sayCode(
                """
                aggregator.aggregate(part1a);  // seq=1
                aggregator.aggregate(part1b);  // seq=1 (overwrites)
                Optional<CompleteMsg> result = aggregator.aggregate(part2);  // seq=2

                // Should contain part1b (the newer one), not part1a
                assertThat(result.get().items()).extracting(Item::id).containsExactly("1b", "2");
                """,
                "java");
        ctx.sayNote(
                "This behavior is useful for retry scenarios where a message might be redelivered.");

        UUID corrId = UUID.randomUUID();

        var part1a = createPart(new Item("1a", 10), 1, 2, corrId);
        var part1b = createPart(new Item("1b", 15), 1, 2, corrId); // Same sequence
        var part2 = createPart(new Item("2", 20), 2, 2, corrId);

        aggregator.aggregate(part1a);
        aggregator.aggregate(part1b); // Overwrites part1a
        Optional<CompleteMsg> result = aggregator.aggregate(part2);

        // Should contain part1b (the newer one), not part1a
        assertThat(result.get().items()).extracting(Item::id).containsExactly("1b", "2");
    }

    // Helper method to create test parts
    private Splitter.MessagePart<Item> createPart(Item item, int seqNum, int total, UUID corrId) {
        return new Splitter.MessagePart<>(item, new Splitter.PartMetadata(corrId, seqNum, total));
    }
}
