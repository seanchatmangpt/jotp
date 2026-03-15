package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Tests for {@link MessageFilter} — Vernon's Message Filter pattern implemented with JOTP.
 *
 * <p>Covers filtering correctness, chaining, edge cases, and performance characteristics.
 */
@Timeout(10)
@DisplayName("Message Filter Tests")
class MessageFilterTest implements WithAssertions {
    record Message(String id, int value, String category) {}
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Nested
    @DisplayName("Basic Filtering")
    class BasicFiltering {
        @Test
        void forwardsMatchingMessages() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);
            MessageFilter<Message> filter =
                    MessageFilter.create(
                            msg -> msg.value() > 5,
                            msg -> {
                                forwarded.incrementAndGet();
                                latch.countDown();
                            });
            filter.filter(new Message("m1", 10, "A")); // Should forward
            filter.filter(new Message("m2", 3, "B")); // Should drop
            filter.filter(new Message("m3", 8, "C")); // Should forward
            // Wait for 2 forwards (drop should happen silently)
            latch.await();
            assertThat(forwarded.get()).isEqualTo(2);
            filter.process().stop();
        }
        void dropsNonMatchingMessages() throws InterruptedException {
                            msg -> msg.category().equals("IMPORTANT"),
                            msg -> forwarded.incrementAndGet());
            filter.filter(new Message("m1", 5, "IMPORTANT")); // Forward
            filter.filter(new Message("m2", 5, "SPAM")); // Drop
            // Give time to process
            await().atMost(Duration.ofMillis(500)).until(() -> true);
            assertThat(forwarded.get()).isEqualTo(1); // Only 1 forwarded
        void handlesNoMatches() throws InterruptedException {
                            msg -> msg.value() > 100, // Very strict predicate
            filter.filter(new Message("m1", 5, "A"));
            filter.filter(new Message("m2", 10, "B"));
            filter.filter(new Message("m3", 50, "C"));
            // Wait to ensure nothing is forwarded
            assertThat(forwarded.get()).isEqualTo(0);
    @DisplayName("Filter Chaining")
    class FilterChaining {
        void chainsMultipleFilters() throws InterruptedException {
            AtomicInteger final_count = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);
            // Create chain: value > 5 -> category == IMPORTANT -> final
            MessageFilter<Message> categoryFilter =
                                final_count.incrementAndGet();
            MessageFilter<Message> valueFilter =
                    MessageFilter.create(msg -> msg.value() > 5, categoryFilter::filter);
            // Send test messages
            valueFilter.filter(new Message("m1", 10, "IMPORTANT")); // Pass both
            valueFilter.filter(new Message("m2", 3, "IMPORTANT")); // Drop at value filter
            valueFilter.filter(new Message("m3", 8, "SPAM")); // Pass value, drop at category
            assertThat(final_count.get()).isEqualTo(1); // Only m1 reaches the end
            valueFilter.process().stop();
            categoryFilter.process().stop();
        void deepChain() throws InterruptedException {
            // Create a 3-level chain
            MessageFilter<Message> filter3 =
            MessageFilter<Message> filter2 =
                    MessageFilter.create(msg -> msg.value() > 5, filter3::filter);
            MessageFilter<Message> filter1 =
                    MessageFilter.create(msg -> msg.id().startsWith("msg"), filter2::filter);
            // Only message passing all three filters should reach final
            filter1.filter(new Message("msg_1", 10, "IMPORTANT")); // Pass all
            filter1.filter(new Message("bad_1", 10, "IMPORTANT")); // Fail filter1
            filter1.filter(new Message("msg_2", 3, "IMPORTANT")); // Fail filter2
            filter1.filter(new Message("msg_3", 10, "SPAM")); // Fail filter3
            assertThat(final_count.get()).isEqualTo(1);
            filter1.process().stop();
            filter2.process().stop();
            filter3.process().stop();
    @DisplayName("Factory Methods")
    class FactoryMethods {
        void createWithDestination() throws InterruptedException {
            AtomicInteger count = new AtomicInteger(0);
            MessageFilter.Destination<Message> dest =
                    msg -> {
                        count.incrementAndGet();
                        latch.countDown();
                    };
            MessageFilter<Message> filter = MessageFilter.create(msg -> msg.value() > 5, dest);
            filter.filter(new Message("m1", 10, "A"));
            assertThat(count.get()).isEqualTo(1);
        void createWithProc() throws InterruptedException {
            io.github.seanchatmangpt.jotp.Proc<Integer, Message> proc =
                    new io.github.seanchatmangpt.jotp.Proc<>(
                            0,
                            (state, msg) -> {
                                count.incrementAndGet();
                                return state;
            MessageFilter<Message> filter = MessageFilter.create(msg -> msg.value() > 5, proc);
            proc.stop();
    @DisplayName("Edge Cases")
    class EdgeCases {
        void nullPredicateThrows() {
            assertThatThrownBy(() -> MessageFilter.create(null, msg -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("predicate");
        void nullDestinationThrows() {
            assertThatThrownBy(
                            () ->
                                    MessageFilter.create(
                                            msg -> true, (MessageFilter.Destination<Message>) null))
                    .hasMessageContaining("next");
        void nullMessageThrows() {
            MessageFilter<Message> filter = MessageFilter.create(msg -> true, msg -> {});
            assertThatThrownBy(() -> filter.filter(null))
                    .hasMessageContaining("message");
        void alwaysTruePredicateForwardsAll() throws InterruptedException {
            int count = 10;
            CountDownLatch latch = new CountDownLatch(count);
                            msg -> true, // Always forward
            for (int i = 0; i < count; i++) {
                filter.filter(new Message("m" + i, i, "ANY"));
            }
            assertThat(forwarded.get()).isEqualTo(count);
        void alwaysFalsePredicateDropsAll() throws InterruptedException {
                            msg -> false, // Never forward
            filter.filter(new Message("m3", 15, "C"));
    @DisplayName("Concurrency")
    class Concurrency {
        void concurrentFiltering() throws InterruptedException {
            int messageCount = 100;
            CountDownLatch latch = new CountDownLatch(messageCount / 2); // Expect 50 forwards
            // Send messages from multiple virtual threads
            for (int i = 0; i < messageCount; i++) {
                final int value = i % 10;
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    filter.filter(new Message("m" + value, value, "A"));
                                });
            // Half of messages have value > 5 (values 6,7,8,9 out of 0-9)
            assertThat(forwarded.get()).isEqualTo(50);
    @DisplayName("Performance")
    class Performance {
        void highThroughputFiltering() throws InterruptedException {
            int messageCount = 1000;
            CountDownLatch latch = new CountDownLatch(messageCount / 2); // Expect half
                            msg -> msg.value() % 2 == 0, // Pass even values
            long start = System.currentTimeMillis();
                filter.filter(new Message("msg_" + i, i, "CAT"));
            long elapsed = System.currentTimeMillis() - start;
            assertThat(forwarded.get()).isEqualTo(messageCount / 2);
            assertThat(elapsed).isLessThan(5000); // Should complete quickly
        void complexPredicatePerformance() throws InterruptedException {
            int messageCount = 500;
            CountDownLatch latch =
                    new CountDownLatch(messageCount / 4); // Complex predicate ~25% match
                            msg ->
                                    msg.value() > 5
                                            && msg.category().length() > 3
                                            && msg.id().startsWith("m"),
                filter.filter(new Message("msg_" + i, i % 20, "IMPORTANT"));
            assertThat(forwarded.get()).isGreaterThan(0);
}
