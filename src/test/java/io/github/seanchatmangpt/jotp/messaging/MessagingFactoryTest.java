/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.messaging;
import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.messagepatterns.construction.CorrelationIdentifier;
import io.github.seanchatmangpt.jotp.messagepatterns.management.SmartProxy;
import io.github.seanchatmangpt.jotp.messaging.endpoints.MessagingGateway;
import io.github.seanchatmangpt.jotp.messaging.endpoints.MessagingMapper;
import io.github.seanchatmangpt.jotp.messaging.system.ChannelPurger;
import io.github.seanchatmangpt.jotp.messaging.system.ControlBus;
import io.github.seanchatmangpt.jotp.messaging.system.Detour;
import io.github.seanchatmangpt.jotp.messaging.transformation.Normalizer;
import io.github.seanchatmangpt.jotp.testing.util.MessageBuilder.TestMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Comprehensive unit tests for Messaging.java factory methods.
 * <p>Tests all newly added factory methods including:
 * <ul>
 *   <li>Control Bus - system management and monitoring
 *   <li>Detour - conditional message routing
 *   <li>Channel Purger - periodic cleanup with various strategies
 *   <li>Test Message - test data creation
 *   <li>Canonical Model - message normalization
 *   <li>Smart Proxy - request-reply correlation
 *   <li>Messaging Gateway - synchronous and asynchronous gateways
 *   <li>Messaging Mapper - message transformation
 * </ul>
