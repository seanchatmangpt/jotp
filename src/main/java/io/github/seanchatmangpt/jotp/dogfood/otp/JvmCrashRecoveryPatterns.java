package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.CrashRecovery;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dogfood: demonstrates Joe Armstrong's approach to JVM crash recovery in JOTP.
 *
 * <p>Joe Armstrong: "There are only two hard problems in distributed systems: messages can get lost
 * and processes can die. Design protocols that survive it."
 *
 * <p>Pattern 1: Idempotent sender with ACK retry — payment charge with idempotency key. Sender
 * retries on timeout; receiver deduplicates via a seen-keys map.
 *
 * <p>Pattern 2: Stateless worker — nothing to lose on JVM crash. Pure function: same input always
 * produces same output. Restart is trivially safe.
 *
 * <p>Pattern 3: Checkpoint + replay for stateful processes — periodically persist progress so
 * recovery replays only un-checkpointed work.
 */
public class JvmCrashRecoveryPatterns {

    // ---------------------------------------------------------------------------
    // Pattern 1: Idempotent Charge (ACK retry)
    // ---------------------------------------------------------------------------

    /** Message hierarchy for the idempotent payment service. */
    public sealed interface ChargeMessage {
        /**
         * Charge request carrying an idempotency key. Sending the same key twice is safe — the
         * second attempt is deduplicated.
         */
        record ChargeRequest(String idempotencyKey, long amountCents) implements ChargeMessage {}

        /** Internal: query whether a key has already been processed. */
        record LookupReceipt(String idempotencyKey) implements ChargeMessage {}
    }

    /** Immutable state held by the idempotent payment process. */
    public record ChargeState(Map<String, ChargeReceipt> processed) {
        /** Create empty initial state. */
        public static ChargeState empty() {
            return new ChargeState(new ConcurrentHashMap<>());
        }
    }

    /** Successful charge receipt returned to the caller. */
    public record ChargeReceipt(String idempotencyKey, String transactionId) {}

    /**
     * Handler for the idempotent payment process.
     *
     * <p>On a {@link ChargeMessage.ChargeRequest}: if the key was already processed, return
     * existing state unchanged (deduplication). Otherwise, generate a transaction ID and record it.
     *
     * <p>On a {@link ChargeMessage.LookupReceipt}: no-op — state is inspected by the caller via
     * ask().
     */
    static ChargeState handleCharge(ChargeState state, ChargeMessage msg) {
        return switch (msg) {
            case ChargeMessage.ChargeRequest req -> {
                if (state.processed().containsKey(req.idempotencyKey())) {
                    // Deduplicate — already processed
                    yield state;
                }
                // Simulate issuing the charge and recording the receipt
                var receipt = new ChargeReceipt(req.idempotencyKey(), "txn-" + UUID.randomUUID());
                state.processed().put(req.idempotencyKey(), receipt);
                yield state;
            }
            case ChargeMessage.LookupReceipt ignored -> state;
        };
    }

