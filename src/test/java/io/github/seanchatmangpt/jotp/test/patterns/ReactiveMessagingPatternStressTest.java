package io.github.seanchatmangpt.jotp.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcSys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
/**
 * Comprehensive throughput stress tests for ALL 39 Reactive Messaging Patterns.
 *
 * <p>Targets real-world throughput numbers for production readiness validation.
 * <h2>Pattern Categories</h2>
 * <ul>
 *   <li>Foundation Patterns (10) — Message Channel, Command, Document, Event, Request-Reply, Return
 *       Address, Correlation ID, Message Sequence, Message Expiration, Format Indicator
 *   <li>Routing Patterns (9) — Router, Content-Based Router, Recipient List, Splitter, Aggregator,
 *       Resequencer, Scatter-Gather, Routing Slip, Process Manager
 *   <li>Endpoint Patterns (14) — Channel Adapter, Messaging Bridge, Message Bus, Pipes and Filters,
 *       Message Dispatcher, Event-Driven Consumer, Competing Consumers, Selective Consumer,
 *       Idempotent Receiver, Service Activator, Message Translator, Content Filter, Claim Check,
 *       Normalizer
 * </ul>
 */
@Timeout(300)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Reactive Messaging Pattern Stress Tests — REAL NUMBERS")
class ReactiveMessagingPatternStressTest implements WithAssertions {
    // ── Message Types ───────────────────────────────────────────────────────────
    sealed interface Msg
            permits Msg.Inc, Msg.Get, Msg.Doc, Msg.Evt, Msg.Seq, Msg.Req, Msg.Cmd, Msg.Boom {
        record Inc(int by) implements Msg {}
        record Get() implements Msg {}
        record Doc(String payload, int version) implements Msg {}
        record Evt(String type, String source) implements Msg {}
        record Seq(int num, String payload) implements Msg {}
        record Req(UUID id, String query) implements Msg {}
        record Cmd(String action) implements Msg {}
        record Boom() implements Msg {}
    }
    record State(int counter, List<String> log, int lastSeq) {
        static State initial() {
            return new State(0, new ArrayList<>(), -1);
        }
    private static State handler(State s, Msg msg) {
        return switch (msg) {
            case Msg.Inc(int by) -> new State(s.counter() + by, s.log(), s.lastSeq());
            case Msg.Get _ -> s;
            case Msg.Doc(String payload, int version) -> {
                var log = new ArrayList<>(s.log());
                log.add("doc@" + version + ":" + payload);
                yield new State(s.counter(), log, s.lastSeq());
            }
            case Msg.Evt(String type, String source) -> {
                log.add("evt:" + type + "@" + source);
            case Msg.Seq(int num, String payload) -> {
                log.add("seq@" + num + ":" + payload);
                yield new State(s.counter(), log, num);
            case Msg.Req(UUID id, String query) -> s;
            case Msg.Cmd(String action) -> s;
            case Msg.Boom _ -> throw new RuntimeException("boom");
        };
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        org.acme.ProcRegistry.reset();
    @AfterEach
    void tearDown() {
    // ═══════════════════════════════════════════════════════════════════════════════
    // FOUNDATION PATTERNS STRESS TESTS (10 tests)
    @Nested
    @DisplayName("Foundation Patterns Stress Tests")
    class FoundationPatternsStressTests {
        @Test
        @DisplayName("1. Message Channel: 1M messages through Proc — target 2M+ msg/s")
        void messageChannel_1MMessages_2MPerSecond() throws Exception {
            int count = 1_000_000;
            var proc = new Proc<>(State.initial(), ReactiveMessagingPatternStressTest::handler);
            // Warm up
            for (int i = 0; i < 10_000; i++) proc.tell(new Msg.Inc(1));
            Thread.sleep(100);
            // Measure
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) proc.tell(new Msg.Inc(1));
            long elapsed = System.nanoTime() - start;
            // Wait for processing
            while (ProcSys.statistics(proc).messagesIn() < count * 0.99) Thread.sleep(10);
            double throughput = count * 1_000_000_000.0 / elapsed;
            System.out.printf(
                    "[message-channel] %,d messages in %,d ns = %,.0f msg/s%n",
                    count, elapsed, throughput);
            assertThat(throughput)
                    .as("Message Channel should exceed 2M msg/s")
                    .isGreaterThan(2_000_000);
            proc.stop();
        @DisplayName("2. Command Message: 500K command executions — target 1M+ cmd/s")
        void commandMessage_500KCommands_1MPerSecond() throws Exception {
            int count = 500_000;
            for (int i = 0; i < 5_000; i++) proc.tell(new Msg.Cmd("action-" + i));
            for (int i = 0; i < count; i++) proc.tell(new Msg.Cmd("execute-" + i));
            while (ProcSys.statistics(proc).messagesIn() < count + 5_000) Thread.sleep(10);
                    "[command-message] %,d commands in %,d ns = %,.0f cmd/s%n",
                    .as("Command Message should exceed 1M cmd/s")
                    .isGreaterThan(1_000_000);
        @DisplayName("3. Document Message: 100K document transfers — target 500K doc/s")
        void documentMessage_100KDocuments_500KPerSecond() throws Exception {
            int count = 100_000;
            for (int i = 0; i < 1_000; i++) proc.tell(new Msg.Doc("warmup", i));
            Thread.sleep(50);
            for (int i = 0; i < count; i++) proc.tell(new Msg.Doc("doc-" + i, i));
            while (ProcSys.statistics(proc).messagesIn() < count + 1_000) Thread.sleep(10);
                    "[document-message] %,d documents in %,d ns = %,.0f doc/s%n",
                    .as("Document Message should exceed 500K doc/s")
                    .isGreaterThan(500_000);
        @DisplayName("4. Event Message: 10K events × 100 handlers — target 1M+ deliveries/s")
        void eventMessage_10KEvents_100Handlers_1MDeliveries() throws Exception {
            int handlerCount = 100;
            int eventCount = 10_000;
            int expectedDeliveries = handlerCount * eventCount;
            var received = new LongAdder();
            var em = EventManager.<Msg.Evt>start();
            for (int i = 0; i < handlerCount; i++) em.addHandler(e -> received.increment());
            for (int i = 0; i < eventCount; i++) em.notify(new Msg.Evt("Event" + i, "stress-test"));
            while (received.sum() < expectedDeliveries * 0.99) Thread.sleep(10);
            double deliveriesPerSec = expectedDeliveries * 1_000_000_000.0 / elapsed;
                    "[event-message] handlers=%d events=%d deliveries=%,d in %d ms = %,.0f deliveries/s%n",
                    handlerCount,
                    eventCount,
                    received.sum(),
                    elapsed / 1_000_000,
                    deliveriesPerSec);
            assertThat(deliveriesPerSec)
                    .as("Event Message should exceed 1M deliveries/s")
            em.stop();
        @DisplayName("5. Request-Reply: 100K ask() round-trips — target 50K+ rt/s")
        void requestReply_100KRoundTrips_50KPerSecond() throws Exception {
            for (int i = 0; i < 1_000; i++) proc.ask(new Msg.Get()).get(1, TimeUnit.SECONDS);
            for (int i = 0; i < count; i++) proc.ask(new Msg.Get()).get(2, TimeUnit.SECONDS);
            double rtPerSec = count * 1_000_000_000.0 / elapsed;
                    "[request-reply] %,d round-trips in %,d ns = %,.0f rt/s%n",
                    count, elapsed, rtPerSec);
            assertThat(rtPerSec).as("Request-Reply should exceed 50K rt/s").isGreaterThan(50_000);
        @DisplayName("6. Return Address: 50K reply-to deliveries — target 500K/s")
        void returnAddress_50KReplies_500KPerSecond() throws Exception {
            int count = 50_000;
            var replies = new LongAdder();
            var replyHandler =
                    new Proc<>(
                            0,
                            (Integer s, String msg) -> {
                                replies.increment();
                                return s + 1;
                            });
            record ReplyMsg(String payload, Proc<Integer, String> replyTo) {}
            var proc =
                            replyHandler,
                            (Proc<Integer, String> rh, ReplyMsg msg) -> {
                                rh.tell("reply:" + msg.payload());
                                return rh;
            for (int i = 0; i < 500; i++) proc.tell(new ReplyMsg("warmup-" + i, replyHandler));
            for (int i = 0; i < count; i++) proc.tell(new ReplyMsg("msg-" + i, replyHandler));
            while (replies.sum() < count + 500) Thread.sleep(10);
                    "[return-address] %,d replies in %,d ns = %,.0f reply/s%n",
                    .as("Return Address should exceed 500K reply/s")
            replyHandler.stop();
        @DisplayName("7. Correlation ID: 100K correlated exchanges — target 200K/s")
        void correlationId_100KCorrelations_200KPerSecond() throws Exception {
            var correlations = new ConcurrentHashMap<UUID, Boolean>();
            var processed = new LongAdder();
            record CorrelatedMsg(UUID correlationId, String payload) {}
                            correlations,
                            (ConcurrentHashMap<UUID, Boolean> map, CorrelatedMsg msg) -> {
                                map.put(msg.correlationId(), true);
                                processed.increment();
                                return map;
            for (int i = 0; i < 1_000; i++)
                proc.tell(new CorrelatedMsg(UUID.randomUUID(), "warmup"));
            for (int i = 0; i < count; i++)
                proc.tell(new CorrelatedMsg(UUID.randomUUID(), "msg-" + i));
            while (processed.sum() < count + 1_000) Thread.sleep(10);
                    "[correlation-id] %,d correlations in %,d ns = %,.0f corr/s%n",
                    .as("Correlation ID should exceed 200K corr/s")
                    .isGreaterThan(200_000);
        @DisplayName("8. Message Sequence: 100K ordered messages — target 500K msg/s")
        void messageSequence_100KOrdered_500KPerSecond() throws Exception {
            for (int i = 0; i < 1_000; i++) proc.tell(new Msg.Seq(i, "warmup"));
            for (int i = 0; i < count; i++) proc.tell(new Msg.Seq(i, "payload-" + i));
                    "[message-sequence] %,d ordered messages in %,d ns = %,.0f msg/s%n",
                    .as("Message Sequence should exceed 500K msg/s")
        @DisplayName("9. Message Expiration: 1K timeout scenarios — target 500/s")
        void messageExpiration_1KTimeouts_500PerSecond() throws Exception {
            int count = 1_000;
                            State.initial(),
                            (State s, Msg msg) -> {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return s;
            var timedOut = new LongAdder();
            for (int i = 0; i < count; i++) {
                try {
                    proc.ask(new Msg.Get(), Duration.ofMillis(1)).get(50, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    timedOut.increment();
                }
                    "[message-expiration] %,d timeouts in %,d ns = %,.0f timeout/s%n",
            assertThat(timedOut.sum())
                    .as("Most requests should timeout")
                    .isGreaterThan((long) (count * 0.9));
                    .as("Message Expiration should exceed 500 timeout/s")
                    .isGreaterThan(500.0);
        @DisplayName(
                "10. Format Indicator: 1M sealed interface dispatches — target 10M+ dispatch/s")
        void formatIndicator_1MDispatches_10MPerSecond() throws Exception {
            var dispatches = new LongAdder();
                Msg msg =
                        switch (i % 5) {
                            case 0 -> new Msg.Inc(i);
                            case 1 -> new Msg.Get();
                            case 2 -> new Msg.Doc("doc", i);
                            case 3 -> new Msg.Evt("type", "source");
                            case 4 -> new Msg.Cmd("cmd");
                            default -> new Msg.Get();
                        };
                // Pattern match dispatch
                String result =
                        switch (msg) {
                            case Msg.Inc(int by) -> "inc:" + by;
                            case Msg.Get _ -> "get";
                            case Msg.Doc(String p, int v) -> "doc:" + v;
                            case Msg.Evt(String t, String s) -> "evt:" + t;
                            case Msg.Cmd(String a) -> "cmd:" + a;
                            default -> "other";
                dispatches.increment();
                    "[format-indicator] %,d sealed dispatches in %,d ns = %,.0f dispatch/s%n",
                    .as("Format Indicator should exceed 10M dispatch/s")
                    .isGreaterThan(10_000_000);
    // ROUTING PATTERNS STRESS TESTS (9 tests)
    @DisplayName("Routing Patterns Stress Tests")
    class RoutingPatternsStressTests {
        @DisplayName("1. Message Router: 100K routed messages — target 500K route/s")
        void messageRouter_100KRouted_500KPerSecond() throws Exception {
            var orderCount = new LongAdder();
            var paymentCount = new LongAdder();
            var orderHandler =
                            0L,
                            (Long s, String msg) -> {
                                orderCount.increment();
            var paymentHandler =
                                paymentCount.increment();
            record Dest(Proc<Long, String> orders, Proc<Long, String> payments) {}
            var router =
                            new Dest(orderHandler, paymentHandler),
                            (Dest d, String msg) -> {
                                if (msg.startsWith("O:")) d.orders().tell(msg);
                                else if (msg.startsWith("P:")) d.payments().tell(msg);
                                return d;
            for (int i = 0; i < 1_000; i++) router.tell(i % 2 == 0 ? "O:w" + i : "P:w" + i);
                router.tell(i % 2 == 0 ? "O:order" + i : "P:payment" + i);
            while (orderCount.sum() + paymentCount.sum() < count + 1_000) Thread.sleep(10);
                    "[message-router] %,d routed in %,d ns = %,.0f route/s%n",
                    .as("Message Router should exceed 500K route/s")
            orderHandler.stop();
            paymentHandler.stop();
            router.stop();
        @DisplayName("2. Content-Based Router: 100K content inspections — target 300K route/s")
        void contentBasedRouter_100KContent_300KPerSecond() throws Exception {
            var standardCount = new LongAdder();
            var expressCount = new LongAdder();
            var standardHandler =
                                standardCount.increment();
            var expressHandler =
                                expressCount.increment();
            record Handlers(Proc<Long, String> std, Proc<Long, String> exp) {}
                            new Handlers(standardHandler, expressHandler),
                            (Handlers h, String msg) -> {
                                if (msg.contains("express")
                                        || msg.contains("urgent")
                                        || msg.contains("priority")) h.exp().tell(msg);
                                else h.std().tell(msg);
                                return h;
                router.tell(i % 3 == 0 ? "express-" + i : "standard-" + i);
            while (standardCount.sum() + expressCount.sum() < count + 1_000) Thread.sleep(10);
                    "[content-based-router] %,d routed by content in %,d ns = %,.0f route/s%n",
                    .as("Content-Based Router should exceed 300K route/s")
                    .isGreaterThan(300_000);
            standardHandler.stop();
            expressHandler.stop();
        @DisplayName("3. Recipient List: 100K multicast × 10 recipients — target 1M+ deliveries/s")
        void recipientList_100KMulticast_10Recipients_1MDeliveries() throws Exception {
            int recipientCount = 10;
            int msgCount = 100_000;
            int expectedDeliveries = recipientCount * msgCount;
            var em = EventManager.<String>start();
            for (int i = 0; i < recipientCount; i++) em.addHandler(s -> received.increment());
            for (int i = 0; i < 100; i++) em.notify("warmup-" + i);
            for (int i = 0; i < msgCount; i++) em.notify("broadcast-" + i);
                    "[recipient-list] recipients=%d messages=%d deliveries=%,d in %d ms = %,.0f deliveries/s%n",
                    recipientCount,
                    msgCount,
                    .as("Recipient List should exceed 1M deliveries/s")
        @DisplayName("4. Splitter: 10K batches × 100 items — target 1M items/s")
        void splitter_10KBatches_100Items_1MPerSecond() throws Exception {
            int batchCount = 10_000;
            int itemsPerBatch = 100;
            int totalItems = batchCount * itemsPerBatch;
            var worker =
                            (Long s, String item) -> {
            var splitter =
                            worker,
                            (Proc<Long, String> w, List<String> batch) -> {
                                for (String item : batch) w.tell(item);
                                return w;
            var warmupBatch = IntStream.range(0, 50).mapToObj(i -> "w" + i).toList();
            for (int i = 0; i < 10; i++) splitter.tell(warmupBatch);
            for (int i = 0; i < batchCount; i++) {
                final int batchIdx = i;
                var batch =
                        IntStream.range(0, itemsPerBatch)
                                .mapToObj(j -> "item-" + batchIdx + "-" + j)
                                .toList();
                splitter.tell(batch);
            while (processed.sum() < totalItems * 0.95) Thread.sleep(10);
            double throughput = totalItems * 1_000_000_000.0 / elapsed;
                    "[splitter] batches=%d items/batch=%d total=%,d in %d ms = %,.0f items/s%n",
                    batchCount, itemsPerBatch, processed.sum(), elapsed / 1_000_000, throughput);
            assertThat(throughput).as("Splitter should exceed 1M items/s").isGreaterThan(1_000_000);
            worker.stop();
            splitter.stop();
        @DisplayName("5. Aggregator: 100K aggregations — target 200K agg/s")
        void aggregator_100KAggregations_200KPerSecond() throws Exception {
            var aggregated = new LongAdder();
            var aggregator =
                            new ArrayList<Integer>(),
                            (List<Integer> list, Integer msg) -> {
                                var newList = new ArrayList<>(list);
                                newList.add(msg);
                                if (newList.size() >= 1000) {
                                    aggregated.increment();
                                    return new ArrayList<>(); // Reset
                                return newList;
            for (int i = 0; i < 500; i++) aggregator.tell(i);
            for (int i = 0; i < count; i++) aggregator.tell(i);
            while (ProcSys.statistics(aggregator).messagesIn() < count + 500) Thread.sleep(10);
                    "[aggregator] %,d aggregations in %,d ns = %,.0f agg/s%n",
            assertThat(throughput).as("Aggregator should exceed 200K agg/s").isGreaterThan(200_000);
            aggregator.stop();
        @DisplayName("6. Resequencer: 100K out-of-order messages — target 100K reorder/s")
        void resequencer_100KReorder_100KPerSecond() throws Exception {
            var reordered = new LongAdder();
            record SeqMsg(int seq) {}
            record ReseqState(int next, Map<Integer, Boolean> buffer) {
                static ReseqState initial() {
                    return new ReseqState(0, new HashMap<>());
            var resequencer =
                            ReseqState.initial(),
                            (ReseqState s, SeqMsg msg) -> {
                                var newBuffer = new HashMap<>(s.buffer());
                                newBuffer.put(msg.seq(), true);
                                int next = s.next();
                                while (newBuffer.containsKey(next)) {
                                    reordered.increment();
                                    newBuffer.remove(next);
                                    next++;
                                return new ReseqState(next, newBuffer);
            // Warm up with in-order messages
            for (int i = 0; i < 100; i++) resequencer.tell(new SeqMsg(i));
            // Send out-of-order (interleaved pattern)
            for (int i = 0; i < count / 2; i++) {
                resequencer.tell(new SeqMsg(100 + i * 2 + 1)); // odd first
                resequencer.tell(new SeqMsg(100 + i * 2)); // even second
            while (reordered.sum() < count + 100) Thread.sleep(10);
                    "[resequencer] %,d reordered in %,d ns = %,.0f reorder/s%n",
                    .as("Resequencer should exceed 100K reorder/s")
                    .isGreaterThan(100_000);
            resequencer.stop();
        @DisplayName("7. Scatter-Gather: 10K parallel tasks — target 100K tasks/s")
        void scatterGather_10KTasks_100KPerSecond() throws Exception {
            int taskCount = 10_000;
            var completed = new LongAdder();
            var tasks =
                    IntStream.range(0, taskCount)
                            .mapToObj(
                                    i ->
                                            (java.util.function.Supplier<Integer>)
                                                    () -> {
                                                        completed.increment();
                                                        return i * i;
                                                    })
                            .collect(Collectors.toList());
            var result = Parallel.all(tasks);
            assertThat(result.isSuccess()).isTrue();
            double throughput = taskCount * 1_000_000_000.0 / elapsed;
                    "[scatter-gather] tasks=%d in %d ms = %,.0f tasks/s%n",
                    taskCount, elapsed / 1_000_000, throughput);
                    .as("Scatter-Gather should exceed 100K tasks/s")
        @DisplayName("8. Routing Slip: 50K routing slip traversals — target 100K slip/s")
        void routingSlip_50KSlips_100KPerSecond() throws Exception {
            var steps = List.of("validate", "enrich", "transform", "persist");
            var traversals = new LongAdder();
            record RoutingSlip(List<String> steps, int current) {}
            final Proc<Long, RoutingSlip>[] routerHolder = new Proc[1];
            routerHolder[0] =
                            (Long s, RoutingSlip slip) -> {
                                if (slip.current() < slip.steps().size()) {
                                    traversals.increment();
                                    if (slip.current() + 1 < slip.steps().size()) {
                                        routerHolder[0].tell(
                                                new RoutingSlip(slip.steps(), slip.current() + 1));
                                    }
            for (int i = 0; i < 100; i++) routerHolder[0].tell(new RoutingSlip(steps, 0));
            for (int i = 0; i < count; i++) routerHolder[0].tell(new RoutingSlip(steps, 0));
            // Wait for all traversals (count * steps.size())
            while (traversals.sum() < (long) count * steps.size() * 0.95) Thread.sleep(10);
                    "[routing-slip] %,d slips in %,d ns = %,.0f slip/s (%,d step traversals)%n",
                    count, elapsed, throughput, traversals.sum());
                    .as("Routing Slip should exceed 100K slip/s")
            routerHolder[0].stop();
        @DisplayName("9. Process Manager: 10K saga orchestrations — target 50K saga/s")
        void processManager_10KSagas_50KPerSecond() throws Exception {
            int count = 10_000;
            record SagaState(String orderId, boolean paid, boolean reserved, boolean shipped) {
                static SagaState start(String id) {
                    return new SagaState(id, false, false, false);
            var sagaManager =
                            SagaState.start("init"),
                            (SagaState s, String msg) -> {
                                if (msg.equals("inventory"))
                                    return new SagaState(s.orderId(), s.paid(), true, s.shipped());
                                if (msg.equals("payment"))
                                    return new SagaState(
                                            s.orderId(), true, s.reserved(), s.shipped());
                                if (msg.equals("ship") && s.paid() && s.reserved()) {
                                    completed.increment();
                                    return new SagaState(s.orderId(), true, true, true);
            for (int i = 0; i < 100; i++) {
                sagaManager.tell("inventory");
                sagaManager.tell("payment");
                sagaManager.tell("ship");
            while (completed.sum() < count + 100) Thread.sleep(10);
                    "[process-manager] %,d sagas in %,d ns = %,.0f saga/s%n",
                    .as("Process Manager should exceed 50K saga/s")
                    .isGreaterThan(50_000);
            sagaManager.stop();
    // ENDPOINT PATTERNS STRESS TESTS (14 tests)
    @DisplayName("Endpoint Patterns Stress Tests")
    class EndpointPatternsStressTests {
        @DisplayName("1. Channel Adapter: 100K external → mailbox — target 200K adapt/s")
        void channelAdapter_100KAdapt_200KPerSecond() throws Exception {
            var externalQueue = new LinkedTransferQueue<String>();
            var processor =
            var running = new AtomicBoolean(true);
            var adapter =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        while (running.get()) {
                                            try {
                                                var msg =
                                                        externalQueue.poll(
                                                                10, TimeUnit.MILLISECONDS);
                                                if (msg != null) processor.tell(msg);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                break;
                                            }
                                        }
                                    });
            for (int i = 0; i < 1_000; i++) externalQueue.put("warmup-" + i);
            for (int i = 0; i < count; i++) externalQueue.put("msg-" + i);
                    "[channel-adapter] %,d adapted in %,d ns = %,.0f adapt/s%n",
                    .as("Channel Adapter should exceed 200K adapt/s")
            running.set(false);
            adapter.join(1000);
            processor.stop();
        @DisplayName("2. Messaging Bridge: 100K bridge transfers — target 500K bridge/s")
        void messagingBridge_100KBridge_500KPerSecond() throws Exception {
            var channel2 =
                                received.increment();
            var bridge =
                            channel2,
                            (Proc<Long, String> ch2, String msg) -> {
                                ch2.tell("bridged:" + msg);
                                return ch2;
            for (int i = 0; i < 1_000; i++) bridge.tell("warmup-" + i);
            for (int i = 0; i < count; i++) bridge.tell("msg-" + i);
            while (received.sum() < count + 1_000) Thread.sleep(10);
                    "[messaging-bridge] %,d bridged in %,d ns = %,.0f bridge/s%n",
                    .as("Messaging Bridge should exceed 500K bridge/s")
            channel2.stop();
            bridge.stop();
        @DisplayName("3. Message Bus: 10K events × 100 handlers — target 1M deliveries/s")
        void messageBus_10KEvents_100Handlers_1MDeliveries() throws Exception {
            var bus = EventManager.<String>start();
            for (int i = 0; i < handlerCount; i++) bus.addHandler(s -> received.increment());
            for (int i = 0; i < 100; i++) bus.notify("warmup-" + i);
            for (int i = 0; i < eventCount; i++) bus.notify("event-" + i);
                    "[message-bus] handlers=%d events=%d deliveries=%,d in %d ms = %,.0f deliveries/s%n",
                    .as("Message Bus should exceed 1M deliveries/s")
            bus.stop();
        @DisplayName("4. Pipes and Filters: 100K × 5-stage pipeline — target 100K pipeline/s")
        void pipesAndFilters_100KPipeline_100KPerSecond() throws Exception {
            // 5-stage pipeline using a shared sink
            var sink =
                                completed.increment();
            var filter4 =
                            sink,
                            (Proc<Long, String> next, String msg) -> {
                                next.tell("f4:" + msg);
                                return next;
            var filter3 =
                                next.tell("f3:" + msg);
            var filter2 =
                                next.tell("f2:" + msg);
            var filter1 =
                                next.tell("f1:" + msg);
            for (int i = 0; i < 1_000; i++) filter1.tell("warmup-" + i);
            for (int i = 0; i < count; i++) filter1.tell("msg-" + i);
            while (completed.sum() < count + 1_000) Thread.sleep(10);
                    "[pipes-and-filters] %,d × 5-stage in %,d ns = %,.0f pipeline/s%n",
                    .as("Pipes and Filters should exceed 100K pipeline/s")
            sink.stop();
            filter1.stop();
            filter2.stop();
            filter3.stop();
            filter4.stop();
        @DisplayName("5. Message Dispatcher: 100K × 10 workers — target 500K dispatch/s")
        void messageDispatcher_100KDispatch_10Workers_500KPerSecond() throws Exception {
            int workerCount = 10;
            var dispatched = new LongAdder();
            var workers = new ArrayList<Proc<Long, String>>();
            for (int i = 0; i < workerCount; i++) {
                workers.add(
                        new Proc<>(
                                0L,
                                (Long s, String msg) -> {
                                    dispatched.increment();
                                    return s + 1;
                                }));
            var dispatcher =
                            (Integer idx, String msg) -> {
                                workers.get(idx % workerCount).tell(msg);
                                return idx + 1;
            for (int i = 0; i < 1_000; i++) dispatcher.tell("warmup-" + i);
            for (int i = 0; i < count; i++) dispatcher.tell("msg-" + i);
            while (dispatched.sum() < count + 1_000) Thread.sleep(10);
                    "[message-dispatcher] workers=%d messages=%,d in %,d ns = %,.0f dispatch/s%n",
                    workerCount, count, elapsed, throughput);
                    .as("Message Dispatcher should exceed 500K dispatch/s")
            for (var w : workers) w.stop();
            dispatcher.stop();
        @DisplayName("6. Event-Driven Consumer: 100K reactive handlers — target 300K handle/s")
        void eventDrivenConsumer_100KHandle_300KPerSecond() throws Exception {
            var handled = new LongAdder();
            var consumer =
                                handled.increment();
            for (int i = 0; i < 1_000; i++) consumer.tell("warmup-" + i);
            for (int i = 0; i < count; i++) consumer.tell("event-" + i);
            while (handled.sum() < count + 1_000) Thread.sleep(10);
                    "[event-driven-consumer] %,d handled in %,d ns = %,.0f handle/s%n",
                    .as("Event-Driven Consumer should exceed 300K handle/s")
            consumer.stop();
        @DisplayName("7. Competing Consumers: 100K × 10 consumers — target 200K consume/s")
        void competingConsumers_100KConsume_10Consumers_200KPerSecond() throws Exception {
            int consumerCount = 10;
            var consumed = new LongAdder();
            var queue = new LinkedTransferQueue<String>();
            for (int i = 0; i < consumerCount; i++) {
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (running.get()) {
                                        try {
                                            var msg = queue.poll(10, TimeUnit.MILLISECONDS);
                                            if (msg != null) consumed.increment();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                });
            for (int i = 0; i < 1_000; i++) queue.put("warmup-" + i);
            for (int i = 0; i < count; i++) queue.put("msg-" + i);
            while (consumed.sum() < count + 1_000) Thread.sleep(10);
                    "[competing-consumers] consumers=%d messages=%,d in %,d ns = %,.0f consume/s%n",
                    consumerCount, count, elapsed, throughput);
                    .as("Competing Consumers should exceed 200K consume/s")
        @DisplayName("8. Selective Consumer: 100K filtered messages — target 300K filter/s")
        void selectiveConsumer_100KFilter_300KPerSecond() throws Exception {
            var accepted = new LongAdder();
            var rejected = new LongAdder();
                                if (msg.contains("priority") || msg.contains("urgent"))
                                    accepted.increment();
                                else rejected.increment();
                consumer.tell(i % 3 == 0 ? "priority-w" + i : "normal-w" + i);
                consumer.tell(i % 3 == 0 ? "priority-" + i : "normal-" + i);
            while (accepted.sum() + rejected.sum() < count + 1_000) Thread.sleep(10);
                    "[selective-consumer] %,d filtered in %,d ns = %,.0f filter/s (accepted=%,d, rejected=%,d)%n",
                    count, elapsed, throughput, accepted.sum(), rejected.sum());
                    .as("Selective Consumer should exceed 300K filter/s")
        @DisplayName("9. Idempotent Receiver: 100K with 50% duplicates — target 200K dedup/s")
        void idempotentReceiver_100KDedup_200KPerSecond() throws Exception {
            var unique = new LongAdder();
            var duplicates = new LongAdder();
            var receiver =
                            new HashSet<String>(),
                            (Set<String> seen, String msg) -> {
                                var newSet = new HashSet<>(seen);
                                if (newSet.add(msg)) unique.increment();
                                else duplicates.increment();
                                return newSet;
            for (int i = 0; i < 500; i++) receiver.tell("warmup-" + i);
            receiver.tell("warmup-0"); // duplicate
                receiver.tell("msg-" + i);
                receiver.tell("msg-" + i); // duplicate
            while (unique.sum() + duplicates.sum() < count + 500) Thread.sleep(10);
                    "[idempotent-receiver] %,d total in %,d ns = %,.0f dedup/s (unique=%,d, dups=%,d)%n",
                    count, elapsed, throughput, unique.sum(), duplicates.sum());
                    .as("Idempotent Receiver should exceed 200K dedup/s")
            assertThat(duplicates.sum())
                    .as("Should have detected duplicates")
                    .isGreaterThan(count / 4);
            receiver.stop();
        @DisplayName("10. Service Activator: 100K activations — target 500K activate/s")
        void serviceActivator_100KActivate_500KPerSecond() throws Exception {
            var activations = new LongAdder();
            var activator =
                                if (msg.startsWith("activate:")) activations.increment();
            for (int i = 0; i < 1_000; i++) activator.tell("activate:warmup-" + i);
            for (int i = 0; i < count; i++) activator.tell("activate:service-" + i);
            while (activations.sum() < count + 1_000) Thread.sleep(10);
                    "[service-activator] %,d activations in %,d ns = %,.0f activate/s%n",
                    .as("Service Activator should exceed 500K activate/s")
            activator.stop();
        @DisplayName("11. Message Translator: 100K format conversions — target 500K translate/s")
        void messageTranslator_100KTranslate_500KPerSecond() throws Exception {
            var translated = new LongAdder();
            record Legacy(String id, double amt) {}
            record Modern(String orderId, double amount) {}
            var translator =
                            (Long s, Legacy legacy) -> {
                                var modern = new Modern(legacy.id(), legacy.amt());
                                translated.increment();
            for (int i = 0; i < 1_000; i++) translator.tell(new Legacy("w" + i, i * 1.0));
            for (int i = 0; i < count; i++) translator.tell(new Legacy("order-" + i, i * 10.0));
            while (translated.sum() < count + 1_000) Thread.sleep(10);
                    "[message-translator] %,d translations in %,d ns = %,.0f translate/s%n",
                    .as("Message Translator should exceed 500K translate/s")
            translator.stop();
        @DisplayName("12. Content Filter: 100K extractions — target 1M filter/s")
        void contentFilter_100KExtract_1MPerSecond() throws Exception {
            var extracted = new LongAdder();
            record Full(String id, String customer, String address, double total) {}
            record Filtered(String id, String customer, double total) {}
            java.util.function.Function<Full, Filtered> filter =
                    full -> new Filtered(full.id(), full.customer(), full.total());
                var full = new Full("O" + i, "Customer" + i, "Address" + i, i * 100.0);
                var filtered = filter.apply(full);
                if (filtered.id() != null) extracted.increment();
                    "[content-filter] %,d extractions in %,d ns = %,.0f filter/s%n",
                    .as("Content Filter should exceed 1M filter/s")
        @DisplayName("13. Claim Check: 100K store/retrieve — target 100K check/s")
        void claimCheck_100KCheck_100KPerSecond() throws Exception {
            var store = new ConcurrentHashMap<String, Object>();
            var checked = new LongAdder();
                            (Long s, String checkId) -> {
                                var payload = store.get(checkId);
                                if (payload != null) checked.increment();
            for (int i = 0; i < 1_000; i++) {
                store.put("check-w" + i, Map.of("data", i));
                receiver.tell("check-w" + i);
            // Store payloads
                store.put("check-" + i, Map.of("large", "data-" + i));
            for (int i = 0; i < count; i++) receiver.tell("check-" + i);
            while (checked.sum() < count + 1_000) Thread.sleep(10);
                    "[claim-check] %,d checks in %,d ns = %,.0f check/s%n",
                    .as("Claim Check should exceed 100K check/s")
        @DisplayName("14. Normalizer: 100K canonical conversions — target 200K normalize/s")
        void normalizer_100KNormalize_200KPerSecond() throws Exception {
            var normalized = new LongAdder();
            record Canonical(String id, String source, Map<String, Object> data) {}
            record JsonOrder(String orderId, Map<String, Object> properties) {}
            record XmlOrder(String id, String customer, String amount) {}
            java.util.function.Function<JsonOrder, Canonical> fromJson =
                    json -> new Canonical(json.orderId(), "JSON", json.properties());
            java.util.function.Function<XmlOrder, Canonical> fromXml =
                    xml ->
                            new Canonical(
                                    xml.id(),
                                    "XML",
                                    Map.of("customer", xml.customer(), "amount", xml.amount()));
                if (i % 2 == 0) {
                    var json = new JsonOrder("O" + i, Map.of("customer", "C" + i));
                    fromJson.apply(json);
                } else {
                    var xml = new XmlOrder("O" + i, "C" + i, String.valueOf(i * 100));
                    fromXml.apply(xml);
                normalized.increment();
                    "[normalizer] %,d normalizations in %,d ns = %,.0f normalize/s%n",
                    .as("Normalizer should exceed 200K normalize/s")
}
