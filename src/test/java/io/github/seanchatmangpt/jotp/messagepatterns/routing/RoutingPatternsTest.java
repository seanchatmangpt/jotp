package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Routing patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 *
 * <p>Enterprise Integration Patterns (EIP) routing patterns enable messages to be dynamically
 * routed to different destinations based on content, conditions, or runtime configuration.
 */
@DisplayName("Routing Patterns")
class RoutingPatternsTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Nested
    @DisplayName("ContentBasedRouter")
    class ContentBasedRouterTests {

        record Order(String id, String type) {}

        @Test
        @DisplayName("routes to matching destination")
        void routesCorrectly() {
                    "Routes messages to different destinations based on message content. Each message is examined and routed to the appropriate channel based on predefined conditions.");
                    """
                    var router = ContentBasedRouter.<Order>builder()
                        .when(o -> o.type().equals("A"), typeA::add)
                        .when(o -> o.type().equals("B"), typeB::add)
                        .build();

                    router.route(new Order("1", "A"));
                    router.route(new Order("2", "B"));
                    """,
                    "java");
                    """
                    graph LR
                        A[Message] --> B{Content-Based Router}
                        B -->|type=A| C[Channel A]
                        B -->|type=B| D[Channel B]
                        B -->|default| E[Default Channel]
                    """);
                    "Use when you need to route messages based on their content, such as order type, customer tier, or message priority.");

            var typeA = new CopyOnWriteArrayList<Order>();
            var typeB = new CopyOnWriteArrayList<Order>();

            var router =
                    ContentBasedRouter.<Order>builder()
                            .when(o -> o.type().equals("A"), typeA::add)
                            .when(o -> o.type().equals("B"), typeB::add)
                            .build();

            router.route(new Order("1", "A"));
            router.route(new Order("2", "B"));

            assertThat(typeA).hasSize(1);
            assertThat(typeB).hasSize(1);
            assertThat(typeA.getFirst().id()).isEqualTo("1");
        }

        @Test
        @DisplayName("falls through to otherwise")
        void otherwise() {
                    "Messages that don't match any specific condition can be routed to a default/fallback channel using the otherwise clause.");
                    """
                    var router = ContentBasedRouter.<Order>builder()
                        .when(o -> o.type().equals("A"), o -> {})
                        .otherwise(fallback::add)
                        .build();
                    """,
                    "java");
                    "Always provide a fallback channel to handle unexpected message types gracefully, preventing message loss.");

            var fallback = new CopyOnWriteArrayList<Order>();

            var router =
                    ContentBasedRouter.<Order>builder()
                            .when(o -> o.type().equals("A"), o -> {})
                            .otherwise(fallback::add)
                            .build();

            router.route(new Order("1", "unknown"));
            assertThat(fallback).hasSize(1);
        }
    }

    @Nested
    @DisplayName("DynamicRouter")
    class DynamicRouterTests {

        @Test
        @DisplayName("dynamically adds and removes interests")
        void dynamicRouting() {
                    "Routes messages based on dynamic conditions that can be added or removed at runtime. Unlike static routers, dynamic routers allow destinations to register their interests programmatically.");
                    """
                    var router = new DynamicRouter<String>();
                    router.registerInterest("upper", s -> s.equals(s.toUpperCase()), results::add);
                    router.route("HELLO");
                    router.removeInterest("upper");
                    """,
                    "java");
                    """
                    graph LR
                        A[Message] --> B[Dynamic Router]
                        B -->|Predicate 1| C[Channel 1]
                        B -->|Predicate 2| D[Channel 2]
                        B -->|Predicate N| E[Channel N]
                        F[Runtime Registration] --> B
                    """);
                    "Use when routing conditions change frequently or when you need to add/remove destinations without redeploying the application.");

            var router = new DynamicRouter<String>();
            var results = new CopyOnWriteArrayList<String>();

            router.registerInterest("upper", s -> s.equals(s.toUpperCase()), results::add);
            router.route("HELLO");
            assertThat(results).containsExactly("HELLO");

            router.removeInterest("upper");
            assertThat(router.route("WORLD")).isFalse();
        }
    }

    @Nested
    @DisplayName("RecipientList")
    class RecipientListTests {

        record PriceRequest(double totalPrice) {}

        @Test
        @DisplayName("routes to all interested recipients")
        void routesToInterested() {
                    "Routes a single message to multiple recipients that have expressed interest in it. Unlike multicast, recipients can dynamically register or deregister their interest.");
                    """
                    var list = new RecipientList<PriceRequest>();
                    list.register("budget", budget::add, r -> r.totalPrice() < 100);
                    list.register("premium", premium::add, r -> r.totalPrice() >= 100);
                    int count = list.route(new PriceRequest(50));
                    """,
                    "java");
                    """
                    graph LR
                        A[Message] --> B[Recipient List]
                        B -->|matches| C[Recipient 1]
                        B -->|matches| D[Recipient 2]
                        B -->|no match| E[Recipient 3]
                    """);
                    "Use when multiple recipients need to receive the same message based on their individual criteria, such as vendor selection or notification routing.");

            var list = new RecipientList<PriceRequest>();
            var budget = new CopyOnWriteArrayList<PriceRequest>();
            var premium = new CopyOnWriteArrayList<PriceRequest>();

            list.register("budget", budget::add, r -> r.totalPrice() < 100);
            list.register("premium", premium::add, r -> r.totalPrice() >= 100);

            int count = list.route(new PriceRequest(50));
            assertThat(count).isEqualTo(1);
            assertThat(budget).hasSize(1);
            assertThat(premium).isEmpty();
        }
    }

    @Nested
    @DisplayName("Splitter")
    class SplitterTests {

        record Order(String id, List<String> items) {}

        @Test
        @DisplayName("splits composite message into parts")
        void splits() {
                    "Breaks down a composite message into individual parts for independent processing. Each part is sent as a separate message through the channel.");
                    """
                    var splitter = new Splitter<Order, String>(o -> o.items(), parts::add);
                    int count = splitter.split(new Order("o1", List.of("item-a", "item-b", "item-c")));
                    """,
                    "java");
                    """
                    graph LR
                        A[Composite Message] --> B[Splitter]
                        B --> C[Part 1]
                        B --> D[Part 2]
                        B --> E[Part 3]
                    """);
                    "Use when you need to process elements of a collection independently, such as processing individual items in an order or lines in a batch file.");

            var parts = new CopyOnWriteArrayList<String>();
            var splitter = new Splitter<Order, String>(o -> o.items(), parts::add);

            int count = splitter.split(new Order("o1", List.of("item-a", "item-b", "item-c")));
            assertThat(count).isEqualTo(3);
            assertThat(parts).containsExactly("item-a", "item-b", "item-c");
        }
    }

    @Nested
    @DisplayName("Aggregator")
    class AggregatorTests {

        record Quote(String rfqId, double price) {}

        @Test
        @DisplayName("aggregates correlated parts and emits result")
        void aggregates() throws InterruptedException {
                    "Combines multiple related messages into a single aggregate message. Messages are correlated by a correlation ID and the aggregation strategy determines when to emit the result.");
                    """
                    var aggregator = new Aggregator<Quote, String, Double>(
                        Quote::rfqId,
                        quotes -> quotes.stream().mapToDouble(Quote::price).min().orElse(0),
                        best -> { /* emit result */ });
                    aggregator.expect("rfq-1", 3);
                    aggregator.addPart(new Quote("rfq-1", 100.0));
                    aggregator.addPart(new Quote("rfq-1", 80.0));
                    aggregator.addPart(new Quote("rfq-1", 120.0));
                    """,
                    "java");
                    """
                    graph LR
                        A[Part 1] --> D[Aggregator]
                        B[Part 2] --> D
                        C[Part 3] --> D
                        D -->|complete| E[Aggregate Message]
                    """);
                    "Use when you need to collect multiple related messages before processing, such as gathering quotes from multiple vendors or assembling order lines.");

            var latch = new CountDownLatch(1);
            var result = new AtomicReference<Double>();

            var aggregator =
                    new Aggregator<Quote, String, Double>(
                            Quote::rfqId,
                            quotes -> quotes.stream().mapToDouble(Quote::price).min().orElse(0),
                            best -> {
                                result.set(best);
                                latch.countDown();
                            });

            aggregator.expect("rfq-1", 3);
            aggregator.addPart(new Quote("rfq-1", 100.0));
            aggregator.addPart(new Quote("rfq-1", 80.0));
            aggregator.addPart(new Quote("rfq-1", 120.0));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get()).isEqualTo(80.0);
            aggregator.stop();
        }
    }

    @Nested
    @DisplayName("Resequencer")
    class ResequencerTests {

        @Test
        @DisplayName("reorders out-of-sequence messages")
        void reorders() throws InterruptedException {
            var latch = new CountDownLatch(3);
            var received = new CopyOnWriteArrayList<String>();

            var resequencer =
                    new Resequencer<String>(
                            msg -> {
                                received.add(msg);
                                latch.countDown();
                            });

            // Send out of order: 2, 0, 1
            resequencer.submit(new Resequencer.Sequenced<>("c1", 2, 3, "third"));
            resequencer.submit(new Resequencer.Sequenced<>("c1", 0, 3, "first"));
            resequencer.submit(new Resequencer.Sequenced<>("c1", 1, 3, "second"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly("first", "second", "third");
            resequencer.stop();
        }
    }

    @Nested
    @DisplayName("RoutingSlip")
    class RoutingSlipTests {

        @Test
        @DisplayName("executes steps in sequence")
        void sequentialSteps() {
            var slip =
                    RoutingSlip.<String>builder()
                            .step("decrypt", s -> s.replace("(encrypted)", ""))
                            .step("authenticate", s -> s.replace("(cert)", ""))
                            .step("trim", String::trim)
                            .build();

            String result = slip.execute("(encrypted) Hello (cert) ");
            assertThat(result).isEqualTo("Hello");
            assertThat(slip.stepCount()).isEqualTo(3);
            assertThat(slip.stepNames()).containsExactly("decrypt", "authenticate", "trim");
        }
    }

    @Nested
    @DisplayName("ScatterGather")
    class ScatterGatherTests {

        @Test
        @DisplayName("scatters request and gathers responses")
        void scatterGather() {
            var sg =
                    ScatterGather.<String, Integer>builder()
                            .addHandler(s -> s.length())
                            .addHandler(s -> s.hashCode())
                            .addHandler(s -> 42)
                            .timeout(Duration.ofSeconds(5))
                            .build();

            List<Integer> results = sg.scatterAndGather("test");
            assertThat(results).hasSize(3);
            assertThat(results).contains(4, 42);
        }
    }

    @Nested
    @DisplayName("MessageRouter")
    class MessageRouterTests {

        @Test
        @DisplayName("round-robin routes across destinations")
        void roundRobin() {
                    "Routes messages to multiple destinations in a round-robin fashion, distributing load evenly across available endpoints.");
                    """
                    var router = new MessageRouter<String>(dest1::add, dest2::add);
                    router.route("a");
                    router.route("b");
                    router.route("c");
                    router.route("d");
                    """,
                    "java");
                    """
                    graph LR
                        A[Message 1] --> D{Router}
                        B[Message 2] --> D
                        C[Message 3] --> D
                        D -->|round robin| E[Endpoint 1]
                        D -->|round robin| F[Endpoint 2]
                    """);
                    "Use when you need to distribute load evenly across multiple identical consumers for horizontal scaling.");

            var dest1 = new CopyOnWriteArrayList<String>();
            var dest2 = new CopyOnWriteArrayList<String>();

            var router = new MessageRouter<String>(dest1::add, dest2::add);

            router.route("a");
            router.route("b");
            router.route("c");
            router.route("d");

            assertThat(dest1).containsExactly("a", "c");
            assertThat(dest2).containsExactly("b", "d");
        }
    }
}