@DisplayName("Messaging Factory Methods")
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MessagingFactoryTest {
    // ═══════════════════════════════════════════════════════════════════════════════
    // CONTROL BUS TESTS
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("controlBus() should create a functional ControlBus")
    void testControlBusCreation() {
        // Arrange & Act
        ControlBus<String> controlBus = Messaging.controlBus();
        // Then
        assertThat(controlBus).isNotNull();
        assertThat(controlBus.listProcesses()).isEmpty();
    @DisplayName("controlBus() should allow process registration and management")
    void testControlBusProcessManagement() throws InterruptedException {
        // Arrange
        var testProc = Messaging.<String>pointToPoint(msg -> {});
        // Act
        controlBus.register("test-process", testProc);
        var processes = controlBus.listProcesses();
        assertThat(processes).contains("test-process");
    @DisplayName("controlBus() should provide statistics")
    void testControlBusStatistics() throws InterruptedException {
        controlBus.register("stats-process", testProc);
        var stats = controlBus.getStats();
        assertThat(stats).isNotNull();
        assertThat(stats).containsKey("stats-process");
    // DETOUR TESTS
    record Order(String id, boolean priority) {}
    @DisplayName("detour() should route matching messages to detour channel")
    void testDetourRoutesToDetourChannel() throws InterruptedException {
        List<Order> detourMessages = new ArrayList<>();
        List<Order> primaryMessages = new ArrayList<>();
        MessageChannel<Order> detourChannel = Messaging.<Order>pointToPoint(detourMessages::add);
        MessageChannel<Order> primaryChannel = Messaging.<Order>pointToPoint(primaryMessages::add);
        Detour<Order> detour =
                Messaging.detour(order -> order.priority(), detourChannel, primaryChannel);
        detour.send(new Order("order-1", true));
        Thread.sleep(100); // Allow async processing
        assertThat(detourMessages).hasSize(1);
        assertThat(detourMessages.get(0).id()).isEqualTo("order-1");
        assertThat(primaryMessages).isEmpty();
    @DisplayName("detour() should route non-matching messages to primary channel")
    void testDetourRoutesToPrimaryChannel() throws InterruptedException {
        detour.send(new Order("order-2", false));
        assertThat(primaryMessages).hasSize(1);
        assertThat(primaryMessages.get(0).id()).isEqualTo("order-2");
        assertThat(detourMessages).isEmpty();
    @DisplayName("detour() should validate null inputs")
    void testDetourValidatesInputs() {
        MessageChannel<Order> channel = Messaging.<Order>pointToPoint(msg -> {});
        // Act & Assert
        assertThatThrownBy(() -> Messaging.detour(null, channel, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("condition");
        assertThatThrownBy(() -> Messaging.detour(order -> true, null, channel))
                .hasMessageContaining("detour");
        assertThatThrownBy(() -> Messaging.detour(order -> true, channel, null))
                .hasMessageContaining("primary");
    // CHANNEL PURGER TESTS
    @DisplayName("channelPurger() should create a purger with custom strategy")
    void testChannelPurgerCreation() {
        var channel = Messaging.<String>pointToPoint(msg -> {});
        var strategy = ChannelPurger.PurgeStrategy.byCount(100);
        ChannelPurger<String> purger =
                Messaging.channelPurger(channel, strategy, Duration.ofMinutes(5));
        assertThat(purger).isNotNull();
        purger.stop();
    @DisplayName("channelPurgerByAge() should create age-based purger")
    void testChannelPurgerByAge() {
                Messaging.channelPurgerByAge(channel, Duration.ofHours(1), Duration.ofMinutes(5));
    @DisplayName("channelPurgerByCount() should create count-based purger")
    void testChannelPurgerByCount() {
                Messaging.channelPurgerByCount(channel, 1000, Duration.ofMinutes(5));
    @DisplayName("channelPurgerByPredicate() should create predicate-based purger")
    void testChannelPurgerByPredicate() {
                Messaging.channelPurgerByPredicate(
                        channel, msg -> msg.startsWith("OLD:"), Duration.ofMinutes(5));
    // TEST MESSAGE TESTS
    @DisplayName("testMessage() with fields should create populated message")
    void testTestMessageWithFields() {
        Map<String, Object> fields =
                Map.of(
                        "id", "123",
                        "name", "Test Order",
                        "items", List.of("item1", "item2"));
        TestMessage message = Messaging.<Order>testMessage("OrderMessage", fields);
        assertThat(message).isNotNull();
        assertThat(message.type).isEqualTo("OrderMessage");
        assertThat(message.fields).hasSize(3);
        assertThat(message.fields.get("id")).isEqualTo("123");
        assertThat(message.fields.get("name")).isEqualTo("Test Order");
    @DisplayName("testMessage() without fields should create empty message")
    void testTestMessageWithoutFields() {
        TestMessage message = Messaging.<Order>testMessage("EmptyMessage");
        assertThat(message.type).isEqualTo("EmptyMessage");
        assertThat(message.fields).isEmpty();
    @DisplayName("testMessage() should preserve field order")
    void testTestMessagePreservesFieldOrder() {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("field1", "value1");
        fields.put("field2", "value2");
        fields.put("field3", "value3");
        TestMessage message = Messaging.testMessage("OrderedMessage", fields);
        assertThat(message.fields.keySet()).containsExactly("field1", "field2", "field3");
    // CANONICAL MODEL TESTS
    @DisplayName("canonicalModel() should create a functional Normalizer")
    void testCanonicalModelCreation() {
        Normalizer normalizer = Messaging.canonicalModel();
        assertThat(normalizer).isNotNull();
    @DisplayName("canonicalModel() should normalize JSON strings")
    void testCanonicalModelNormalizesJson() {
        String jsonInput = "{\"type\":\"ORDER\",\"id\":\"123\"}";
        var result = normalizer.toCanonical(jsonInput);
        assertThat(result).isNotNull();
    @DisplayName("canonicalModel() should normalize XML strings")
    void testCanonicalModelNormalizesXml() {
        String xmlInput = "<order><id>123</id></order>";
        var result = normalizer.toCanonical(xmlInput);
    @DisplayName("canonicalModel() should convert back to JSON format")
    void testCanonicalModelFromCanonicalToJson() {
        var message = io.github.seanchatmangpt.jotp.messaging.Message.event("TEST", "data");
        String jsonOutput = normalizer.fromCanonical(message, "JSON");
        assertThat(jsonOutput).isNotNull();
        assertThat(jsonOutput).isNotEmpty();
    // SMART PROXY TESTS
    record Request(String id, String data) {
        public CorrelationIdentifier correlationId() {
            return new CorrelationIdentifier(id);
        }
    record Reply(String id, String result) {
    @DisplayName("smartProxy() should create a functional proxy")
    void testSmartProxyCreation() {
        AtomicBoolean requestReceived = new AtomicBoolean(false);
        Consumer<Request> service = req -> requestReceived.set(true);
        SmartProxy<Request, Reply> proxy =
                Messaging.smartProxy(Request::correlationId, Reply::correlationId, service);
        assertThat(proxy).isNotNull();
        assertThat(proxy.pendingCount()).isZero();
    @DisplayName("smartProxy() should track pending requests")
    void testSmartProxyTracksPendingRequests() {
                Messaging.smartProxy(Request::correlationId, Reply::correlationId, req -> {});
        AtomicReference<Reply> replyRef = new AtomicReference<>();
        proxy.sendRequest(new Request("req-1", "data"), replyRef::set);
        assertThat(proxy.pendingCount()).isEqualTo(1);
    @DisplayName("smartProxy() should route replies to correct requesters")
    void testSmartProxyRoutesReplies() {
        AtomicReference<Reply> reply1 = new AtomicReference<>();
        AtomicReference<Reply> reply2 = new AtomicReference<>();
        proxy.sendRequest(new Request("req-1", "data1"), reply1::set);
        proxy.sendRequest(new Request("req-2", "data2"), reply2::set);
        proxy.deliverReply(new Reply("req-1", "result1"));
        assertThat(proxy.pendingCount()).isEqualTo(1); // Only req-2 pending
        assertThat(reply1.get()).isNotNull();
        assertThat(reply1.get().result()).isEqualTo("result1");
        assertThat(reply2.get()).isNull();
    @DisplayName("smartProxy() should handle unmatched replies")
    void testSmartProxyHandlesUnmatchedReplies() {
        boolean delivered = proxy.deliverReply(new Reply("unknown-req", "result"));
        assertThat(delivered).isFalse();
    // MESSAGING GATEWAY TESTS
    @DisplayName("gateway() request-reply should create synchronous gateway")
    void testGatewayRequestReplyCreation() {
        MessagingGateway<String, String> gateway =
                Messaging.gateway(channel, Duration.ofSeconds(5));
        assertThat(gateway).isNotNull();
    @DisplayName("gateway() one-way should create asynchronous gateway")
    void testGatewayOneWayCreation() {
        AtomicBoolean received = new AtomicBoolean(false);
        MessagingGateway<String, Void> gateway = Messaging.gateway(msg -> received.set(true));
    @DisplayName("gateway() one-way should send messages asynchronously")
    void testGatewayOneWaySendsAsync() throws InterruptedException {
        List<String> received = new ArrayList<>();
        MessagingGateway<String, Void> gateway = Messaging.gateway(received::add);
        gateway.sendAsync("message-1");
        gateway.sendAsync("message-2");
        assertThat(received).hasSize(2);
        assertThat(received).containsExactly("message-1", "message-2");
    @DisplayName("gateway() one-way should handle null messages gracefully")
    void testGatewayOneWayHandlesNull() {
        assertThatThrownBy(() -> gateway.sendAsync(null))
                .isInstanceOf(IllegalArgumentException.class);
    // MESSAGING MAPPER TESTS
    record ExternalOrder(String json) {}
    record InternalOrder(String id, String items) {}
    @DisplayName("mapper() should create mapper without error channel")
    void testMapperCreation() {
        var downstream = Messaging.<InternalOrder>pointToPoint(order -> {});
        MessagingMapper<ExternalOrder, InternalOrder> mapper =
                Messaging.mapper(ext -> new InternalOrder("parsed", "items"), downstream);
        assertThat(mapper).isNotNull();
    @DisplayName("mapper() should transform messages")
    void testMapperTransformsMessages() throws InterruptedException {
        List<InternalOrder> received = new ArrayList<>();
        var downstream = Messaging.<InternalOrder>pointToPoint(received::add);
                Messaging.mapper(
                        ext -> new InternalOrder("id-from-json", "items-from-json"), downstream);
        mapper.send(new ExternalOrder("{\"id\":\"123\",\"items\":\"a,b\"}"));
        assertThat(received).hasSize(1);
        assertThat(received.get(0).id()).isEqualTo("id-from-json");
    @DisplayName("mapper() with error channel should route failed mappings")
    void testMapperWithErrorChannel() throws InterruptedException {
        List<InternalOrder> successMessages = new ArrayList<>();
        List<ExternalOrder> errorMessages = new ArrayList<>();
        var downstream = Messaging.<InternalOrder>pointToPoint(successMessages::add);
        var errorChannel = Messaging.<ExternalOrder>pointToPoint(errorMessages::add);
                        ext -> {
                            if (ext.json().contains("invalid")) {
                                throw new IllegalArgumentException("Invalid JSON");
                            }
                            return new InternalOrder("parsed", "items");
                        },
                        downstream,
                        errorChannel);
        mapper.send(new ExternalOrder("invalid json"));
        assertThat(successMessages).isEmpty();
        assertThat(errorMessages).hasSize(1);
    @DisplayName("mapper() should handle successful mappings with error channel")
    void testMapperSuccessWithErrorChannel() throws InterruptedException {
                        ext -> new InternalOrder("parsed", "items"), downstream, errorChannel);
        mapper.send(new ExternalOrder("valid json"));
        assertThat(successMessages).hasSize(1);
        assertThat(errorMessages).isEmpty();
    @DisplayName("mapper() should validate null inputs")
    void testMapperValidatesInputs() {
        var channel = Messaging.<InternalOrder>pointToPoint(order -> {});
        assertThatThrownBy(() -> Messaging.mapper(null, channel))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Messaging.mapper(ext -> new InternalOrder("id", "items"), null))
    // INTEGRATION TESTS
    @DisplayName("Integration: Detour with ControlBus management")
    void testDetourWithControlBus() throws InterruptedException {
        List<Order> priorityOrders = new ArrayList<>();
        List<Order> normalOrders = new ArrayList<>();
        var priorityChannel = Messaging.<Order>pointToPoint(priorityOrders::add);
        var normalChannel = Messaging.<Order>pointToPoint(normalOrders::add);
        Detour<Order> detour = Messaging.detour(Order::priority, priorityChannel, normalChannel);
        assertThat(priorityOrders).hasSize(1);
        assertThat(normalOrders).hasSize(1);
    @DisplayName("Integration: Mapper with Gateway")
    void testMapperWithGateway() throws InterruptedException {
                        ext -> new InternalOrder("mapped-" + ext.json(), "items"), downstream);
        MessagingGateway<ExternalOrder, Void> gateway = Messaging.gateway(mapper::send);
        gateway.sendAsync(new ExternalOrder("json-data"));
        assertThat(received.get(0).id()).isEqualTo("mapped-json-data");
    @DisplayName("Integration: SmartProxy with multiple concurrent requests")
    void testSmartProxyConcurrency() {
        AtomicInteger requestCount = new AtomicInteger(0);
        Consumer<Request> service = req -> requestCount.incrementAndGet();
        List<AtomicReference<Reply>> replies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AtomicReference<Reply> ref = new AtomicReference<>();
            replies.add(ref);
            proxy.sendRequest(new Request("req-" + i, "data-" + i), ref::set);
            proxy.deliverReply(new Reply("req-" + i, "result-" + i));
        assertThat(requestCount.get()).isEqualTo(10);
            assertThat(replies.get(i).get()).isNotNull();
            assertThat(replies.get(i).get().result()).isEqualTo("result-" + i);
}
