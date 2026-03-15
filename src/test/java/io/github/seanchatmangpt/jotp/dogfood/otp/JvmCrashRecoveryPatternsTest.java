package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.CrashRecovery;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.dogfood.otp.JvmCrashRecoveryPatterns.ChargeMessage;
import io.github.seanchatmangpt.jotp.dogfood.otp.JvmCrashRecoveryPatterns.ChargeState;
import io.github.seanchatmangpt.jotp.dogfood.otp.JvmCrashRecoveryPatterns.CheckpointState;
import io.github.seanchatmangpt.jotp.dogfood.otp.JvmCrashRecoveryPatterns.ComputeMessage;
import io.github.seanchatmangpt.jotp.dogfood.otp.JvmCrashRecoveryPatterns.WorkerState;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/**
 * Dogfood: tests for JvmCrashRecoveryPatterns verifying all three patterns.
 *
 * <ul>
 *   <li>Pattern 1: idempotent charge with ACK retry
 *   <li>Pattern 2: stateless worker (no state to lose)
 *   <li>Pattern 3: checkpoint + replay for stateful processes
 * </ul>
 */
@DisplayName("JvmCrashRecoveryPatterns")
class JvmCrashRecoveryPatternsTest implements WithAssertions {
    // ---------------------------------------------------------------------------
    // Pattern 1: Idempotent Charge
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Nested
    @DisplayName("Pattern 1: Idempotent sender with ACK retry")
    class IdempotentCharge {
        @Test
        @DisplayName("charge succeeds and returns a receipt")
        void chargeSucceedsWithReceipt() {
            var key = UUID.randomUUID().toString();
            var result = JvmCrashRecoveryPatterns.chargeWithRetry(key, 1000L, 3);
            assertThat(result.isSuccess()).isTrue();
            var receipt = result.orElseThrow();
            assertThat(receipt.idempotencyKey()).isEqualTo(key);
            assertThat(receipt.transactionId()).startsWith("txn-");
        }
        @DisplayName(
                "sending same idempotency key twice returns the same transaction ID (deduplication)")
        void sameKeyDeduplicates() throws InterruptedException {
            var service = new Proc<>(ChargeState.empty(), JvmCrashRecoveryPatterns::handleCharge);
            var timeout = Duration.ofSeconds(5);
            var req = new ChargeMessage.ChargeRequest(key, 500L);
            // First request — creates the receipt
            ChargeState state1 = service.ask(req, timeout).join();
            var receipt1 = state1.processed().get(key);
            // Second request with identical key — must be deduplicated
            ChargeState state2 = service.ask(req, timeout).join();
            var receipt2 = state2.processed().get(key);
            assertThat(receipt1).isNotNull();
            assertThat(receipt2).isNotNull();
            assertThat(receipt1.transactionId()).isEqualTo(receipt2.transactionId());
            service.stop();
        @DisplayName("distinct idempotency keys produce distinct transaction IDs")
        void distinctKeysProduceDistinctReceipts() throws InterruptedException {
            var key1 = UUID.randomUUID().toString();
            var key2 = UUID.randomUUID().toString();
            service.ask(new ChargeMessage.ChargeRequest(key1, 100L), timeout).join();
            service.ask(new ChargeMessage.ChargeRequest(key2, 200L), timeout).join();
            var state = service.ask(new ChargeMessage.LookupReceipt(key1), timeout).join();
            var txn1 = state.processed().get(key1).transactionId();
            var txn2 = state.processed().get(key2).transactionId();
            assertThat(txn1).isNotEqualTo(txn2);
        @DisplayName("retry wrapper succeeds on first attempt without any actual failure")
        void retryWrapperSucceedsFirstAttempt() {
            // maxAttempts=1 means exactly one attempt; must succeed immediately
            var result = JvmCrashRecoveryPatterns.chargeWithRetry(key, 9999L, 1);
            assertThat(result.orElseThrow().idempotencyKey()).isEqualTo(key);
        @DisplayName("retry wrapper retries on transient failure and eventually succeeds")
        void retryWrapperRetriesOnTransientFailure() {
            // Simulate a supplier that fails twice then succeeds
            var callCount = new AtomicInteger(0);
            var result =
                    CrashRecovery.retry(
                            5,
                            () -> {
                                int n = callCount.incrementAndGet();
                                if (n < 3) {
                                    throw new RuntimeException("transient error on attempt " + n);
                                }
                                return "ok-on-attempt-" + n;
                            });
            assertThat(result.orElseThrow()).isEqualTo("ok-on-attempt-3");
            assertThat(callCount.get()).isEqualTo(3);
    // Pattern 2: Stateless worker
    @DisplayName("Pattern 2: Stateless worker (nothing to lose on crash)")
    class StatelessWorker {
        @DisplayName("doubling a positive value returns correct result")
        void doublingPositiveValue() {
            var result = JvmCrashRecoveryPatterns.statelessDouble(21);
            assertThat(result.orElseThrow()).isEqualTo(42);
        @DisplayName("doubling zero returns zero")
        void doublingZero() {
            var result = JvmCrashRecoveryPatterns.statelessDouble(0);
            assertThat(result.orElseThrow()).isEqualTo(0);
        @DisplayName("doubling a negative value returns correct negative result")
        void doublingNegativeValue() {
            var result = JvmCrashRecoveryPatterns.statelessDouble(-7);
            assertThat(result.orElseThrow()).isEqualTo(-14);
        @DisplayName("worker process handler is a pure function — same input always same output")
        void handlerIsPure() throws InterruptedException {
            var worker = new Proc<>(WorkerState.empty(), JvmCrashRecoveryPatterns::handleCompute);
            var r1 = worker.ask(new ComputeMessage.DoubleIt(5), timeout).join();
            var r2 = worker.ask(new ComputeMessage.DoubleIt(5), timeout).join();
            var r3 = worker.ask(new ComputeMessage.DoubleIt(5), timeout).join();
            assertThat(r1.lastResult()).isEqualTo(10);
            assertThat(r2.lastResult()).isEqualTo(10);
            assertThat(r3.lastResult()).isEqualTo(10);
            worker.stop();
        @DisplayName("stateless worker holds no accumulated mutable state between requests")
        void noAccumulatedState() throws InterruptedException {
            // Each request is independent — the last result reflects only the latest request
            worker.ask(new ComputeMessage.DoubleIt(100), timeout).join();
            var state = worker.ask(new ComputeMessage.DoubleIt(3), timeout).join();
            // State reflects the most recent input, not accumulated totals
            assertThat(state.lastResult()).isEqualTo(6);
    // Pattern 3: Checkpoint + replay
    @DisplayName("Pattern 3: Checkpoint + replay for stateful processes")
    class CheckpointReplay {
        @DisplayName("processing three items records correct count")
        void processingItemsRecordsCount() {
            var items = new String[] {"a", "b", "c"};
            var state = JvmCrashRecoveryPatterns.processWithCheckpoints(items, 10);
            assertThat(state.processedCount()).isEqualTo(3);
            assertThat(state.lastProcessedKey()).isEqualTo("c");
        @DisplayName("checkpoint is captured at the specified interval")
        void checkpointCapturedAtInterval() {
            var items = new String[] {"k1", "k2", "k3", "k4", "k5"};
            // checkpoint every 2 items → checkpoints at item 2 and item 4, then final
            var state = JvmCrashRecoveryPatterns.processWithCheckpoints(items, 2);
            // After final checkpoint all processed items are committed
            assertThat(state.processedCount()).isEqualTo(5);
            assertThat(state.checkpointCount()).isEqualTo(5);
        @DisplayName("final checkpoint commits all remaining items")
        void finalCheckpointCommitsRemainder() {
            var items = new String[] {"x1", "x2", "x3"};
            // Interval of 10 means no mid-batch checkpoint; only the explicit final one
            assertThat(state.checkpointCount()).isEqualTo(3);
            assertThat(state.lastCheckpointKey()).isEqualTo("x3");
        @DisplayName("empty item list produces empty committed state")
        void emptyItemsProducesEmptyState() {
            var state = JvmCrashRecoveryPatterns.processWithCheckpoints(new String[] {}, 5);
            assertThat(state.processedCount()).isEqualTo(0);
            assertThat(state.checkpointCount()).isEqualTo(0);
            assertThat(state.lastProcessedKey()).isNull();
        @DisplayName("checkpoint state handler correctly advances counters")
        void checkpointHandlerAdvancesCounters() throws InterruptedException {
            var proc =
                    new Proc<>(CheckpointState.empty(), JvmCrashRecoveryPatterns::handleProcessor);
            // Process two items then checkpoint
            proc.ask(new JvmCrashRecoveryPatterns.ProcessorMessage.ProcessItem("i1", 1), timeout)
                    .join();
            proc.ask(new JvmCrashRecoveryPatterns.ProcessorMessage.ProcessItem("i2", 1), timeout)
            var afterCheckpoint =
                    proc.ask(new JvmCrashRecoveryPatterns.ProcessorMessage.Checkpoint(), timeout)
                            .join();
            assertThat(afterCheckpoint.processedCount()).isEqualTo(2);
            assertThat(afterCheckpoint.checkpointCount()).isEqualTo(2);
            assertThat(afterCheckpoint.lastCheckpointKey()).isEqualTo("i2");
            proc.stop();
                "un-checkpointed items are captured between processedCount and checkpointCount")
        void unCheckpointedItemsAreDetectable() throws InterruptedException {
            // Checkpoint after first item
            proc.ask(new JvmCrashRecoveryPatterns.ProcessorMessage.ProcessItem("first", 1), timeout)
            proc.ask(new JvmCrashRecoveryPatterns.ProcessorMessage.Checkpoint(), timeout).join();
            // Process two more without checkpointing
            proc.ask(
                            new JvmCrashRecoveryPatterns.ProcessorMessage.ProcessItem("second", 1),
                            timeout)
            var state =
                    proc.ask(
                                    new JvmCrashRecoveryPatterns.ProcessorMessage.ProcessItem(
                                            "third", 1),
                                    timeout)
            // processedCount > checkpointCount: two items are un-checkpointed
            assertThat(state.checkpointCount()).isEqualTo(1);
            long unCheckpointed = state.processedCount() - state.checkpointCount();
            assertThat(unCheckpointed).isEqualTo(2);
}