    /**
     * Demonstrate Pattern 1: retry a charge up to {@code maxAttempts} times. Because the payment
     * process deduplicates on idempotency key, retries are safe even if the first attempt succeeded
     * but the ACK was lost.
     *
     * @param idempotencyKey unique key for this charge (reused on every retry)
     * @param amountCents amount to charge
     * @param maxAttempts maximum retry attempts
     * @return Result containing the ChargeReceipt on success, or an Exception on failure
     */
    public static Result<ChargeReceipt, Exception> chargeWithRetry(
            String idempotencyKey, long amountCents, int maxAttempts) {

        var chargeService = new Proc<>(ChargeState.empty(), JvmCrashRecoveryPatterns::handleCharge);
        var timeout = Duration.ofSeconds(5);
        var req = new ChargeMessage.ChargeRequest(idempotencyKey, amountCents);

        try {
            return CrashRecovery.retry(
                    maxAttempts,
                    () -> {
                        // ask() is synchronous request-reply; on JVM crash/restart the same key is
                        // replayed — the receiver deduplicates, so the charge is applied exactly
                        // once.
                        ChargeState state = chargeService.ask(req, timeout).join();
                        ChargeReceipt receipt = state.processed().get(idempotencyKey);
                        if (receipt == null) {
                            throw new IllegalStateException(
                                    "charge not recorded for key: " + idempotencyKey);
                        }
                        return receipt;
                    });
        } finally {
            try {
                chargeService.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Pattern 2: Stateless worker
    // ---------------------------------------------------------------------------

    /** Message for the stateless compute worker. */
    public sealed interface ComputeMessage {
        /** Double the supplied value — pure, no side effects. */
        record DoubleIt(int value) implements ComputeMessage {}
    }

    /** Stateless worker state — there is nothing to lose. */
    public record WorkerState(int lastResult) {
        public static WorkerState empty() {
            return new WorkerState(0);
        }
    }

    /**
     * Handler for the stateless worker: pure function, same input → same output. Restarting after a
     * JVM crash requires zero recovery logic.
     */
    static WorkerState handleCompute(WorkerState state, ComputeMessage msg) {
        return switch (msg) {
            case ComputeMessage.DoubleIt req -> new WorkerState(req.value() * 2);
        };
    }

    /**
     * Demonstrate Pattern 2: stateless computation using a Proc whose state carries only the last
     * result. Because there is no accumulated mutable state, a crash and restart leaves nothing
     * unrecoverable.
     *
     * @param value input to double
     * @return Result containing the doubled value
     */
    public static Result<Integer, Exception> statelessDouble(int value) {
        var worker = new Proc<>(WorkerState.empty(), JvmCrashRecoveryPatterns::handleCompute);
        var timeout = Duration.ofSeconds(5);

        try {
            return CrashRecovery.retry(
                    3,
                    () -> {
                        WorkerState result =
                                worker.ask(new ComputeMessage.DoubleIt(value), timeout).join();
                        return result.lastResult();
                    });
        } finally {
            try {
                worker.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Pattern 3: Checkpoint + replay
    // ---------------------------------------------------------------------------

    /** Messages for the checkpointing processor. */
    public sealed interface ProcessorMessage {
        /** Process a single work item identified by its key. */
        record ProcessItem(String key, int payload) implements ProcessorMessage {}

        /** Request a checkpoint: persist state to durable storage. */
        record Checkpoint() implements ProcessorMessage {}
    }

    /**
     * Checkpointed process state.
     *
     * <p>On JVM crash, the process can be reconstructed from the last {@code checkpointCount} and
     * {@code lastCheckpointKey}, replaying only items that arrived after the checkpoint.
     *
     * @param processedCount total items processed since start
     * @param lastProcessedKey key of the most recent item
     * @param checkpointCount items processed as of the last checkpoint
     * @param lastCheckpointKey key at the last checkpoint (null if none yet)
     */
    public record CheckpointState(
            long processedCount,
            String lastProcessedKey,
            long checkpointCount,
            String lastCheckpointKey) {

        /** Initial state with no history. */
        public static CheckpointState empty() {
            return new CheckpointState(0, null, 0, null);
        }

        /** Advance counters by one item. */
        CheckpointState withItem(String key) {
            return new CheckpointState(processedCount + 1, key, checkpointCount, lastCheckpointKey);
        }

        /**
         * Persist current position. In production this would write to a database or distributed
         * log; here we simply capture the current counters.
         */
        CheckpointState checkpoint() {
            return new CheckpointState(
                    processedCount, lastProcessedKey, processedCount, lastProcessedKey);
        }
    }

    /** Handler for the checkpointing processor. */
    static CheckpointState handleProcessor(CheckpointState state, ProcessorMessage msg) {
        return switch (msg) {
            case ProcessorMessage.ProcessItem item -> state.withItem(item.key());
            case ProcessorMessage.Checkpoint ignored -> state.checkpoint();
        };
    }

    /**
     * Demonstrate Pattern 3: process a batch of items with periodic checkpointing.
     *
     * <p>Every {@code checkpointEvery} items, a {@link ProcessorMessage.Checkpoint} message is sent
     * so the process durably records its progress. On JVM crash + restart, only items after the
     * last checkpoint need to be replayed.
     *
     * @param items array of work keys to process
     * @param checkpointEvery checkpoint interval (items between checkpoints)
     * @return final CheckpointState after processing all items
     */
    public static CheckpointState processWithCheckpoints(String[] items, int checkpointEvery) {
        var processor =
                new Proc<>(CheckpointState.empty(), JvmCrashRecoveryPatterns::handleProcessor);
        var timeout = Duration.ofSeconds(5);
        AtomicInteger counter = new AtomicInteger(0);

        try {
            CheckpointState state = CheckpointState.empty();
            for (String key : items) {
                state = processor.ask(new ProcessorMessage.ProcessItem(key, 1), timeout).join();
                if (counter.incrementAndGet() % checkpointEvery == 0) {
                    state = processor.ask(new ProcessorMessage.Checkpoint(), timeout).join();
                }
            }
            // Final checkpoint to commit remaining items
            state = processor.ask(new ProcessorMessage.Checkpoint(), timeout).join();
            return state;
        } finally {
            try {
                processor.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
