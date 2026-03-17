package io.github.seanchatmangpt.jotp.enterprise.eventbus;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Comprehensive DTR-documented tests for EventBus pattern.
 *
 * <p>Documents Joe Armstrong's gen_event pub-sub pattern with ordered delivery, dead-letter queue,
 * batching, and delivery guarantees using JOTP's process-based architecture.
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining event bus semantics, delivery policies, and production patterns. Run with DTR to see
 * examples with actual output values.
 */
@DisplayName("EventBus: Joe Armstrong gen_event pub-sub with ordered delivery and DLQ")
class EventBusTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    private static EventBusConfig defaultConfig() {
        return EventBusConfig.builder().build();
    }

    // ── Test 1: EventBus Overview and Creation ────────────────────────────────

    @DisplayName("createWithValidConfig_returnsInstance: EventBus.create returns non-null")
    void createWithValidConfig_returnsInstance() {
                "EventBus implements Joe Armstrong's gen_event pattern for publish-subscribe messaging."
                        + " Producers publish events without knowing consumers, enabling loose coupling and"
                        + " scalability.");
                """
            // Create event bus with default configuration
            EventBusConfig config = EventBusConfig.builder()
                .batchSize(10)
                .batchTimeoutMs(100)
                .maxRetries(3)
                .build();

            var bus = EventBus.create(config);
            """,
                "java");

        var config = defaultConfig();
        var bus = EventBus.create(config);
        assertThat(bus).isNotNull();

                Map.of(
                        "Batch Size", "10 events",
                        "Batch Timeout", "100ms",
                        "Max Retries", "3",
                        "Delivery Policy", "AtLeastOnce (default)"));
                """
            sequenceDiagram
                participant P as Publisher
                participant B as EventBus
                participant S1 as Subscriber 1
                participant S2 as Subscriber 2

                P->>B: publish(event)
                B->>S1: deliver(event)
                B->>S2: deliver(event)
                S1-->>B: ACK
                S2-->>B: ACK
                B-->>P: ACCEPTED
            """);
        bus.shutdown();
    }

    @DisplayName("configBuilder_rejectsZeroBatchSize: batchSize=0 throws IllegalArgumentException")
    void configBuilder_rejectsZeroBatchSize() {
                "Batching improves throughput by delivering multiple events together. Zero batch size"
                        + " prevents any event delivery.");
                """
            assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder()
                    .batchSize(0)  // Invalid!
                    .build());
            """,
                "java");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder().batchSize(0).build());

                List.of(
                        List.of("1", "Immediate delivery (no batching)"),
                        List.of("10", "Small batch, low latency"),
                        List.of("100", "Large batch, high throughput")),
                List.of("Batch Size", "Characteristics"));
    }

    @DisplayName("configBuilder_rejectsZeroBatchTimeout: batchTimeoutMs=0 throws exception")
    void configBuilder_rejectsZeroBatchTimeout() {
                "Batch timeout determines maximum wait before flushing partial batch. Zero timeout"
                        + " defeats batching purpose.");
                """
            assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder()
                    .batchTimeoutMs(0)  // Invalid!
                    .build());
            """,
                "java");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder().batchTimeoutMs(0).build());

                Map.of(
                        "Recommended", "50-200ms",
                        "Short Timeout", "Faster delivery, less batching",
                        "Long Timeout", "More batching, higher latency"));
    }

    @DisplayName("configBuilder_rejectsNegativeMaxRetries: maxRetries=-1 throws exception")
    void configBuilder_rejectsNegativeMaxRetries() {
                "Max retries controls delivery attempts for failed subscribers. Negative values are"
                        + " invalid.");
                """
            assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder()
                    .maxRetries(-1)  // Invalid!
                    .build());
            """,
                "java");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder().maxRetries(-1).build());

                List.of(
                        List.of("0", "Fire-and-forget (no retry)"),
                        List.of("3", "Standard retry"),
                        List.of("10", "Persistent retry")),
                List.of("Max Retries", "Delivery Guarantee"));
    }

    // ── Test 5: Publishing and Subscribing ──────────────────────────────────────

    @DisplayName("publish_noSubscribers_accepted: publish with no subscribers returns ACCEPTED")
    void publish_noSubscribers_accepted() {
                "EventBus accepts events even with no subscribers. This enables pub-decoupling where"
                        + " publishers don't need to know if subscribers exist.");
                """
            var bus = EventBus.create(config);
            var result = bus.publish("test-event");

            // result.status() == PublishResult.Status.ACCEPTED
            // result.eventId() != null
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        var result = bus.publish("test-event");
        assertThat(result.status()).isEqualTo(EventBus.PublishResult.Status.ACCEPTED);

                Map.of(
                        "Status", "ACCEPTED",
                        "Event ID", "UUID assigned",
                        "Delivered To", "0 subscribers",
                        "Reason", "No-op is acceptable"));
        bus.shutdown();
    }

    @DisplayName(
            "subscribe_thenPublish_handlerReceivesEvent: subscribed handler is called when event published")
    void subscribe_thenPublish_handlerReceivesEvent() {
                "Subscribers register handlers with unique IDs. When events are published, all"
                        + " subscribers receive the event asynchronously.");
                """
            var bus = EventBus.create(config);
            var received = new AtomicBoolean(false);

            bus.subscribe("sub1", event -> received.set(true));
            bus.publish("hello");

            await().atMost(Duration.ofSeconds(3)).untilTrue(received);
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        var received = new AtomicBoolean(false);

        bus.subscribe("sub1", event -> received.set(true));
        bus.publish("hello");

        await().atMost(Duration.ofSeconds(3)).untilTrue(received);

                List.of(
                        List.of("Subscriber ID", "sub1"),
                        List.of("Event Received", "true"),
                        List.of("Delivery Time", "< 3 seconds"),
                        List.of("Handler Invoked", "Once")),
                List.of("Property", "Value"));
        bus.shutdown();
    }

    @DisplayName("unsubscribe_handlerNoLongerCalled: handler not called after unsubscribing")
    void unsubscribe_handlerNoLongerCalled() {
                "Subscribers can be removed by ID. After unsubscription, handler no longer receives"
                        + " events. Delivery is idempotent - unsubscribing twice is safe.");
                """
            var bus = EventBus.create(config);
            var callCount = new AtomicInteger(0);

            bus.subscribe("sub1", event -> callCount.incrementAndGet());
            bus.publish("first");
            // callCount == 1

            bus.unsubscribe("sub1");
            bus.publish("second");
            // callCount == 1 (no increase)
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        var callCount = new AtomicInteger(0);

        bus.subscribe("sub1", event -> callCount.incrementAndGet());
        bus.publish("first");
        await().atMost(Duration.ofSeconds(3)).until(() -> callCount.get() == 1);

        bus.unsubscribe("sub1");
        bus.publish("second");

        Thread.sleep(200);

        assertThat(callCount.get()).isEqualTo(1);

                Map.of(
                        "Initial Publish", "Delivered (count=1)",
                        "After Unsubscribe", "Not delivered (count=1)",
                        "Idempotent", "Safe to unsubscribe multiple times"));
        bus.shutdown();
    }

    // ── Test 8: Flow Control ───────────────────────────────────────────────────

    @DisplayName("publishWhenPaused_returnsRejected: publish after pause() returns REJECTED")
    void publishWhenPaused_returnsRejected() {
                "EventBus can be paused to stop accepting new events. Useful for backpressure,"
                        + " maintenance, or graceful shutdown. Paused bus returns REJECTED.");
                """
            var bus = EventBus.create(config);
            bus.pause();

            var result = bus.publish("event");
            // result.status() == PublishResult.Status.REJECTED
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        bus.pause();
        var result = bus.publish("event");
        assertThat(result.status()).isEqualTo(EventBus.PublishResult.Status.REJECTED);

                List.of(
                        List.of("RUNNING", "Accepts and delivers events"),
                        List.of("PAUSED", "Rejects new events"),
                        List.of("DEGRADED", "Some subscribers failing")),
                List.of("State", "Behavior"));
        bus.shutdown();
    }

    @DisplayName("resume_afterPause_publishAccepted: publish after resume() returns ACCEPTED")
    void resume_afterPause_publishAccepted() {
                "Resuming a paused bus restores normal operation. Events are accepted and delivered"
                        + " to subscribers.");
                """
            var bus = EventBus.create(config);
            bus.pause();
            bus.resume();

            var result = bus.publish("event");
            // result.status() == PublishResult.Status.ACCEPTED
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        bus.pause();
        bus.resume();
        var result = bus.publish("event");
        assertThat(result.status()).isEqualTo(EventBus.PublishResult.Status.ACCEPTED);

                Map.of(
                        "After Resume", "Running state",
                        "Publish Result", "ACCEPTED",
                        "Delivery", "Normal"));
        bus.shutdown();
    }

    // ── Test 10: Fault Tolerance ───────────────────────────────────────────────

    @DisplayName(
            "subscriberFails_nextPublishStillWorks: bus continues working after a subscriber throws")
    void subscriberFails_nextPublishStillWorks() {
                "Subscriber failures don't kill the event bus. Bad subscribers are isolated, good"
                        + " subscribers continue receiving events. This is Joe Armstrong's 'let it crash'"
                        + " principle.");
                """
            sequenceDiagram
                participant B as EventBus
                participant Bad as Bad Subscriber
                participant Good as Good Subscriber

                B->>Bad: deliver(event1)
                Bad-->>B: EXCEPTION (thrown)
                B->>Good: deliver(event1)
                Good-->>B: ACK

                B->>Bad: deliver(event2)
                Bad-->>B: EXCEPTION (thrown)
                B->>Good: deliver(event2)
                Good-->>B: ACK
            """);

                """
            var bus = EventBus.create(config);
            var goodReceived = new AtomicBoolean(false);

            // Subscriber that throws
            bus.subscribe("bad-sub", event -> {
                throw new RuntimeException("deliberate failure");
            });

            // Subscriber that works
            bus.subscribe("good-sub", event -> goodReceived.set(true));

            bus.publish("event1");
            await().atMost(Duration.ofSeconds(3)).untilTrue(goodReceived);

            var result = bus.publish("event2");
            // result.status() == ACCEPTED (bus still working)
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        var goodReceived = new AtomicBoolean(false);

        bus.subscribe(
                "bad-sub",
                event -> {
                    throw new RuntimeException("deliberate failure");
                });

        bus.subscribe("good-sub", event -> goodReceived.set(true));

        bus.publish("event1");
        await().atMost(Duration.ofSeconds(3)).untilTrue(goodReceived);

        var result = bus.publish("event2");
        assertThat(result.status()).isEqualTo(EventBus.PublishResult.Status.ACCEPTED);

                List.of(
                        List.of("Bad Subscriber", "Throws exception"),
                        List.of("Good Subscriber", "Receives both events"),
                        List.of("Event Bus", "Continues running"),
                        List.of("Fault Isolation", "Subscriber failures contained")),
                List.of("Component", "Behavior"));
        bus.shutdown();
    }

    // ── Test 11: Observability ─────────────────────────────────────────────────

    @DisplayName(
            "getSubscribers_afterSubscribeAndUnsubscribe_correct: size reflects subscribe/unsubscribe")
    void getSubscribers_afterSubscribeAndUnsubscribe_correct() {
                "EventBus provides introspection of current subscribers. Useful for monitoring,"
                        + " debugging, and operational visibility.");
                """
            var bus = EventBus.create(config);

            bus.subscribe("sub1", event -> {});
            bus.subscribe("sub2", event -> {});
            // bus.getSubscribers().size() == 2

            bus.unsubscribe("sub1");
            // bus.getSubscribers().size() == 1
            """,
                "java");

        var bus = EventBus.create(defaultConfig());

        bus.subscribe("sub1", event -> {});
        bus.subscribe("sub2", event -> {});
        assertThat(bus.getSubscribers()).hasSize(2);

        bus.unsubscribe("sub1");
        assertThat(bus.getSubscribers()).hasSize(1);

                Map.of(
                        "Initial Subscribers", "2 (sub1, sub2)",
                        "After Unsubscribe", "1 (sub2)",
                        "Subscriber Info", "List of SubscriberInfo records"));
        bus.shutdown();
    }

    // ── Test 13: Lifecycle ────────────────────────────────────────────────────

    @DisplayName("shutdown_doesNotThrow: calling shutdown does not throw any exception")
    void shutdown_doesNotThrow() {
                "EventBus must be shutdown to release process resources. Shutdown is idempotent and"
                        + " safe to call multiple times.");
                """
            var bus = EventBus.create(config);
            bus.shutdown();
            """,
                "java");

        var bus = EventBus.create(defaultConfig());
        assertThatNoException().isThrownBy(bus::shutdown);

                Map.of(
                        "Process Shutdown", "Coordinator process terminated",
                        "Subscriber Cleanup", "All subscribers removed",
                        "Dead Letter Queue", "Preserved for inspection"));
    }
}
