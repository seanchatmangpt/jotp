package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/** Unit tests for {@link EventManager} timeout configurations. */
@DisplayName("EventManager configurable timeouts")
class EventManagerTimeoutTest implements WithAssertions {
    /** Simple test event. */
    record TestEvent(String message) {}
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
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
        @DisplayName("syncNotify completes successfully with fast handler")
        void syncNotifyCompletes() throws InterruptedException {
            AtomicInteger count = new AtomicInteger(0);
            manager.addHandler(event -> count.incrementAndGet());
            manager.syncNotify(new TestEvent("test"));
            assertThat(count.get()).isEqualTo(1);
        @DisplayName("call() completes successfully with fast handler")
        void callCompletes() {
            EventManager.Handler<TestEvent> handler = event -> count.incrementAndGet();
            manager.addHandler(handler);
            manager.call(handler, new TestEvent("test"));
        @DisplayName("deleteHandler completes successfully")
        void deleteHandlerCompletes() {
            EventManager.Handler<TestEvent> handler = event -> {};
            boolean removed = manager.deleteHandler(handler);
            assertThat(removed).isTrue();
    @DisplayName("EventManager.start(Duration) — custom timeout factory")
    class CustomTimeoutFactory {
        @DisplayName("creates manager with specified timeout")
        void createsWithCustomTimeout() {
            Duration customTimeout = Duration.ofSeconds(10);
            EventManager<TestEvent> manager = EventManager.start(customTimeout);
        @DisplayName("rejects null timeout")
        void rejectsNullTimeout() {
            assertThatThrownBy(() -> EventManager.start((java.time.Duration) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout cannot be null");
        @DisplayName("rejects zero timeout")
        void rejectsZeroTimeout() {
            assertThatThrownBy(() -> EventManager.start(Duration.ZERO))
                    .hasMessageContaining("timeout must be positive");
        @DisplayName("rejects negative timeout")
        void rejectsNegativeTimeout() {
            assertThatThrownBy(() -> EventManager.start(Duration.ofSeconds(-1)))
        @DisplayName("accepts large timeout")
        void acceptsLargeTimeout() {
            Duration largeTimeout = Duration.ofSeconds(60);
            EventManager<TestEvent> manager = EventManager.start(largeTimeout);
        @DisplayName("accepts small timeout")
        void acceptsSmallTimeout() {
            Duration smallTimeout = Duration.ofMillis(100);
            EventManager<TestEvent> manager = EventManager.start(smallTimeout);
    @DisplayName("syncNotify(event, timeout) — custom timeout per-call")
    class SyncNotifyCustomTimeout {
        @DisplayName("uses instance timeout when not overridden")
        void usesInstanceTimeout() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(10));
        @DisplayName("accepts custom timeout override")
        void customTimeoutOverride() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(5));
            manager.syncNotify(new TestEvent("test"), Duration.ofSeconds(10));
        @DisplayName("rejects null timeout override")
        void rejectsNullTimeoutOverride() {
            assertThatThrownBy(() -> manager.syncNotify(new TestEvent("test"), null))
        @DisplayName("rejects zero timeout override")
        void rejectsZeroTimeoutOverride() {
            assertThatThrownBy(() -> manager.syncNotify(new TestEvent("test"), Duration.ZERO))
        @DisplayName("rejects negative timeout override")
        void rejectsNegativeTimeoutOverride() {
            assertThatThrownBy(
                            () -> manager.syncNotify(new TestEvent("test"), Duration.ofSeconds(-1)))
        @DisplayName("fast operation completes with short timeout")
        void fastOperationCompletesWithShortTimeout() throws InterruptedException {
            manager.syncNotify(new TestEvent("test"), Duration.ofMillis(100));
        @DisplayName("operation with multiple handlers respects timeout")
        void multipleHandlersRespectTimeout() throws InterruptedException {
            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);
            manager.addHandler(event -> count1.incrementAndGet());
            manager.addHandler(event -> count2.incrementAndGet());
            manager.syncNotify(new TestEvent("test"), Duration.ofSeconds(5));
            assertThat(count1.get()).isEqualTo(1);
            assertThat(count2.get()).isEqualTo(1);
    @DisplayName("deleteHandler(handler, timeout) — custom timeout per-call")
    class DeleteHandlerCustomTimeout {
        void usesInstanceTimeout() {
        void customTimeoutOverride() {
            boolean removed = manager.deleteHandler(handler, Duration.ofSeconds(10));
            assertThatThrownBy(() -> manager.deleteHandler(handler, null))
            assertThatThrownBy(() -> manager.deleteHandler(handler, Duration.ZERO))
            assertThatThrownBy(() -> manager.deleteHandler(handler, Duration.ofSeconds(-1)))
        @DisplayName("deletion completes with short timeout")
        void deletionCompletesWithShortTimeout() {
            boolean removed = manager.deleteHandler(handler, Duration.ofMillis(100));
        @DisplayName("deletion of non-existent handler returns false")
        void deletionOfNonExistentHandlerReturnsFalse() {
            boolean removed = manager.deleteHandler(handler, Duration.ofSeconds(5));
            assertThat(removed).isFalse();
    @DisplayName("call(handler, event, timeout) — custom timeout per-call")
    class CallCustomTimeout {
            manager.call(handler, new TestEvent("test"), Duration.ofSeconds(10));
            assertThatThrownBy(() -> manager.call(handler, new TestEvent("test"), null))
            assertThatThrownBy(() -> manager.call(handler, new TestEvent("test"), Duration.ZERO))
                            () ->
                                    manager.call(
                                            handler, new TestEvent("test"), Duration.ofSeconds(-1)))
        @DisplayName("handler call completes with short timeout")
        void callCompletesWithShortTimeout() {
            manager.call(handler, new TestEvent("test"), Duration.ofMillis(100));
        @DisplayName("call to unregistered handler is no-op")
        void callToUnregisteredHandlerIsNoOp() {
            manager.call(handler, new TestEvent("test"), Duration.ofSeconds(5));
            assertThat(count.get()).isEqualTo(0);
    @DisplayName("Backward compatibility — overloads without timeout param")
    class BackwardCompatibility {
        @DisplayName("syncNotify(event) uses instance timeout")
        void syncNotifyNoTimeoutUsesInstance() throws InterruptedException {
            EventManager<TestEvent> manager = EventManager.start(Duration.ofSeconds(3));
        @DisplayName("deleteHandler(handler) uses instance timeout")
        void deleteHandlerNoTimeoutUsesInstance() {
        @DisplayName("call(handler, event) uses instance timeout")
        void callNoTimeoutUsesInstance() {
    @DisplayName("Concurrent operations with different timeouts")
    class ConcurrentOperations {
        @DisplayName("multiple handlers process concurrently")
        void multipleHandlersProcessConcurrently() throws InterruptedException {
            AtomicInteger count3 = new AtomicInteger(0);
            manager.addHandler(event -> count3.incrementAndGet());
            assertThat(count3.get()).isEqualTo(1);
        @DisplayName("different calls can have different timeouts")
        void differentCallsDifferentTimeouts() throws InterruptedException {
            EventManager.Handler<TestEvent> handler1 = event -> count1.incrementAndGet();
            EventManager.Handler<TestEvent> handler2 = event -> count2.incrementAndGet();
            manager.addHandler(handler1);
            manager.addHandler(handler2);
            manager.syncNotify(new TestEvent("event1"), Duration.ofMillis(500));
            manager.syncNotify(new TestEvent("event2"), Duration.ofSeconds(10));
            assertThat(count1.get()).isEqualTo(2);
            assertThat(count2.get()).isEqualTo(2);
    @DisplayName("Handler fault isolation with timeouts")
    class HandlerFaultIsolation {
        @DisplayName("handler exception does not affect timeout behavior")
        void handlerExceptionDoesNotAffectTimeout() throws InterruptedException {
            AtomicInteger goodHandlerCount = new AtomicInteger(0);
            manager.addHandler(
                    event -> {
                        throw new RuntimeException("Handler crash");
                    });
            manager.addHandler(event -> goodHandlerCount.incrementAndGet());
            assertThat(goodHandlerCount.get()).isEqualTo(1);
        @DisplayName("crashing handler is removed silently")
        void crashingHandlerRemovedSilently() throws InterruptedException {
            AtomicInteger crashingHandlerCount = new AtomicInteger(0);
            EventManager.Handler<TestEvent> crashingHandler =
                        crashingHandlerCount.incrementAndGet();
                        throw new RuntimeException("Intentional crash");
                    };
            manager.addHandler(crashingHandler);
            manager.syncNotify(new TestEvent("event1"));
            manager.syncNotify(new TestEvent("event2"));
            assertThat(crashingHandlerCount.get()).isEqualTo(1);
            assertThat(goodHandlerCount.get()).isEqualTo(2);
}
