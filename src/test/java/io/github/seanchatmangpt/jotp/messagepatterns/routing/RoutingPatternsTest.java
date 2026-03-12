package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Routing patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 */
@DisplayName("Routing Patterns")
class RoutingPatternsTest implements WithAssertions {

    @Nested
    @DisplayName("ContentBasedRouter")
    class ContentBasedRouterTests {

        record Order(String id, String type) {}

        @Test
        @DisplayName("routes to matching destination")
        void routesCorrectly() {
            var typeA = new CopyOnWriteArrayList<Order>();
            var typeB = new CopyOnWriteArrayList<Order>();

            var router = ContentBasedRouter.<Order>builder()
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
            var fallback = new CopyOnWriteArrayList<Order>();

            var router = ContentBasedRouter.<Order>builder()
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
            var latch = new CountDownLatch(1);
            var result = new AtomicReference<Double>();

            var aggregator = new Aggregator<Quote, String, Double>(
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

            var resequencer = new Resequencer<String>(msg -> {
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
            var slip = RoutingSlip.<String>builder()
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
            var sg = ScatterGather.<String, Integer>builder()
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
            var dest1 = new CopyOnWriteArrayList<String>();
            var dest2 = new CopyOnWriteArrayList<String>();

            var router = new MessageRouter<>(dest1::add, dest2::add);

            router.route("a");
            router.route("b");
            router.route("c");
            router.route("d");

            assertThat(dest1).containsExactly("a", "c");
            assertThat(dest2).containsExactly("b", "d");
        }
    }
}
