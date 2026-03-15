package io.github.seanchatmangpt.jotp.messaging.channels;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * Test suite for PublishSubscribeChannel pattern. Verifies 1:N broadcast delivery, subscriber
 * isolation, and event ordering.
 */
@DisplayName("Publish-Subscribe Channel Pattern")
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
    @DisplayName("should handle multiple published events for each subscriber")
    void testMultipleEventsToMultipleSubscribers() throws InterruptedException {
        // Given: two subscribers
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
    @DisplayName("should track subscriber count correctly")
    void testSubscriberCount() {
        // Given: channel with no subscribers
        assertThat(channel.subscriberCount()).isZero();
        // When: adding subscribers
        channel.subscribe(msg -> {});
        assertThat(channel.subscriberCount()).isOne();
        assertThat(channel.subscriberCount()).isEqualTo(2);
        assertThat(channel.subscriberCount()).isEqualTo(3);
    @DisplayName("should support unsubscribe")
    void testUnsubscribe() throws InterruptedException {
        var handler1 = (java.util.function.Consumer<Message>) sub1Events::add;
        var handler2 = (java.util.function.Consumer<Message>) sub2Events::add;
        channel.subscribe(handler1);
        var h2 = channel.subscribe(handler2);
        // When: unsubscribing one subscriber
        channel.unsubscribe(h2);
        // And: publishing
        var message = Message.event("TEST", null);
        Thread.sleep(100);
        // Then: only remaining subscriber received it
        assertThat(sub2Events).isEmpty();
    @DisplayName("should support unsubscribeAll")
    void testUnsubscribeAll() throws InterruptedException {
        // When: unsubscribing all
        channel.unsubscribeAll();
        // Then: count is zero
        // And: publishing has no effect
        channel.publish(Message.event("TEST", null));
        assertThat(sub1Events).isEmpty();
        assertThat(sub3Events).isEmpty();
    @DisplayName("should allow dynamic subscription during publishing")
    void testDynamicSubscription() throws InterruptedException {
        // Given: initial subscriber
        // When: publishing first message
        var msg1 = Message.event("E1", null);
        channel.publish(msg1);
        // And: adding new subscriber mid-stream
        // And: publishing second message
        var msg2 = Message.event("E2", null);
        channel.publish(msg2);
        // Then: sub1 got both, sub2 got only msg2 (joined late)
        assertThat(sub1Events).containsExactly(msg1, msg2);
        assertThat(sub2Events).containsExactly(msg2);
    @DisplayName("should isolate subscriber exceptions")
    void testSubscriberExceptionIsolation() throws InterruptedException {
        // Given: one throwing subscriber, one normal
        List<Message> okEvents = Collections.synchronizedList(new ArrayList<>());
        channel.subscribe(
                msg -> {
                    throw new RuntimeException("Simulated failure");
                });
        channel.subscribe(okEvents::add);
        // When: publishing (first subscriber throws)
        channel.publishSync(message); // Sync to ensure exception handling
        // Then: ok subscriber still received message despite other's exception
        assertThat(okEvents).containsExactly(message);
    @DisplayName("should support different message types")
    void testMessageTypeFiltering() throws InterruptedException {
        // Given: subscriber that filters by message type
        List<String> eventTypes = Collections.synchronizedList(new ArrayList<>());
                    if (msg instanceof Message.EventMsg evt) {
                        eventTypes.add("EVENT: " + evt.eventType());
                    } else if (msg instanceof Message.CommandMsg cmd) {
                        eventTypes.add("COMMAND: " + cmd.commandType());
                    }
        // When: publishing different message types
        channel.publish(Message.event("ORDER_PLACED", null));
        channel.publish(Message.command("PROCESS_ORDER", null, null));
        channel.publish(Message.event("SHIPMENT_SENT", null));
        // Then: types were captured correctly
        assertThat(eventTypes)
                .containsExactly(
                        "EVENT: ORDER_PLACED", "COMMAND: PROCESS_ORDER", "EVENT: SHIPMENT_SENT");
    @DisplayName("should preserve message uniqueness across subscribers")
    void testMessageIdConsistency() throws InterruptedException {
        List<Message> sub1 = Collections.synchronizedList(new ArrayList<>());
        List<Message> sub2 = Collections.synchronizedList(new ArrayList<>());
        channel.subscribe(sub1::add);
        channel.subscribe(sub2::add);
        // When: publishing
        var msg = Message.event("TEST", null);
        channel.publish(msg);
        // Then: both have the same message ID
        assertThat(sub1.get(0).messageId()).isEqualTo(sub2.get(0).messageId());
    @DisplayName("should handle high-throughput publishing")
    void testHighThroughput() throws InterruptedException {
        // When: publishing many messages rapidly
        int messageCount = 100;
        for (int i = 0; i < messageCount; i++) {
            channel.publish(Message.event("EVENT_" + i, null));
        }
        Thread.sleep(500); // Allow processing
        // Then: all messages delivered to all subscribers
        assertThat(sub1).hasSize(messageCount);
        assertThat(sub2).hasSize(messageCount);
    @DisplayName("should support publishSync for blocking behavior")
    void testPublishSync() throws InterruptedException {
        // Given: subscribers
        CountDownLatch processedLatch = new CountDownLatch(2);
                    sub1.add(msg);
                    processedLatch.countDown();
                    sub2.add(msg);
        // When: publishing synchronously
        int notified = channel.publishSync(msg);
        // Then: returns number of subscribers notified
        assertThat(notified).isEqualTo(2);
        assertThat(processedLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(sub1).containsExactly(msg);
        assertThat(sub2).containsExactly(msg);
}
