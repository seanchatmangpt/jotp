package io.github.seanchatmangpt.jotp.messaging.channels;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Test suite for PointToPointChannel pattern. Verifies 1:1 message delivery, ordering, and state
 * management.
 */
@DisplayName("Point-to-Point Channel Pattern")
class PointToPointChannelTest {
    static class TestState {
        final Queue<Message> received = new ConcurrentLinkedQueue<>();
        int messageCount = 0;
        TestState addMessage(Message msg) {
            received.offer(msg);
            messageCount++;
            return this;
        }
    }
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    @Test
    @DisplayName("should deliver message to exactly one receiver")
    void testExactlyOneDelivery() throws Exception {
        // Given: a Point-to-Point channel (receiver)
        var receiver =
                PointToPointChannel.createReceiver(
                        (state, msg) -> state.addMessage(msg), new TestState());
        // When: sending a message
        var testMsg = Message.event("TEST_EVENT", "payload");
        PointToPointChannel.send(receiver, testMsg);
        Thread.sleep(100); // Let async processing complete
        // Then: message delivered exactly once
        var state = ProcSys.getState(receiver).get();
        assertThat(state.received).hasSize(1);
        assertThat(state.received.peek()).isEqualTo(testMsg);
    @DisplayName("should preserve message ordering")
    void testMessageOrdering() throws Exception {
        // Given: a receiver and multiple messages
        var messages =
                new Message[] {
                    Message.event("EVENT_1", "first"),
                    Message.event("EVENT_2", "second"),
                    Message.event("EVENT_3", "third")
                };
        // When: sending messages in order
        for (var msg : messages) {
            PointToPointChannel.send(receiver, msg);
        Thread.sleep(200); // Wait for processing
        // Then: messages received in same order
        assertThat(state.messageCount).isEqualTo(3);
        assertThat(state.received).containsExactly(messages[0], messages[1], messages[2]);
    @DisplayName("should support state mutation across messages")
    void testStateMutation() throws Exception {
        // Given: a receiver with mutable state
        class CounterState {
            int count = 0;
            List<String> events = new ArrayList<>();
            CounterState increment(String eventType) {
                count++;
                events.add(eventType);
                return this;
            }
                        (CounterState state, Message msg) -> {
                            if (msg instanceof Message.EventMsg evt) {
                                return state.increment(evt.eventType());
                            }
                            return state;
                        },
                        new CounterState());
        // When: sending multiple different events
        String[] events = {"LOGIN", "PURCHASE", "LOGOUT"};
        for (String event : events) {
            PointToPointChannel.send(receiver, Message.event(event, null));
        Thread.sleep(200);
        // Then: state accumulated correctly
        assertThat(state.count).isEqualTo(3);
        assertThat(state.events).containsExactly("LOGIN", "PURCHASE", "LOGOUT");
    @DisplayName("should handle different message types (sealed interface)")
    void testMessageTypeHandling() throws Exception {
        // Given: receiver that handles different message types
        class TypeTrackerState {
            List<String> types = new ArrayList<>();
                        (TypeTrackerState state, Message msg) -> {
                            state.types.add(
                                    switch (msg) {
                                        case Message.EventMsg __ -> "EVENT";
                                        case Message.CommandMsg __ -> "COMMAND";
                                        case Message.QueryMsg __ -> "QUERY";
                                        case Message.DocumentMsg __ -> "DOCUMENT";
                                    });
                        new TypeTrackerState());
        // When: sending different message types
        PointToPointChannel.send(receiver, Message.event("test", null));
        PointToPointChannel.send(receiver, Message.command("cmd", null, null));
        PointToPointChannel.send(receiver, Message.query("q", null));
        PointToPointChannel.send(receiver, Message.document("doc", new byte[] {1}));
        // Then: all types handled
        assertThat(state.types).containsExactly("EVENT", "COMMAND", "QUERY", "DOCUMENT");
    @DisplayName("should support pipeline routing")
    void testPipelineRouting() throws Exception {
        // Given: a 3-stage pipeline
        class PipelineState {
            String currentValue = "";
            int stagesPassed = 0;
            PipelineState processAt(String stage) {
                currentValue = stage;
                stagesPassed++;
        var initialState = new PipelineState();
        var pipeline =
                PointToPointChannel.createPipeline(
                        initialState,
                        (PipelineState state, Message msg) -> state.processAt("STAGE_1"),
                        (PipelineState state, Message msg) -> state.processAt("STAGE_2"),
                        (PipelineState state, Message msg) -> state.processAt("STAGE_3"));
        // When: routing message through pipeline
        var msg = Message.event("FLOW", null);
        PointToPointChannel.routeThrough(pipeline, msg);
        // Then: message passed through all stages
        assertThat(pipeline).hasLength(3);
        var finalState = ProcSys.getState(pipeline[2]).get();
        assertThat(finalState.stagesPassed).isEqualTo(1);
        assertThat(finalState.currentValue).isEqualTo("STAGE_3");
    @DisplayName("should have unique message IDs")
    void testMessageIdUniqueness() throws Exception {
        // Given: receiver collecting messages
        // When: sending multiple messages
                    Message.event("E1", null), Message.event("E2", null), Message.event("E3", null)
        // Then: all message IDs are unique
        var ids = state.received.stream().map(Message::messageId).distinct().count();
        assertThat(ids).isEqualTo(3);
    @DisplayName("should preserve message timestamps")
    void testMessageTimestamps() throws Exception {
        // Given: receiver
        // When: sending message
        long beforeTime = System.currentTimeMillis();
        var msg = Message.event("TEST", null);
        long afterTime = System.currentTimeMillis();
        PointToPointChannel.send(receiver, msg);
        Thread.sleep(100);
        // Then: timestamp is within expected range
        var received = state.received.peek();
        assertThat(received.createdAt()).isBetween(beforeTime, afterTime);
    @DisplayName("should isolate state between concurrent channels")
    void testStateIsolation() throws Exception {
        // Given: two independent receivers
        var receiver1 =
        var receiver2 =
        // When: sending different messages to each
        var msg1 = Message.event("EVENT_1", null);
        var msg2 = Message.event("EVENT_2", null);
        PointToPointChannel.send(receiver1, msg1);
        PointToPointChannel.send(receiver2, msg2);
        // Then: each receiver has its own isolated state
        var state1 = ProcSys.getState(receiver1).get();
        var state2 = ProcSys.getState(receiver2).get();
        assertThat(state1.received).containsOnly(msg1);
        assertThat(state2.received).containsOnly(msg2);
}
