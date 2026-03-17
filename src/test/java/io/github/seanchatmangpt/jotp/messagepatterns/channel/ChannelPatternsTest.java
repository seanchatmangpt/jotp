package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Channel patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 *
 * <p>Enterprise Integration Patterns (EIP) channel patterns define how messages are transported
 * between endpoints, including point-to-point, publish-subscribe, and datatype channels.
 */
@DisplayName("Channel Patterns")
class ChannelPatternsTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Nested
    @DisplayName("PointToPoint")
    class PointToPointTests {

        @Test
        @DisplayName("delivers message to single consumer")
        void singleConsumer() throws InterruptedException {
                    "Delivers messages from a single sender to a single receiver. Each message is consumed by exactly one consumer, ensuring reliable delivery.");
                    """
                    var channel = PointToPoint.<String>create(msg -> {
                        received.set(msg);
                        latch.countDown();
                    });
                    channel.send("Hello");
                    """,
                    "java");
                    """
                    graph LR
                        A[Sender] -->|Message| B[P2P Channel]
                        B -->|Message| C[Receiver]
                    """);
                    "Use when you need exactly-once processing semantics, such as command messages or work queue distribution.");

            var latch = new CountDownLatch(1);
            var received = new AtomicReference<String>();
            var channel =
                    PointToPoint.<String>create(
                            msg -> {
                                received.set(msg);
                                latch.countDown();
                            });

            channel.send("Hello");
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo("Hello");
            channel.stop();
        }
    }

    @Nested
    @DisplayName("PublishSubscribe")
    class PublishSubscribeTests {

        @Test
        @DisplayName("broadcasts to all subscribers")
        void broadcast() throws InterruptedException {
                    "Broadcasts messages to all subscribed consumers. Each subscriber receives a copy of every message, enabling fan-out notification patterns.");
                    """
                    var bus = new PublishSubscribe<String>();
                    bus.subscribe(received1::add);
                    bus.subscribe(received2::add);
                    bus.publishSync("event-1");
                    """,
                    "java");
                    """
                    graph LR
                        A[Publisher] -->|Message| B[Pub-Sub Channel]
                        B -->|Copy 1| C[Subscriber 1]
                        B -->|Copy 2| D[Subscriber 2]
                        B -->|Copy N| E[Subscriber N]
                    """);
                    "Use when multiple consumers need to receive the same message, such as event notifications, cache invalidation, or audit logging.");

            var bus = new PublishSubscribe<String>();
            var received1 = new CopyOnWriteArrayList<String>();
            var received2 = new CopyOnWriteArrayList<String>();

            bus.subscribe(received1::add);
            bus.subscribe(received2::add);

            bus.publishSync("event-1");

            assertThat(received1).containsExactly("event-1");
            assertThat(received2).containsExactly("event-1");
            bus.stop();
        }

        @Test
        @DisplayName("unsubscribe stops delivery")
        void unsubscribe() throws InterruptedException {
                    """
                    bus.subscribe(sub);
                    bus.publishSync("before");
                    bus.unsubscribe(sub);
                    bus.publishSync("after");
                    """,
                    "java");
                    "Dynamic subscription allows consumers to join and leave the channel at runtime without disrupting other subscribers.");

            var bus = new PublishSubscribe<String>();
            var received = new CopyOnWriteArrayList<String>();
            java.util.function.Consumer<String> sub = received::add;

            bus.subscribe(sub);
            bus.publishSync("before");
            bus.unsubscribe(sub);
            bus.publishSync("after");

            assertThat(received).containsExactly("before");
            bus.stop();
        }
    }

    @Nested
    @DisplayName("DatatypeChannel")
    class DatatypeChannelTests {

        record ProductQuery(String id) {}

        @Test
        @DisplayName("carries typed messages")
        void typedMessages() throws InterruptedException {
                    "A strongly-typed channel that only accepts and delivers messages of a specific type, providing compile-time type safety.");
                    """
                    var channel = DatatypeChannel.create(ProductQuery.class, q -> {
                        received.set(q);
                        latch.countDown();
                    });
                    channel.send(new ProductQuery("SKU-001"));
                    """,
                    "java");
                    """
                    graph LR
                        A[Sender] -->|ProductQuery| B[Datatype Channel]
                        B -->|ProductQuery| C[Receiver]
                    """);
                    "Use when you want type-safe message passing with automatic validation at compile time.");

            var latch = new CountDownLatch(1);
            var received = new AtomicReference<ProductQuery>();

            var channel =
                    DatatypeChannel.create(
                            ProductQuery.class,
                            q -> {
                                received.set(q);
                                latch.countDown();
                            });

            channel.send(new ProductQuery("SKU-001"));
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().id()).isEqualTo("SKU-001");
            assertThat(channel.messageType()).isEqualTo(ProductQuery.class);
            channel.stop();
        }
    }

    @Nested
    @DisplayName("InvalidMessageChannel")
    class InvalidMessageChannelTests {

        @Test
        @DisplayName("captures invalid messages with reasons")
        void captures() {
                    "Captures messages that fail validation along with the reason for failure, enabling error analysis and retry logic.");
                    """
                    var channel = new InvalidMessageChannel<String>();
                    channel.reject("bad-msg", "missing required field");
                    """,
                    "java");
                    "Use for message validation and error handling. Invalid messages can be logged, analyzed, or sent to dead letter channels.");

            var channel = new InvalidMessageChannel<String>();
            channel.reject("bad-msg", "missing required field");

            assertThat(channel.size()).isEqualTo(1);
            var entries = channel.drain();
            assertThat(entries.getFirst().message()).isEqualTo("bad-msg");
            assertThat(entries.getFirst().validationError()).isEqualTo("missing required field");
        }
    }

    @Nested
    @DisplayName("DeadLetter")
    class DeadLetterTests {

        @Test
        @DisplayName("captures undeliverable messages")
        void captures() {
                    "Captures messages that cannot be delivered, such as when no consumer is registered or the destination is unavailable.");
                    """
                    var dl = new DeadLetter<String>();
                    dl.dead("lost-msg", "no consumer registered");
                    """,
                    "java");
                    """
                    graph LR
                        A[Message] --> B{Can Deliver?}
                        B -->|No| C[Dead Letter Channel]
                        B -->|Yes| D[Consumer]
                    """);
                    "Essential for reliability. Dead letter channels preserve failed messages for inspection, retry, or manual intervention.");

            var dl = new DeadLetter<String>();
            dl.dead("lost-msg", "no consumer registered");

            assertThat(dl.size()).isEqualTo(1);
            assertThat(dl.drain().getFirst().reason()).isEqualTo("no consumer registered");
        }
    }

    @Nested
    @DisplayName("GuaranteedDelivery")
    class GuaranteedDeliveryTests {

        @Test
        @DisplayName("acknowledge removes pending delivery")
        void acknowledgeRemoves() {
                    "Ensures reliable message delivery by tracking unacknowledged messages and supporting retry logic.");
                    """
                    var gd = new GuaranteedDelivery<String>(
                        msg -> counter.incrementAndGet(),
                        Duration.ofSeconds(1), 3);
                    String id = gd.send("order-1");
                    gd.acknowledge(id);
                    """,
                    "java");
                    "Use when message loss is unacceptable, such as financial transactions or critical commands. Requires idempotent consumers.");

            var counter = new AtomicInteger(0);
            var gd =
                    new GuaranteedDelivery<String>(
                            msg -> counter.incrementAndGet(), Duration.ofSeconds(1), 3);

            String id = gd.send("order-1");
            assertThat(counter.get()).isEqualTo(1);
            assertThat(gd.pendingCount()).isEqualTo(1);

            gd.acknowledge(id);
            assertThat(gd.pendingCount()).isZero();
        }

        @Test
        @DisplayName("retry re-delivers unacknowledged messages")
        void retryDelivers() {
                    """
                    gd.send("order-1");
                    gd.retryPending();
                    """,
                    "java");
                    "Retry logic should be exponential backoff to avoid overwhelming the system during failures.");

            var counter = new AtomicInteger(0);
            var gd =
                    new GuaranteedDelivery<String>(
                            msg -> counter.incrementAndGet(), Duration.ofSeconds(1), 3);

            gd.send("order-1");
            gd.retryPending();

            assertThat(counter.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("MessageBus")
    class MessageBusTests {

        @Test
        @DisplayName("dispatches commands and broadcasts notifications")
        void commandsAndNotifications() {
                    "A central messaging backbone that supports both point-to-point commands and publish-subscribe notifications.");
                    """
                    var bus = new MessageBus<String>();
                    bus.registerCommandHandler("buy", commandResult::set);
                    bus.registerNotificationInterest("executed", notified::add);
                    bus.dispatchCommand("buy", "AAPL");
                    int count = bus.broadcastNotification("executed", "AAPL-done");
                    """,
                    "java");
                    """
                    graph LR
                        A[Command] --> B[Message Bus]
                        B --> C[Command Handler]
                        D[Event] --> B
                        B --> E[Notification Subscriber 1]
                        B --> F[Notification Subscriber 2]
                    """);
                    "Use as a central nervous system for application integration, decoupling senders from receivers while supporting both commands and events.");

            var bus = new MessageBus<String>();
            var commandResult = new AtomicReference<String>();
            var notified = new CopyOnWriteArrayList<String>();

            bus.registerCommandHandler("buy", commandResult::set);
            bus.registerNotificationInterest("executed", notified::add);
            bus.registerNotificationInterest("executed", notified::add);

            assertThat(bus.dispatchCommand("buy", "AAPL")).isTrue();
            assertThat(commandResult.get()).isEqualTo("AAPL");

            int count = bus.broadcastNotification("executed", "AAPL-done");
            assertThat(count).isEqualTo(2);
            assertThat(notified).containsExactly("AAPL-done", "AAPL-done");
        }
    }

    @Nested
    @DisplayName("MessagingBridge")
    class MessagingBridgeTests {

        @Test
        @DisplayName("translates and forwards messages")
        void translates() throws InterruptedException {
                    "Connects two different messaging systems by translating message formats and forwarding messages between them.");
                    """
                    var bridge = MessagingBridge.create(
                        Integer.class, String.class,
                        i -> "num:" + i,
                        s -> { received.set(s); latch.countDown(); });
                    bridge.forward(42);
                    """,
                    "java");
                    """
                    graph LR
                        A[System A] -->|Integer| B[Messaging Bridge]
                    B -->|Translation|
                    B -->|String| C[System B]
                    """);
                    "Use when integrating heterogeneous systems that use different message formats or protocols.");

            var latch = new CountDownLatch(1);
            var received = new AtomicReference<String>();

            var bridge =
                    MessagingBridge.create(
                            Integer.class,
                            String.class,
                            i -> "num:" + i,
                            s -> {
                                received.set(s);
                                latch.countDown();
                            });

            bridge.forward(42);
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo("num:42");
            bridge.stop();
        }
    }
}
