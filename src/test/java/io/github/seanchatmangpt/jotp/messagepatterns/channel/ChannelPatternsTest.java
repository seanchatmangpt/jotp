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
