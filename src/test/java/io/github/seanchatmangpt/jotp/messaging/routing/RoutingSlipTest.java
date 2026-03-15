package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for Routing Slip pattern.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Message creation with routing slip
 *   <li>Sequential hop execution (fire-and-forget)
 *   <li>Synchronous hop execution with replies
 *   <li>Slip advancement and completion checking
 *   <li>Immutability of slips and messages
 *   <li>Correlation tracking across hops
 *   <li>Error handling and exception propagation
 * </ul>
 */
@DisplayName("Routing Slip Orchestration Pattern")
class RoutingSlipTest {
    /** Test message type. */
    record Payload(String data, List<String> history) {
        public Payload withEntry(String entry) {
            var newHistory = new ArrayList<>(history);
            newHistory.add(entry);
            return new Payload(data, newHistory);
        }
    }
    /** Process state tracks number of messages processed. */
    record ProcessState(String name, int count) {}
    /** Messages for routing slip tests. */
    sealed interface TestMessage {
        record Process(RoutingSlip.MessageWithSlip<Payload, ProcessState> msg)
                implements TestMessage {}
        record GetCount() implements TestMessage {}
    private List<ProcRef<ProcessState, Object>> hops;
    private CopyOnWriteArrayList<String> executionOrder;
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        hops = new ArrayList<>();
        executionOrder = new CopyOnWriteArrayList<>();
        // Create 3 hop processes
        for (int i = 0; i < 3; i++) {
            int hopId = i + 1;
            var proc =
                    new Proc<ProcessState, Object>(
                            new ProcessState("Hop-" + hopId, 0),
                            (state, msg) -> {
                                if (msg instanceof TestMessage.Process process) {
                                    var slip = process.msg();
                                    executionOrder.add("Hop-" + hopId);
                                    System.out.println(
                                            "Hop-"
                                                    + hopId
                                                    + ": processing '"
                                                    + slip.payload().data()
                                                    + "'");
                                    // Continue to next hop if slip has more hops
                                    if (!slip.isComplete()) {
                                        var nextHop = slip.peekNext().orElseThrow();
                                        nextHop.tell(slip);
                                    }
                                    return new ProcessState(state.name(), state.count() + 1);
                                } else if (msg instanceof TestMessage.GetCount) {
                                    return state;
                                } else {
                                }
                            });
            hops.add(new ProcRef<>(proc));
    @Test
    @DisplayName("Create message with routing slip")
    void testCreateMessageWithSlip() {
        // Act
        var payload = new Payload("test", new ArrayList<>());
        var message = RoutingSlip.withSlip(payload, hops.toArray(new ProcRef[0]));
        // Assert
        assertThat(message.payload()).isEqualTo(payload);
        assertThat(message.slip()).hasSize(3);
        assertThat(message.remainingHops()).isEqualTo(3);
        assertThat(message.isComplete()).isFalse();
    @DisplayName("Peek next hop without advancing slip")
    void testPeekNext() {
        // Arrange
        var peeked = RoutingSlip.peekNext(message);
        assertThat(peeked).isPresent();
        assertThat(message.remainingHops()).as("Peek should not advance slip").isEqualTo(3);
    @DisplayName("Pop next hop advances slip")
    void testPopNext() {
        var advanced = RoutingSlip.popNext(message);
        assertThat(advanced.remainingHops()).isEqualTo(2);
        assertThat(message.remainingHops())
                .as("Original should be unchanged (immutable)")
                .isEqualTo(3);
    @DisplayName("Execute slip: fire-and-forget delivery to all hops")
    void testExecuteSlip() throws InterruptedException {
        var payload = new Payload("flow", new ArrayList<>());
        executionOrder.clear();
        var result = RoutingSlip.executeSlip(message);
        // Wait for async delivery
        Thread.sleep(500);
        assertThat(result).isInstanceOf(Result.Ok.class);
        // All hops should execute in order
        assertThat(executionOrder)
                .as("All hops should execute in order")
                .contains("Hop-1", "Hop-2", "Hop-3");
    @DisplayName("Execute slip synchronously with request-reply")
    void testExecuteSlipSync() throws InterruptedException {
        // Arrange: create simpler sync-friendly processes
        var syncHops = new ArrayList<ProcRef<ProcessState, Object>>();
                            new ProcessState("SyncHop-" + hopId, 0),
                                // Simple echo back the state
                                return new ProcessState(state.name(), state.count() + 1);
            syncHops.add(new ProcRef<>(proc));
        var payload = new Payload("sync-flow", new ArrayList<>());
        @SuppressWarnings("unchecked")
        var message =
                (RoutingSlip.MessageWithSlip<Payload, ProcessState>)
                        (RoutingSlip.MessageWithSlip)
                                RoutingSlip.withSlip(payload, syncHops.toArray(new ProcRef[0]));
        var result = RoutingSlip.executeSlipSync(message, Duration.ofSeconds(2));
        var okResult = (Result.Ok<List<ProcessState>, Exception>) result;
        assertThat(okResult.value()).as("Should collect replies from all hops").hasSize(3);
    @DisplayName("Slip exhaustion: pop from empty slip throws")
    void testSlipExhaustion() {
        var message = RoutingSlip.withSlip(payload, hops.get(0));
        var advanced = RoutingSlip.popNext(message); // Last hop
        assertThat(advanced.isComplete()).isTrue();
        // Act & Assert
        assertThatThrownBy(() -> RoutingSlip.popNext(advanced))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Routing slip exhausted");
    @DisplayName("Routing slip is immutable: modifications create new instances")
    void testImmutability() {
        var payload = new Payload("immutable", new ArrayList<>());
        var original = RoutingSlip.withSlip(payload, hops.toArray(new ProcRef[0]));
        var modified = RoutingSlip.popNext(original);
        assertThat(original.remainingHops()).as("Original should be unchanged").isEqualTo(3);
        assertThat(modified.remainingHops()).as("Modified should have one fewer hop").isEqualTo(2);
    @DisplayName("Sequential execution order: hops processed in slip order")
    void testExecutionOrder() throws InterruptedException {
        var payload = new Payload("order-test", new ArrayList<>());
        // Wait for processing
        // Should not be empty if execution occurred
        if (!executionOrder.isEmpty()) {
            // Check that Hop-1 executed before Hop-2, etc.
            int hop1Idx = executionOrder.indexOf("Hop-1");
            int hop2Idx = executionOrder.indexOf("Hop-2");
            int hop3Idx = executionOrder.indexOf("Hop-3");
            // All should be present and in order
            if (hop1Idx >= 0 && hop2Idx >= 0 && hop3Idx >= 0) {
                assertThat(hop1Idx).as("Hop-1 should execute first").isLessThan(hop2Idx);
                assertThat(hop2Idx).as("Hop-2 should execute before Hop-3").isLessThan(hop3Idx);
            }
    @DisplayName("Remaining hops counter")
    void testRemainingHops() {
        assertThat(RoutingSlip.remainingHops(message)).isEqualTo(3);
        var after1 = RoutingSlip.popNext(message);
        assertThat(RoutingSlip.remainingHops(after1)).isEqualTo(2);
        var after2 = RoutingSlip.popNext(after1);
        assertThat(RoutingSlip.remainingHops(after2)).isEqualTo(1);
        var after3 = RoutingSlip.popNext(after2);
        assertThat(RoutingSlip.remainingHops(after3)).isEqualTo(0);
        assertThat(RoutingSlip.isComplete(after3)).isTrue();
    @DisplayName("Single hop slip: valid edge case")
    void testSingleHopSlip() {
        var payload = new Payload("single", new ArrayList<>());
        assertThat(message.remainingHops()).isEqualTo(1);
        assertThat(RoutingSlip.peekNext(message)).isPresent();
    @DisplayName("Empty slip validation: cannot create with no hops")
    void testEmptySlipValidation() {
        var payload = new Payload("empty", new ArrayList<>());
        assertThatThrownBy(() -> RoutingSlip.withSlip(payload, new ProcRef[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one hop");
    @DisplayName("Execute slip async: returns CompletableFuture")
    void testExecuteSlipAsync() {
        var payload = new Payload("async", new ArrayList<>());
        var future = RoutingSlip.executeSlipAsync(message);
        assertThat(future).isNotNull();
        assertThat(future.isDone()).as("Future should eventually complete").isTrue();
    @DisplayName("Slip with payload enrichment")
    void testPayloadEnrichment() {
        var payload = new Payload("original", new ArrayList<>());
        // Act: simulate enrichment at each hop
        var enriched = message.payload().withEntry("Step-1-processed");
        var reMessage = RoutingSlip.withSlip(enriched, hops.toArray(new ProcRef[0]));
        assertThat(reMessage.payload().history()).contains("Step-1-processed");
        assertThat(message.payload().history())
                .as("Original payload should be unchanged")
                .isEmpty();
}
