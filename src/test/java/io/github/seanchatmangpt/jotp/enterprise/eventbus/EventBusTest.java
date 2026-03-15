package io.github.seanchatmangpt.jotp.enterprise.eventbus;

import static org.awaitility.Awaitility.await;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
@DisplayName(
        "EventBus: Joe Armstrong gen_event pub-sub with ordered delivery and dead-letter queue")
class EventBusTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    private static EventBusConfig defaultConfig() {
        return EventBusConfig.builder().build();
    @Test
    @DisplayName("createWithValidConfig_returnsInstance: EventBus.create returns non-null instance")
    void createWithValidConfig_returnsInstance() {
        var config = defaultConfig();
        var bus = EventBus.create(config);
        assertThat(bus).isNotNull();
        bus.shutdown();
    @DisplayName("configBuilder_rejectsZeroBatchSize: batchSize=0 throws IllegalArgumentException")
    void configBuilder_rejectsZeroBatchSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventBusConfig.builder().batchSize(0).build());
    @DisplayName(
            "configBuilder_rejectsZeroBatchTimeout: batchTimeoutMs=0 throws IllegalArgumentException")
    void configBuilder_rejectsZeroBatchTimeout() {
                .isThrownBy(() -> EventBusConfig.builder().batchTimeoutMs(0).build());
            "configBuilder_rejectsNegativeMaxRetries: maxRetries=-1 throws IllegalArgumentException")
    void configBuilder_rejectsNegativeMaxRetries() {
                .isThrownBy(() -> EventBusConfig.builder().maxRetries(-1).build());
    @DisplayName("publish_noSubscribers_accepted: publish with no subscribers returns ACCEPTED")
    void publish_noSubscribers_accepted() {
        var bus = EventBus.create(defaultConfig());
        var result = bus.publish("test-event");
        assertThat(result.status()).isEqualTo(EventBus.PublishResult.Status.ACCEPTED);
            "subscribe_thenPublish_handlerReceivesEvent: subscribed handler is called when event published")
    void subscribe_thenPublish_handlerReceivesEvent() {
        var received = new AtomicBoolean(false);
        bus.subscribe("sub1", event -> received.set(true));
        bus.publish("hello");
        await().atMost(Duration.ofSeconds(3)).untilTrue(received);
    @DisplayName("unsubscribe_handlerNoLongerCalled: handler not called after unsubscribing")
    void unsubscribe_handlerNoLongerCalled() {
        var callCount = new AtomicInteger(0);
        bus.subscribe("sub1", event -> callCount.incrementAndGet());
        bus.publish("first");
        await().atMost(Duration.ofSeconds(3)).until(() -> callCount.get() == 1);
        bus.unsubscribe("sub1");
        bus.publish("second");
        // Give time for potential spurious second delivery
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(callCount.get()).isEqualTo(1);
    @DisplayName("publishWhenPaused_returnsRejected: publish after pause() returns REJECTED")
    void publishWhenPaused_returnsRejected() {
        bus.pause();
        var result = bus.publish("event");
        assertThat(result.status()).isEqualTo(EventBus.PublishResult.Status.REJECTED);
    @DisplayName("resume_afterPause_publishAccepted: publish after resume() returns ACCEPTED")
    void resume_afterPause_publishAccepted() {
        bus.resume();
            "subscriberFails_nextPublishStillWorks: bus continues working after a subscriber throws")
    void subscriberFails_nextPublishStillWorks() {
        var goodReceived = new AtomicBoolean(false);
        // Subscriber that throws
        bus.subscribe(
                "bad-sub",
                event -> {
                    throw new RuntimeException("deliberate failure");
                });
        // Subscriber that works
        bus.subscribe("good-sub", event -> goodReceived.set(true));
        // First publish: bad subscriber throws, but good subscriber should still run
        bus.publish("event1");
        await().atMost(Duration.ofSeconds(3)).untilTrue(goodReceived);
        // Second publish: bus should still accept events
        var result = bus.publish("event2");
            "getSubscribers_afterSubscribeAndUnsubscribe_correct: size reflects subscribe/unsubscribe")
    void getSubscribers_afterSubscribeAndUnsubscribe_correct() {
        bus.subscribe("sub1", event -> {});
        bus.subscribe("sub2", event -> {});
        assertThat(bus.getSubscribers()).hasSize(2);
        assertThat(bus.getSubscribers()).hasSize(1);
    @DisplayName("shutdown_doesNotThrow: calling shutdown does not throw any exception")
    void shutdown_doesNotThrow() {
        assertThatNoException().isThrownBy(bus::shutdown);
}
