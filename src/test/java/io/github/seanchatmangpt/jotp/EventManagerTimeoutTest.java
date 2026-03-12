package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EventManager} timeout configurations. */
@DisplayName("EventManager configurable timeouts")
class EventManagerTimeoutTest implements WithAssertions {

    /**
     * Simple test event.
     */
    record TestEvent(String message) {}

    @Nested
    @DisplayName("EventManager.start() — default timeout factory")
    class DefaultTimeoutFactory {

        @Test
        @DisplayName("creates manager with 5-second default timeout")
        void createsWithDefaultTimeout() {
            EventManager<TestEvent> manager = EventManager.start();
            assertThat(manager).isNotNull();
            manager.stop();
        }

        @Test
        @DisplayName("syncNotify completes successfully with fast handler")
        void syncNotifyCompletes() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start();
            AtomicInteger count = new AtomicInteger(0);
            manager.addHandler(event -> count.incrementAndGet());

            manager.syncNotify(new TestEvent("test"));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("call() completes successfully with fast handler")
        void callCompletes() {
            EventManager<TestEvent> manager = EventManager.start();
            AtomicInteger count = new AtomicInteger(0);
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();
            manager.addHandler(handler);

            manager.call(handler, new TestEvent("test"));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("deleteHandler completes successfully")
        void deleteHandlerCompletes() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};
            manager.addHandler(handler);

            boolean removed = manager.deleteHandler(handler);

