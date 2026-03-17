package io.github.seanchatmangpt.jotp.messaging.channels;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/**
 * Test suite for PublishSubscribeChannel pattern with DTR documentation.
 *
 * <p>The Publish-Subscribe Channel (EIP) broadcasts messages to all subscribed consumers. Each
 * subscriber receives a copy of every message, enabling fan-out notification patterns.
 *
 * <p>Verifies 1:N broadcast delivery, subscriber isolation, and event ordering.
 */
@DisplayName("Publish-Subscribe Channel Pattern (EIP)")
class PublishSubscribeChannelTest {

    private PublishSubscribeChannel<Message> channel;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        channel = PublishSubscribeChannel.create();
    }

    @Test
    @DisplayName("should deliver message to all subscribers")
    void testBroadcastToAllSubscribers() throws InterruptedException {
                "Broadcasts messages to all subscribed consumers. Each subscriber receives a copy of"
                        + " every message, enabling fan-out notification patterns.");
                """
                var channel = PublishSubscribeChannel.create();
                channel.subscribe(sub1Events::add);
                channel.subscribe(sub2Events::add);
                channel.subscribe(sub3Events::add);

                var message = Message.event("TEST", "data");
                channel.publish(message);

                // All subscribers received the same message
                assertThat(sub1Events).containsExactly(message);
                assertThat(sub2Events).containsExactly(message);
                assertThat(sub3Events).containsExactly(message);
                """,
                "java");
                """
                graph LR
                    A[Publisher] -->|Message| B[Pub-Sub Channel]
                    B -->|Copy 1| C[Subscriber 1]
                    B -->|Copy 2| D[Subscriber 2]
                    B -->|Copy N| E[Subscriber N]
                """);
                "Use when multiple consumers need to receive the same message, such as event"
                        + " notifications, cache invalidation, or audit logging.");

        // Given: multiple subscribers
        List<Message> sub1Events = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub2Events = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub3Events = Collections.synchronizedList(new ArrayList<>());

        channel.subscribe(sub1Events::add);
        channel.subscribe(sub2Events::add);
        channel.subscribe(sub3Events::add);

        // When: publishing a message
        var message = Message.event("TEST", "data");
        channel.publish(message);

        Thread.sleep(100); // Let async processing complete

        // Then: all subscribers received the same message
        assertThat(sub1Events).containsExactly(message);
        assertThat(sub2Events).containsExactly(message);
        assertThat(sub3Events).containsExactly(message);
    }

    @Test
    @DisplayName("should handle multiple published events for each subscriber")
    void testMultipleEventsToMultipleSubscribers() throws InterruptedException {
                """
                channel.publish(event1);
                channel.publish(event2);
                channel.publish(event3);

                // Both subscribers receive all events in order
                assertThat(sub1Events).containsExactly(event1, event2, event3);
                assertThat(sub2Events).containsExactly(event1, event2, event3);
                """,
                "java");
                "Ordering guarantees are per-subscriber. Different subscribers may process messages at"
                        + " different rates, but each sees messages in publication order.");

        // Given: two subscribers
        List<Message> sub1Events = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub2Events = Collections.synchronizedList(new ArrayList<>());

        channel.subscribe(sub1Events::add);
        channel.subscribe(sub2Events::add);

        // When: publishing multiple messages
        var event1 = Message.event("E1", null);
        var event2 = Message.event("E2", null);
        var event3 = Message.event("E3", null);

        channel.publish(event1);
        channel.publish(event2);
        channel.publish(event3);

        Thread.sleep(200);

        // Then: both subscribers receive all events in order
        assertThat(sub1Events).containsExactly(event1, event2, event3);
        assertThat(sub2Events).containsExactly(event1, event2, event3);
    }

    @Test
    @DisplayName("should support unsubscribe")
    void testUnsubscribe() throws InterruptedException {
                """
                var h1 = channel.subscribe(handler1);
                var h2 = channel.subscribe(handler2);

                channel.unsubscribe(h2);

                channel.publish(message);

                // Only handler1 received it
                assertThat(sub1Events).containsExactly(message);
                assertThat(sub2Events).isEmpty();
                """,
                "java");
                "Dynamic subscription allows consumers to join and leave the channel at runtime"
                        + " without disrupting other subscribers.");

        // Given: multiple subscribers
        List<Message> sub1Events = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub2Events = Collections.synchronizedList(new ArrayList<>());

        var handler1 = (java.util.function.Consumer<Message>) sub1Events::add;
        var handler2 = (java.util.function.Consumer<Message>) sub2Events::add;

        channel.subscribe(handler1);
        var h2 = channel.subscribe(handler2);

        // When: unsubscribing one subscriber
        channel.unsubscribe(h2);

        // And: publishing
        var message = Message.event("TEST", null);
        channel.publish(message);

        Thread.sleep(100);

        // Then: only remaining subscriber received it
        assertThat(sub1Events).containsExactly(message);
        assertThat(sub2Events).isEmpty();
    }

    @Test
    @DisplayName("should allow dynamic subscription during publishing")
    void testDynamicSubscription() throws InterruptedException {
                """
                channel.subscribe(sub1);
                channel.publish(msg1);  // sub1 receives

                channel.subscribe(sub2);  // sub2 joins
                channel.publish(msg2);   // both receive

                // sub1 got both, sub2 got only msg2 (joined late)
                assertThat(sub1Events).containsExactly(msg1, msg2);
                assertThat(sub2Events).containsExactly(msg2);
                """,
                "java");
                "Late-joining subscribers don't receive historical messages. They only receive"
                        + " messages published after subscription.");

        // Given: initial subscriber
        List<Message> sub1Events = Collections.synchronizedList(new ArrayList<>());
        channel.subscribe(sub1Events::add);

        // When: publishing first message
        var msg1 = Message.event("E1", null);
        channel.publish(msg1);
        Thread.sleep(100);

        // And: adding new subscriber mid-stream
        List<Message> sub2Events = Collections.synchronizedList(new ArrayList<>());
        channel.subscribe(sub2Events::add);

        // And: publishing second message
        var msg2 = Message.event("E2", null);
        channel.publish(msg2);
        Thread.sleep(100);

        // Then: sub1 got both, sub2 got only msg2 (joined late)
        assertThat(sub1Events).containsExactly(msg1, msg2);
        assertThat(sub2Events).containsExactly(msg2);
    }

    @Test
    @DisplayName("should isolate subscriber exceptions")
    void testSubscriberExceptionIsolation() throws InterruptedException {
                "Exceptions in one subscriber don't prevent other subscribers from receiving the"
                        + " message.");
                """
                channel.subscribe(msg -> {
                    throw new RuntimeException("Simulated failure");
                });

                channel.subscribe(okEvents::add);

                channel.publishSync(message);

                // ok subscriber still received message despite other's exception
                assertThat(okEvents).containsExactly(message);
                """,
                "java");
                """
                graph LR
                    A[Message] --> B[Subscriber 1: Throws]
                    A --> C[Subscriber 2: OK]
                    B -.->|exception| D[Error Handler]
                    C --> E[Processed]
                """);
                "Exception isolation is critical for reliability—a buggy subscriber shouldn't break"
                        + " the entire notification system.");

        // Given: one throwing subscriber, one normal
        List<Message> okEvents = Collections.synchronizedList(new ArrayList<>());

        channel.subscribe(
                msg -> {
                    throw new RuntimeException("Simulated failure");
                });

        channel.subscribe(okEvents::add);

        // When: publishing (first subscriber throws)
        var message = Message.event("TEST", null);
        channel.publishSync(message); // Sync to ensure exception handling

        // Then: ok subscriber still received message despite other's exception
        assertThat(okEvents).containsExactly(message);
    }

    @Test
    @DisplayName("should support different message types")
    void testMessageTypeFiltering() throws InterruptedException {
                "Subscribers can filter messages by type, processing only the message types they care"
                        + " about.");
                """
                channel.subscribe(msg -> {
                    if (msg instanceof Message.EventMsg evt) {
                        eventTypes.add("EVENT: " + evt.eventType());
                    } else if (msg instanceof Message.CommandMsg cmd) {
                        eventTypes.add("COMMAND: " + cmd.commandType());
                    }
                });

                channel.publish(Message.event("ORDER_PLACED", null));
                channel.publish(Message.command("PROCESS_ORDER", null, null));
                channel.publish(Message.event("SHIPMENT_SENT", null));

                // Types were captured correctly
                assertThat(eventTypes).containsExactly(
                    "EVENT: ORDER_PLACED", "COMMAND: PROCESS_ORDER", "EVENT: SHIPMENT_SENT");
                """,
                "java");
                "Type filtering enables polymorphic message handling—different subscribers can"
                        + " interpret the same message stream differently.");

        // Given: subscriber that filters by message type
        List<String> eventTypes = Collections.synchronizedList(new ArrayList<>());

        channel.subscribe(
                msg -> {
                    if (msg instanceof Message.EventMsg evt) {
                        eventTypes.add("EVENT: " + evt.eventType());
                    } else if (msg instanceof Message.CommandMsg cmd) {
                        eventTypes.add("COMMAND: " + cmd.commandType());
                    }
                });

        // When: publishing different message types
        channel.publish(Message.event("ORDER_PLACED", null));
        channel.publish(Message.command("PROCESS_ORDER", null, null));
        channel.publish(Message.event("SHIPMENT_SENT", null));

        Thread.sleep(200);

        // Then: types were captured correctly
        assertThat(eventTypes)
                .containsExactly(
                        "EVENT: ORDER_PLACED", "COMMAND: PROCESS_ORDER", "EVENT: SHIPMENT_SENT");
    }

    @Test
    @DisplayName("should preserve message uniqueness across subscribers")
    void testMessageIdConsistency() throws InterruptedException {
                "All subscribers receive the same message instance with the same message ID. This"
                        + " enables correlation and tracing.");
                """
                channel.publish(msg);

                // Both have the same message ID
                assertThat(sub1.get(0).messageId()).isEqualTo(sub2.get(0).messageId());
                """,
                "java");
                "Consistent message IDs enable distributed tracing and correlation across multiple"
                        + " subscribers.");

        // Given: two subscribers
        List<Message> sub1 = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub2 = Collections.synchronizedList(new ArrayList<>());

        channel.subscribe(sub1::add);
        channel.subscribe(sub2::add);

        // When: publishing
        var msg = Message.event("TEST", null);
        channel.publish(msg);

        Thread.sleep(100);

        // Then: both have the same message ID
        assertThat(sub1.get(0).messageId()).isEqualTo(sub2.get(0).messageId());
    }

    @Test
    @DisplayName("should support publishSync for blocking behavior")
    void testPublishSync() throws InterruptedException {
                "publishSync() blocks until all subscribers have processed the message, enabling"
                        + " reliable broadcast semantics.");
                """
                var msg = Message.event("TEST", null);
                int notified = channel.publishSync(msg);

                // Returns number of subscribers notified
                assertThat(notified).isEqualTo(2);
                assertThat(sub1).containsExactly(msg);
                assertThat(sub2).containsExactly(msg);
                """,
                "java");
                "Use publishSync() when you need to ensure all subscribers have received the message"
                        + " before proceeding, such as in shutdown scenarios.");

        // Given: subscribers
        CountDownLatch processedLatch = new CountDownLatch(2);
        List<Message> sub1 = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub2 = Collections.synchronizedList(new ArrayList<>());

        channel.subscribe(
                msg -> {
                    sub1.add(msg);
                    processedLatch.countDown();
                });
        channel.subscribe(
                msg -> {
                    sub2.add(msg);
                    processedLatch.countDown();
                });

        // When: publishing synchronously
        var msg = Message.event("TEST", null);
        int notified = channel.publishSync(msg);

        // Then: returns number of subscribers notified
        assertThat(notified).isEqualTo(2);
        assertThat(processedLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(sub1).containsExactly(msg);
        assertThat(sub2).containsExactly(msg);
    }
}
