package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Tests for {@link ContentBasedRouter} — Vernon's Content-Based Router pattern implemented with
 * JOTP.
 *
 * <p>Covers routing correctness, edge cases, and performance characteristics.
 */
@Timeout(10)
@DisplayName("Content-Based Router Tests")
class ContentBasedRouterTest implements WithAssertions {
    record Message(String type, int priority, String content) {}
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Nested
    @DisplayName("Basic Routing")
    class BasicRouting {
        @Test
        void routesToFirstMatchingPredicate() throws InterruptedException {
            AtomicInteger urgentCount = new AtomicInteger(0);
            AtomicInteger normalCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);
            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> msg.priority() >= 8,
                    msg -> {
                        urgentCount.incrementAndGet();
                        latch.countDown();
                    });
                    msg -> msg.priority() < 8,
                        normalCount.incrementAndGet();
            router.route(new Message("alert", 9, "urgent"));
            router.route(new Message("info", 3, "normal"));
            latch.await();
            assertThat(urgentCount.get()).isEqualTo(1);
            assertThat(normalCount.get()).isEqualTo(1);
            router.process().stop();
        }
        void firstMatchingRouteWins() throws InterruptedException {
            AtomicInteger firstRoute = new AtomicInteger(0);
            AtomicInteger secondRoute = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);
            // Both predicates match, but first should be used
                    msg -> msg.priority() >= 5,
                        firstRoute.incrementAndGet();
                    msg -> msg.priority() >= 3,
                        secondRoute.incrementAndGet();
            router.route(new Message("order", 7, "content"));
            assertThat(firstRoute.get()).isEqualTo(1);
            assertThat(secondRoute.get()).isEqualTo(0); // Should not be invoked
        void unmatchedMessagesSilentlyDropped() throws InterruptedException {
            AtomicInteger delivered = new AtomicInteger(0);
            Thread.sleep(100); // Let any spurious messages settle
            router.addRoute(msg -> msg.type().equals("ORDER"), msg -> delivered.incrementAndGet());
            // Send a message that doesn't match
            router.route(new Message("UNKNOWN", 5, "content"));
            // Wait a bit to ensure no delivery
            await().atMost(Duration.ofMillis(500)).until(() -> true);
            assertThat(delivered.get()).isEqualTo(0);
    @DisplayName("Default Route")
    class DefaultRoute {
        void defaultRouteHandlesUnmatchedMessages() throws InterruptedException {
            AtomicInteger mainRoute = new AtomicInteger(0);
            AtomicInteger defaultRoute = new AtomicInteger(0);
                    msg -> msg.type().equals("ORDER"),
                        mainRoute.incrementAndGet();
            router.setDefault(
                        defaultRoute.incrementAndGet();
            router.route(new Message("ORDER", 5, "matching"));
            router.route(new Message("UNKNOWN", 5, "unmatched"));
            assertThat(mainRoute.get()).isEqualTo(1);
            assertThat(defaultRoute.get()).isEqualTo(1);
        void nullDefaultDropsUnmatched() throws InterruptedException {
                        delivered.incrementAndGet();
            router.setDefault(null); // Explicitly null default
            router.route(new Message("ORDER", 5, "match"));
            router.route(new Message("UNKNOWN", 5, "drop"));
            // Wait to ensure no additional delivery
            assertThat(delivered.get()).isEqualTo(1);
    @DisplayName("Edge Cases")
    class EdgeCases {
        void nullPredicateThrows() {
            assertThatThrownBy(() -> router.addRoute(null, msg -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("predicate");
        void nullDestinationThrows() {
            assertThatThrownBy(() -> router.addRoute(msg -> true, null))
                    .hasMessageContaining("destination");
        void nullMessageThrows() {
            router.addRoute(msg -> true, msg -> {});
            assertThatThrownBy(() -> router.route(null))
                    .hasMessageContaining("message");
        void emptyRouterDropsAll() throws InterruptedException {
            AtomicInteger deliveries = new AtomicInteger(0);
            // No routes added
            router.route(new Message("ANY", 5, "should drop"));
            // Wait to ensure nothing is delivered
            assertThat(deliveries.get()).isEqualTo(0);
    @DisplayName("Concurrency")
    class Concurrency {
        void concurrentRouting() throws InterruptedException {
            AtomicInteger routedCount = new AtomicInteger(0);
            int messageCount = 100;
            CountDownLatch latch = new CountDownLatch(messageCount);
                        routedCount.incrementAndGet();
            // Send messages from multiple virtual threads
            for (int i = 0; i < messageCount; i++) {
                final int priority = i % 10;
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    if (priority >= 5) {
                                        router.route(new Message("msg", priority, "content"));
                                    }
                                });
            }
            // Expect messages with priority 5,6,7,8,9 (5 out of 10 = 50)
            assertThat(routedCount.get()).isEqualTo(50);
        void addRoutesDuringRouting() throws InterruptedException {
            AtomicInteger route1 = new AtomicInteger(0);
            AtomicInteger route2 = new AtomicInteger(0);
                    msg -> msg.type().equals("TYPE1"),
                        route1.incrementAndGet();
            router.route(new Message("TYPE1", 5, "first"));
            // Add second route while first is being used
                    msg -> msg.type().equals("TYPE2"),
                        route2.incrementAndGet();
            router.route(new Message("TYPE2", 5, "second"));
            assertThat(route1.get()).isEqualTo(1);
            assertThat(route2.get()).isEqualTo(1);
    @DisplayName("Performance")
    class Performance {
        void highThroughputRouting() throws InterruptedException {
            ConcurrentHashMap<String, AtomicInteger> destinations = new ConcurrentHashMap<>();
            destinations.put("A", new AtomicInteger(0));
            destinations.put("B", new AtomicInteger(0));
            destinations.put("C", new AtomicInteger(0));
            int messageCount = 1000;
                    msg -> msg.priority() <= 3,
                        destinations.get("A").incrementAndGet();
                    msg -> msg.priority() > 3 && msg.priority() <= 6,
                        destinations.get("B").incrementAndGet();
                    msg -> msg.priority() > 6,
                        destinations.get("C").incrementAndGet();
            long start = System.currentTimeMillis();
                router.route(new Message("msg", i % 10, "content"));
            long elapsed = System.currentTimeMillis() - start;
            assertThat(destinations.get("A").get()).isGreaterThan(0);
            assertThat(destinations.get("B").get()).isGreaterThan(0);
            assertThat(destinations.get("C").get()).isGreaterThan(0);
            assertThat(elapsed).isLessThan(5000); // Should complete quickly
}