            assertThat(removed).isTrue();
            manager.stop();
        }
    }

    @Nested
    @DisplayName("EventManager.start(Duration) — custom timeout factory")
    class CustomTimeoutFactory {

        @Test
        @DisplayName("creates manager with specified timeout")
        void createsWithCustomTimeout() {
            Duration customTimeout = Duration.ofSeconds(10);
            EventManager<TestEvent> manager = EventManager.start(customTimeout);
            assertThat(manager).isNotNull();
            manager.stop();
        }

        @Test
        @DisplayName("rejects null timeout")
        void rejectsNullTimeout() {
            assertThatThrownBy(() -> EventManager.start(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout cannot be null");
        }

        @Test
        @DisplayName("rejects zero timeout")
        void rejectsZeroTimeout() {
            assertThatThrownBy(() -> EventManager.start(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");
        }

        @Test
        @DisplayName("rejects negative timeout")
        void rejectsNegativeTimeout() {
            assertThatThrownBy(() -> EventManager.start(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");
        }

        @Test
        @DisplayName("accepts large timeout")
        void acceptsLargeTimeout() {
            Duration largeTimeout = Duration.ofSeconds(60);
            EventManager<TestEvent> manager = EventManager.start(largeTimeout);
            assertThat(manager).isNotNull();
            manager.stop();
        }

        @Test
        @DisplayName("accepts small timeout")
        void acceptsSmallTimeout() {
            Duration smallTimeout = Duration.ofMillis(100);
            EventManager<TestEvent> manager = EventManager.start(smallTimeout);
            assertThat(manager).isNotNull();
            manager.stop();
        }
    }

    @Nested
    @DisplayName("syncNotify(event, timeout) — custom timeout per-call")
    class SyncNotifyCustomTimeout {

        @Test
        @DisplayName("uses instance timeout when not overridden")
        void usesInstanceTimeout() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(10));
            AtomicInteger count = new AtomicInteger(0);
            manager.addHandler(event -> count.incrementAndGet());

            manager.syncNotify(new TestEvent("test"));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("accepts custom timeout override")
        void customTimeoutOverride() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            AtomicInteger count = new AtomicInteger(0);
            manager.addHandler(event -> count.incrementAndGet());

            manager.syncNotify(new TestEvent("test"), Duration.ofSeconds(10));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("rejects null timeout override")
        void rejectsNullTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();

            assertThatThrownBy(
                    () -> manager.syncNotify(new TestEvent("test"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout cannot be null");

            manager.stop();
        }

        @Test
        @DisplayName("rejects zero timeout override")
        void rejectsZeroTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();

            assertThatThrownBy(
                    () -> manager.syncNotify(new TestEvent("test"), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            manager.stop();
        }

        @Test
        @DisplayName("rejects negative timeout override")
        void rejectsNegativeTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();

            assertThatThrownBy(
                    () -> manager.syncNotify(new TestEvent("test"), Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            manager.stop();
        }

        @Test
        @DisplayName("fast operation completes with short timeout")
        void fastOperationCompletesWithShortTimeout() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start();
            AtomicInteger count = new AtomicInteger(0);
            manager.addHandler(event -> count.incrementAndGet());

            manager.syncNotify(new TestEvent("test"), Duration.ofMillis(100));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("operation with multiple handlers respects timeout")
        void multipleHandlersRespectTimeout() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start();
            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);
            manager.addHandler(event -> count1.incrementAndGet());
            manager.addHandler(event -> count2.incrementAndGet());

            manager.syncNotify(new TestEvent("test"), Duration.ofSeconds(5));

            assertThat(count1.get()).isEqualTo(1);
            assertThat(count2.get()).isEqualTo(1);
            manager.stop();
        }
    }

    @Nested
    @DisplayName("deleteHandler(handler, timeout) — custom timeout per-call")
    class DeleteHandlerCustomTimeout {

        @Test
        @DisplayName("uses instance timeout when not overridden")
        void usesInstanceTimeout() {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(10));
            EventManager.Handler<TestEvent> handler = event -> {};
            manager.addHandler(handler);

            boolean removed = manager.deleteHandler(handler);

            assertThat(removed).isTrue();
            manager.stop();
        }

        @Test
        @DisplayName("accepts custom timeout override")
        void customTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            EventManager.Handler<TestEvent> handler = event -> {};
            manager.addHandler(handler);

            boolean removed = manager.deleteHandler(handler, Duration.ofSeconds(10));

            assertThat(removed).isTrue();
            manager.stop();
        }

        @Test
        @DisplayName("rejects null timeout override")
        void rejectsNullTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            assertThatThrownBy(() -> manager.deleteHandler(handler, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout cannot be null");

            manager.stop();
        }

        @Test
        @DisplayName("rejects zero timeout override")
        void rejectsZeroTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            assertThatThrownBy(
                    () -> manager.deleteHandler(handler, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            manager.stop();
        }

        @Test
        @DisplayName("rejects negative timeout override")
        void rejectsNegativeTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            assertThatThrownBy(
                    () -> manager.deleteHandler(handler, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            manager.stop();
        }

        @Test
        @DisplayName("deletion completes with short timeout")
        void deletionCompletesWithShortTimeout() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};
            manager.addHandler(handler);

            boolean removed = manager.deleteHandler(handler, Duration.ofMillis(100));

            assertThat(removed).isTrue();
            manager.stop();
        }

        @Test
        @DisplayName("deletion of non-existent handler returns false")
        void deletionOfNonExistentHandlerReturnsFalse() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            boolean removed =
                    manager.deleteHandler(handler, Duration.ofSeconds(5));

            assertThat(removed).isFalse();
            manager.stop();
        }
    }

    @Nested
    @DisplayName("call(handler, event, timeout) — custom timeout per-call")
    class CallCustomTimeout {

        @Test
        @DisplayName("uses instance timeout when not overridden")
        void usesInstanceTimeout() {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(10));
            AtomicInteger count = new AtomicInteger(0);
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();
            manager.addHandler(handler);

            manager.call(handler, new TestEvent("test"));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("accepts custom timeout override")
        void customTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            AtomicInteger count = new AtomicInteger(0);
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();
            manager.addHandler(handler);

            manager.call(handler, new TestEvent("test"), Duration.ofSeconds(10));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("rejects null timeout override")
        void rejectsNullTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            assertThatThrownBy(
                    () -> manager.call(handler, new TestEvent("test"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout cannot be null");

            manager.stop();
        }

        @Test
        @DisplayName("rejects zero timeout override")
        void rejectsZeroTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            assertThatThrownBy(
                    () -> manager.call(handler, new TestEvent("test"), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            manager.stop();
        }

        @Test
        @DisplayName("rejects negative timeout override")
        void rejectsNegativeTimeoutOverride() {
            EventManager<TestEvent> manager = EventManager.start();
            EventManager.Handler<TestEvent> handler = event -> {};

            assertThatThrownBy(
                    () -> manager.call(
                            handler, new TestEvent("test"), Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");

            manager.stop();
        }

        @Test
        @DisplayName("handler call completes with short timeout")
        void callCompletesWithShortTimeout() {
            EventManager<TestEvent> manager = EventManager.start();
            AtomicInteger count = new AtomicInteger(0);
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();
            manager.addHandler(handler);

            manager.call(handler, new TestEvent("test"), Duration.ofMillis(100));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("call to unregistered handler is no-op")
        void callToUnregisteredHandlerIsNoOp() {
            EventManager<TestEvent> manager = EventManager.start();
            AtomicInteger count = new AtomicInteger(0);
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();

            manager.call(handler, new TestEvent("test"), Duration.ofSeconds(5));

            assertThat(count.get()).isEqualTo(0);
            manager.stop();
        }
    }

    @Nested
    @DisplayName("Backward compatibility — overloads without timeout param")
    class BackwardCompatibility {

        @Test
        @DisplayName("syncNotify(event) uses instance timeout")
        void syncNotifyNoTimeoutUsesInstance() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(3));
            AtomicInteger count = new AtomicInteger(0);
            manager.addHandler(event -> count.incrementAndGet());

            manager.syncNotify(new TestEvent("test"));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("deleteHandler(handler) uses instance timeout")
        void deleteHandlerNoTimeoutUsesInstance() {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(3));
            EventManager.Handler<TestEvent> handler = event -> {};
            manager.addHandler(handler);

            boolean removed = manager.deleteHandler(handler);

            assertThat(removed).isTrue();
            manager.stop();
        }

        @Test
        @DisplayName("call(handler, event) uses instance timeout")
        void callNoTimeoutUsesInstance() {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(3));
            AtomicInteger count = new AtomicInteger(0);
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();
            manager.addHandler(handler);

            manager.call(handler, new TestEvent("test"));

            assertThat(count.get()).isEqualTo(1);
            manager.stop();
        }
    }

    @Nested
    @DisplayName("Concurrent operations with different timeouts")
    class ConcurrentOperations {

        @Test
        @DisplayName("multiple handlers process concurrently")
        void multipleHandlersProcessConcurrently() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);
            AtomicInteger count3 = new AtomicInteger(0);

            manager.addHandler(event -> count1.incrementAndGet());
            manager.addHandler(event -> count2.incrementAndGet());
            manager.addHandler(event -> count3.incrementAndGet());

            manager.syncNotify(new TestEvent("test"));

            assertThat(count1.get()).isEqualTo(1);
            assertThat(count2.get()).isEqualTo(1);
            assertThat(count3.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("different calls can have different timeouts")
        void differentCallsDifferentTimeouts() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);

            EventManager.Handler<TestEvent> handler1 = event -> count1.incrementAndGet();
            EventManager.Handler<TestEvent> handler2 = event -> count2.incrementAndGet();

            manager.addHandler(handler1);
            manager.addHandler(handler2);

            manager.syncNotify(new TestEvent("event1"), Duration.ofMillis(500));
            manager.syncNotify(new TestEvent("event2"), Duration.ofSeconds(10));

            assertThat(count1.get()).isEqualTo(2);
            assertThat(count2.get()).isEqualTo(2);
            manager.stop();
        }
    }

    @Nested
    @DisplayName("Handler fault isolation with timeouts")
    class HandlerFaultIsolation {

        @Test
        @DisplayName("handler exception does not affect timeout behavior")
        void handlerExceptionDoesNotAffectTimeout() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            AtomicInteger goodHandlerCount = new AtomicInteger(0);

            manager.addHandler(
                    event -> {
                        throw new RuntimeException("Handler crash");
                    });
            manager.addHandler(event -> goodHandlerCount.incrementAndGet());

            manager.syncNotify(new TestEvent("test"));

            assertThat(goodHandlerCount.get()).isEqualTo(1);
            manager.stop();
        }

        @Test
        @DisplayName("crashing handler is removed silently")
        void crashingHandlerRemovedSilently() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            AtomicInteger goodHandlerCount = new AtomicInteger(0);
            AtomicInteger crashingHandlerCount = new AtomicInteger(0);

            EventManager.Handler<TestEvent> crashingHandler =
                    event -> {
                        crashingHandlerCount.incrementAndGet();
                        throw new RuntimeException("Intentional crash");
                    };

            manager.addHandler(crashingHandler);
            manager.addHandler(event -> goodHandlerCount.incrementAndGet());

            manager.syncNotify(new TestEvent("event1"));
            manager.syncNotify(new TestEvent("event2"));

            assertThat(crashingHandlerCount.get()).isEqualTo(1);
            assertThat(goodHandlerCount.get()).isEqualTo(2);
            manager.stop();
        }
    }
}
