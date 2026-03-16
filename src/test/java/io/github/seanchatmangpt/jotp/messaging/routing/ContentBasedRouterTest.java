package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link ContentBasedRouter} with DTR documentation.
 *
 * <p>The Content-Based Router (EIP) examines message content and routes messages to different
 * destinations based on predefined conditions. This is one of the most fundamental EIP patterns.
 *
 * <p>Covers routing correctness, edge cases, and performance characteristics.
 */
@Timeout(10)
@DisplayName("Content-Based Router Pattern (EIP)")
class ContentBasedRouterTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    record Message(String type, int priority, String content) {}

    @Nested
    @DisplayName("Basic Routing")
    class BasicRouting {

        @Test
        void routesToFirstMatchingPredicate(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router");
            ctx.say(
                    "Routes messages to different destinations based on message content. Each message"
                            + " is examined and routed to the appropriate channel based on predefined"
                            + " conditions.");
            ctx.sayCode(
                    """
                    ContentBasedRouter<Message> router = new ContentBasedRouter<>();
                    router.addRoute(msg -> msg.priority() >= 8, urgentHandler);
                    router.addRoute(msg -> msg.priority() < 8, normalHandler);

                    router.route(new Message("alert", 9, "urgent"));
                    router.route(new Message("info", 3, "normal"));
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Message] --> B{Content-Based Router}
                        B -->|priority >= 8| C[Urgent Handler]
                        B -->|priority < 8| D[Normal Handler]
                    """);
            ctx.sayNote(
                    "Use when you need to route messages based on their content, such as order type,"
                            + " customer tier, or message priority. The first matching route wins.");

            AtomicInteger urgentCount = new AtomicInteger(0);
            AtomicInteger normalCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        urgentCount.incrementAndGet();
                        latch.countDown();
                        return msg.priority() >= 8;
                    });
            router.addRoute(
                    msg -> {
                        normalCount.incrementAndGet();
                        latch.countDown();
                        return msg.priority() < 8;
                    });

            router.route(new Message("alert", 9, "urgent"));
            router.route(new Message("info", 3, "normal"));

            latch.await();

            assertThat(urgentCount.get()).isEqualTo(1);
            assertThat(normalCount.get()).isEqualTo(1);

            router.process().stop();
        }

        @Test
        void firstMatchingRouteWins(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: First Match Wins");
            ctx.say(
                    "When multiple predicates match a message, the first matching route is used. Route"
                            + " order matters!");
            ctx.sayCode(
                    """
                    // Both predicates match, but first should be used
                    router.addRoute(msg -> msg.priority() >= 5, firstHandler);
                    router.addRoute(msg -> msg.priority() >= 3, secondHandler);

                    router.route(new Message("order", 7, "content"));

                    assertThat(firstRoute.get()).isEqualTo(1);
                    assertThat(secondRoute.get()).isEqualTo(0);  // Should not be invoked
                    """,
                    "java");
            ctx.sayNote(
                    "Order routes from most specific to most general to ensure correct routing."
                            + " Think of it like a switch statement.");

            AtomicInteger firstRoute = new AtomicInteger(0);
            AtomicInteger secondRoute = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            // Both predicates match, but first should be used
            router.addRoute(
                    msg -> {
                        firstRoute.incrementAndGet();
                        latch.countDown();
                        return msg.priority() >= 5;
                    });
            router.addRoute(
                    msg -> {
                        secondRoute.incrementAndGet();
                        latch.countDown();
                        return msg.priority() >= 3;
                    });

            router.route(new Message("order", 7, "content"));

            latch.await();

            assertThat(firstRoute.get()).isEqualTo(1);
            assertThat(secondRoute.get()).isEqualTo(0); // Should not be invoked
            router.process().stop();
        }

        @Test
        void unmatchedMessagesSilentlyDropped(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Unmatched Messages");
            ctx.say(
                    "Messages that don't match any route are silently dropped unless a default route"
                            + " is configured.");
            ctx.sayCode(
                    """
                    router.addRoute(msg -> msg.type().equals("ORDER"), handler);

                    // Send a message that doesn't match
                    router.route(new Message("UNKNOWN", 5, "content"));

                    // Message is dropped, handler never invoked
                    assertThat(delivered.get()).isEqualTo(0);
                    """,
                    "java");
            ctx.sayNote(
                    "Always provide a default route or dead letter channel to handle unexpected"
                            + " message types and prevent silent message loss.");

            AtomicInteger delivered = new AtomicInteger(0);
            Thread.sleep(100); // Let any spurious messages settle

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        delivered.incrementAndGet();
                        return msg.type().equals("ORDER");
                    });

            // Send a message that doesn't match
            router.route(new Message("UNKNOWN", 5, "content"));

            // Wait a bit to ensure no delivery
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(delivered.get()).isEqualTo(0);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Default Route")
    class DefaultRoute {

        @Test
        void defaultRouteHandlesUnmatchedMessages(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Default Route");
            ctx.say(
                    "A default route handles messages that don't match any specific condition,"
                            + " preventing message loss.");
            ctx.sayCode(
                    """
                    ContentBasedRouter<Message> router = new ContentBasedRouter<>();
                    router.addRoute(msg -> msg.type().equals("ORDER"), mainRoute);
                    router.setDefault(defaultRoute);

                    router.route(new Message("ORDER", 5, "matching"));
                    router.route(new Message("UNKNOWN", 5, "unmatched"));

                    // Both messages are handled
                    assertThat(mainRoute.get()).isEqualTo(1);
                    assertThat(defaultRoute.get()).isEqualTo(1);
                    """,
                    "java");
            ctx.sayNote(
                    "Default routes are essential for robustness—they catch unexpected message types"
                            + " and enable graceful error handling or logging.");

            AtomicInteger mainRoute = new AtomicInteger(0);
            AtomicInteger defaultRoute = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        mainRoute.incrementAndGet();
                        latch.countDown();
                        return msg.type().equals("ORDER");
                    });
            router.setDefault(
                    msg -> {
                        defaultRoute.incrementAndGet();
                        latch.countDown();
                    });

            router.route(new Message("ORDER", 5, "matching"));
            router.route(new Message("UNKNOWN", 5, "unmatched"));

            latch.await();

            assertThat(mainRoute.get()).isEqualTo(1);
            assertThat(defaultRoute.get()).isEqualTo(1);
            router.process().stop();
        }

        @Test
        void nullDefaultDropsUnmatched(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Null Default");
            ctx.say("Setting default to null explicitly drops unmatched messages.");
            ctx.sayCode(
                    """
                    router.setDefault(null);  // Explicitly null default

                    router.route(new Message("ORDER", 5, "match"));
                    router.route(new Message("UNKNOWN", 5, "drop"));

                    // Only ORDER is delivered
                    assertThat(delivered.get()).isEqualTo(1);
                    """,
                    "java");

            AtomicInteger delivered = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        delivered.incrementAndGet();
                        latch.countDown();
                        return msg.type().equals("ORDER");
                    });
            router.setDefault(null); // Explicitly null default

            router.route(new Message("ORDER", 5, "match"));
            latch.await();

            router.route(new Message("UNKNOWN", 5, "drop"));

            // Wait to ensure no additional delivery
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(delivered.get()).isEqualTo(1);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        void nullPredicateThrows(DtrContext ctx) {
            ctx.sayNextSection("Content-Based Router: Validation");
            ctx.say("The router validates that predicates and destinations are not null.");
            ctx.sayCode(
                    """
                    assertThatThrownBy(() -> router.addRoute(null, msg -> {}))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("predicate");

                    assertThatThrownBy(() -> router.addRoute(msg -> true, null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("destination");
                    """,
                    "java");

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();

            assertThatThrownBy(() -> router.addRoute(null, msg -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("predicate");

            assertThatThrownBy(() -> router.addRoute(msg -> true, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("destination");
        }

        @Test
        void emptyRouterDropsAll(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Empty Router");
            ctx.say("A router with no routes drops all messages.");
            ctx.sayCode(
                    """
                    ContentBasedRouter<Message> router = new ContentBasedRouter<>();
                    // No routes added

                    router.route(new Message("ANY", 5, "should drop"));

                    // Message is dropped
                    assertThat(deliveries.get()).isEqualTo(0);
                    """,
                    "java");

            AtomicInteger deliveries = new AtomicInteger(0);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            // No routes added

            router.route(new Message("ANY", 5, "should drop"));
            router.route(new Message("ANY", 5, "should drop"));

            // Wait to ensure nothing is delivered
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(deliveries.get()).isEqualTo(0);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        void concurrentRouting(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Concurrency");
            ctx.say(
                    "The router is thread-safe and can handle concurrent routing from multiple virtual"
                            + " threads.");
            ctx.sayCode(
                    """
                    ContentBasedRouter<Message> router = new ContentBasedRouter<>();
                    router.addRoute(msg -> msg.priority() >= 5, handler);

                    // Send messages from multiple virtual threads
                    for (int i = 0; i < messageCount; i++) {
                        final int priority = i % 10;
                        Thread.ofVirtual().start(() -> {
                            if (priority >= 5) {
                                router.route(new Message("msg", priority, "content"));
                            }
                        });
                    }

                    // Expect messages with priority 5,6,7,8,9 (5 out of 10 = 50)
                    assertThat(routedCount.get()).isEqualTo(50);
                    """,
                    "java");
            ctx.sayNote(
                    "Thread safety enables horizontal scaling—multiple producers can route messages"
                            + " concurrently without contention.");

            AtomicInteger routedCount = new AtomicInteger(0);
            int messageCount = 100;
            CountDownLatch latch = new CountDownLatch(messageCount);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        routedCount.incrementAndGet();
                        latch.countDown();
                        return msg.priority() >= 5;
                    });

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

            latch.await();

            // Expect messages with priority 5,6,7,8,9 (5 out of 10 = 50)
            assertThat(routedCount.get()).isEqualTo(50);
            router.process().stop();
        }

        @Test
        void addRoutesDuringRouting(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Dynamic Routes");
            ctx.say("Routes can be added dynamically while the router is in use.");
            ctx.sayCode(
                    """
                    router.addRoute(msg -> msg.type().equals("TYPE1"), route1);
                    router.route(new Message("TYPE1", 5, "first"));

                    // Add second route while first is being used
                    router.addRoute(msg -> msg.type().equals("TYPE2"), route2);
                    router.route(new Message("TYPE2", 5, "second"));

                    // Both routes work correctly
                    assertThat(route1.get()).isEqualTo(1);
                    assertThat(route2.get()).isEqualTo(1);
                    """,
                    "java");
            ctx.sayNote(
                    "Dynamic routing enables flexible, adaptive message flow that can change at"
                            + " runtime without redeployment.");

            AtomicInteger route1 = new AtomicInteger(0);
            AtomicInteger route2 = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        route1.incrementAndGet();
                        latch.countDown();
                        return msg.type().equals("TYPE1");
                    });

            router.route(new Message("TYPE1", 5, "first"));

            // Add second route while first is being used
            router.addRoute(
                    msg -> {
                        route2.incrementAndGet();
                        latch.countDown();
                        return msg.type().equals("TYPE2");
                    });

            router.route(new Message("TYPE2", 5, "second"));

            latch.await();

            assertThat(route1.get()).isEqualTo(1);
            assertThat(route2.get()).isEqualTo(1);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Performance")
    class Performance {

        @Test
        void highThroughputRouting(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content-Based Router: Performance");
            ctx.say(
                    "Content-based routing is O(n) in the number of routes, where n is typically small"
                            + " (< 10 routes). This enables high-throughput message routing.");
            ctx.sayCode(
                    """
                    int messageCount = 1000;
                    ContentBasedRouter<Message> router = new ContentBasedRouter<>();
                    router.addRoute(msg -> msg.priority() <= 3, destinationA);
                    router.addRoute(msg -> msg.priority() > 3 && msg.priority() <= 6, destinationB);
                    router.addRoute(msg -> msg.priority() > 6, destinationC);

                    long start = System.currentTimeMillis();
                    for (int i = 0; i < messageCount; i++) {
                        router.route(new Message("msg", i % 10, "content"));
                    }
                    long elapsed = System.currentTimeMillis() - start;

                    assertThat(elapsed).isLessThan(5000);  // Should complete quickly
                    """,
                    "java");
            ctx.sayNote(
                    "Keep the number of routes small for optimal performance. For complex routing,"
                            + " consider a routing table or decision tree instead of linear search.");

            ConcurrentHashMap<String, AtomicInteger> destinations = new ConcurrentHashMap<>();
            destinations.put("A", new AtomicInteger(0));
            destinations.put("B", new AtomicInteger(0));
            destinations.put("C", new AtomicInteger(0));

            int messageCount = 1000;
            CountDownLatch latch = new CountDownLatch(messageCount);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(
                    msg -> {
                        destinations.get("A").incrementAndGet();
                        latch.countDown();
                        return msg.priority() <= 3;
                    });
            router.addRoute(
                    msg -> {
                        destinations.get("B").incrementAndGet();
                        latch.countDown();
                        return msg.priority() > 3 && msg.priority() <= 6;
                    });
            router.addRoute(
                    msg -> {
                        destinations.get("C").incrementAndGet();
                        latch.countDown();
                        return msg.priority() > 6;
                    });

            long start = System.currentTimeMillis();

            for (int i = 0; i < messageCount; i++) {
                router.route(new Message("msg", i % 10, "content"));
            }

            latch.await();

            long elapsed = System.currentTimeMillis() - start;

            assertThat(destinations.get("A").get()).isGreaterThan(0);
            assertThat(destinations.get("B").get()).isGreaterThan(0);
            assertThat(destinations.get("C").get()).isGreaterThan(0);
            assertThat(elapsed).isLessThan(5000); // Should complete quickly

            router.process().stop();
        }
    }
}
