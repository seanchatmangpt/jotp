package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MessageRecorder} and {@link MessageReplayer}.
 *
 * <p>Covers recording, forwarding, sequence numbers, snapshot immutability, clear semantics, replay
 * correctness, error capture, and empty-log edge cases.
 */
@DisplayName("MessageRecorder + MessageReplayer: deterministic message sequence recording/replay")
class MessageRecorderTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // -------------------------------------------------------------------------
    // MessageRecorder tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tell() is forwarded to the underlying process")
    void tellForwardsToUnderlyingProc() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        recorder.tell("a");
        recorder.tell("b");
        recorder.tell("c");

        // Use ask on the underlying proc to flush the mailbox and read final state
        await().atMost(Duration.ofSeconds(5)).until(() -> proc.ask("d").get().equals(4));

        proc.stop();
    }

    @Test
    @DisplayName("tell() records entries in the log")
    void tellRecordsEntries() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        recorder.tell("hello");
        recorder.tell("world");

        // Wait for messages to be forwarded
        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.log().size() == 2);

        List<MessageRecorder.Entry<String>> log = recorder.log();
        assertThat(log).hasSize(2);
        assertThat(log.get(0).message()).isEqualTo("hello");
        assertThat(log.get(1).message()).isEqualTo("world");

        proc.stop();
    }

    @Test
    @DisplayName("sequence numbers are monotonically increasing and start at 1")
    void sequenceNumbersAreMonotonicallyIncreasing() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        recorder.tell("a");
        recorder.tell("b");
        recorder.tell("c");

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.log().size() == 3);

        List<MessageRecorder.Entry<String>> log = recorder.log();
        assertThat(log.get(0).seqNum()).isEqualTo(1L);
        assertThat(log.get(1).seqNum()).isEqualTo(2L);
        assertThat(log.get(2).seqNum()).isEqualTo(3L);

        proc.stop();
    }

    @Test
    @DisplayName("timestamps are non-negative and non-decreasing")
    void timestampsAreNonNegativeAndNonDecreasing() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        for (int i = 0; i < 5; i++) {
            recorder.tell("msg" + i);
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.log().size() == 5);

        List<MessageRecorder.Entry<String>> log = recorder.log();
        for (int i = 0; i < log.size(); i++) {
            assertThat(log.get(i).nanoTime()).isGreaterThanOrEqualTo(0L);
            if (i > 0) {
                assertThat(log.get(i).nanoTime()).isGreaterThanOrEqualTo(log.get(i - 1).nanoTime());
            }
        }

        proc.stop();
    }

    @Test
    @DisplayName("ask() records the message and returns the new process state")
    void askRecordsAndReturnsState() throws Exception {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        Integer state = recorder.<Integer>ask("inc").get();

        assertThat(state).isEqualTo(1);
        assertThat(recorder.log()).hasSize(1);
        assertThat(recorder.log().get(0).message()).isEqualTo("inc");

        proc.stop();
    }

    @Test
    @DisplayName("snapshot() returns an immutable copy of the log at that instant")
    void snapshotIsImmutableCopy() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        recorder.tell("x");

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.log().size() == 1);

        List<MessageRecorder.Entry<String>> snap = recorder.snapshot();

        // Add another message after snapshot
        recorder.tell("y");

        // Snapshot must not see the new message
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).message()).isEqualTo("x");

        // Snapshot itself must be unmodifiable
        assertThatThrownBy(() -> snap.add(null)).isInstanceOf(UnsupportedOperationException.class);

        proc.stop();
    }

    @Test
    @DisplayName("clear() resets the log and restarts sequence numbers")
    void clearResetsLogAndSequenceNumbers() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        recorder.tell("first");

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.log().size() == 1);

        recorder.clear();
        assertThat(recorder.log()).isEmpty();

        recorder.tell("after-clear");

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.log().size() == 1);

        // Sequence should restart from 1
        assertThat(recorder.log().get(0).seqNum()).isEqualTo(1L);
        assertThat(recorder.log().get(0).message()).isEqualTo("after-clear");

        proc.stop();
    }

    @Test
    @DisplayName("clear() does not affect the underlying process state")
    void clearDoesNotAffectUnderlyingProc() throws Exception {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(proc);

        // Drive state to 3
        recorder.<Integer>ask("a").get();
        recorder.<Integer>ask("b").get();
        recorder.<Integer>ask("c").get();

        recorder.clear();

        // The underlying proc state is unchanged — ask one more to confirm
        Integer state = recorder.<Integer>ask("d").get();
        assertThat(state).isEqualTo(4);

        proc.stop();
    }

    // -------------------------------------------------------------------------
    // MessageReplayer tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("replay() produces the same final state as the original run")
    void replaySameFinalStateAsOriginal() throws Exception {
        // Original run
        Proc<Integer, String> original = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(original);

        recorder.<Integer>ask("a").get();
        recorder.<Integer>ask("b").get();
        recorder.<Integer>ask("c").get();

        List<MessageRecorder.Entry<String>> log = recorder.snapshot();

        original.stop();

        // Fresh process for replay
        Proc<Integer, String> fresh = Proc.spawn(0, (s, m) -> s + 1);
        MessageReplayer<Integer, String> replayer = MessageReplayer.create(fresh);

        MessageReplayer.Result<Integer> result = replayer.replay(log);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.finalState()).isEqualTo(3);
        assertThat(result.messagesReplayed()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();

        fresh.stop();
    }

    @Test
    @DisplayName("replay() with empty log returns zero messages and succeeds")
    void replayEmptyLogSucceeds() throws InterruptedException {
        Proc<Integer, String> proc = Proc.spawn(0, (s, m) -> s + 1);
        MessageReplayer<Integer, String> replayer = MessageReplayer.create(proc);

        MessageReplayer.Result<Integer> result = replayer.replay(List.of());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.messagesReplayed()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
        assertThat(result.finalState()).isNull();

        proc.stop();
    }

    @Test
    @DisplayName("replay() captures errors but continues processing remaining messages")
    void replayCapturesErrorsAndContinues() throws Exception {
        // Handler that throws on "BOOM" but increments otherwise
        Proc<Integer, String> proc =
                Proc.spawn(
                        0,
                        (s, m) -> {
                            if ("BOOM".equals(m)) throw new RuntimeException("simulated crash");
                            return s + 1;
                        });

        // Build a log manually using a recorder on a clean proc
        Proc<Integer, String> source = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(source);
        recorder.<Integer>ask("a").get();
        recorder.<Integer>ask("BOOM").get(); // source handles it fine (no throw)
        recorder.<Integer>ask("b").get();
        List<MessageRecorder.Entry<String>> log = recorder.snapshot();
        source.stop();

        // Replay against the throwing proc
        MessageReplayer<Integer, String> replayer = MessageReplayer.create(proc);
        MessageReplayer.Result<Integer> result = replayer.replay(log);

        // BOOM should have produced an error; a and b should have succeeded
        assertThat(result.messagesReplayed()).isEqualTo(3);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).isEqualTo("BOOM");
        assertThat(result.errors().get(0).cause()).isInstanceOf(RuntimeException.class);
        assertThat(result.succeeded()).isFalse();
        // Final state reflects successful messages (a + b = 2)
        assertThat(result.finalState()).isEqualTo(2);

        proc.stop();
    }

    @Test
    @DisplayName("replay() with timeScale=0.0 completes without real-time delays")
    void replayWithZeroTimeScaleIsInstant() throws Exception {
        Proc<Integer, String> original = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(original);

        for (int i = 0; i < 10; i++) {
            recorder.<Integer>ask("msg" + i).get();
        }
        List<MessageRecorder.Entry<String>> log = recorder.snapshot();
        original.stop();

        Proc<Integer, String> fresh = Proc.spawn(0, (s, m) -> s + 1);
        MessageReplayer<Integer, String> replayer = MessageReplayer.create(fresh);

        long start = System.currentTimeMillis();
        MessageReplayer.Result<Integer> result = replayer.replay(log, 0.0);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.succeeded()).isTrue();
        assertThat(result.finalState()).isEqualTo(10);
        // Replay at 0x speed should complete well within 5 seconds for 10 messages
        assertThat(elapsed).isLessThan(5000L);

        fresh.stop();
    }

    @Test
    @DisplayName("ReplayError carries the correct seqNum and cause")
    void replayErrorCarriesCorrectSeqNumAndCause() throws Exception {
        Proc<Integer, String> proc =
                Proc.spawn(
                        0,
                        (s, m) -> {
                            if ("fail".equals(m)) throw new IllegalStateException("on purpose");
                            return s + 1;
                        });

        Proc<Integer, String> source = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(source);
        recorder.<Integer>ask("ok").get();
        recorder.<Integer>ask("fail").get();
        List<MessageRecorder.Entry<String>> log = recorder.snapshot();
        source.stop();

        MessageReplayer<Integer, String> replayer = MessageReplayer.create(proc);
        MessageReplayer.Result<Integer> result = replayer.replay(log);

        assertThat(result.errors()).hasSize(1);
        MessageReplayer.ReplayError error = result.errors().get(0);
        assertThat(error.seqNum()).isEqualTo(2L);
        assertThat(error.message()).isEqualTo("fail");
        assertThat(error.cause()).isInstanceOf(IllegalStateException.class);
        assertThat(error.cause().getMessage()).isEqualTo("on purpose");

        proc.stop();
    }

    @Test
    @DisplayName("messages are replayed in the same order as recorded")
    void messagesReplayedInOrder() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();

        Proc<Integer, String> original = Proc.spawn(0, (s, m) -> s + 1);
        MessageRecorder<String> recorder = MessageRecorder.wrap(original);

        recorder.<Integer>ask("first").get();
        recorder.<Integer>ask("second").get();
        recorder.<Integer>ask("third").get();

        List<MessageRecorder.Entry<String>> log = recorder.snapshot();
        original.stop();

        Proc<Integer, String> fresh =
                Proc.spawn(
                        0,
                        (s, m) -> {
                            received.add(m);
                            return s + 1;
                        });
        MessageReplayer<Integer, String> replayer = MessageReplayer.create(fresh);
        replayer.replay(log);

        assertThat(received).containsExactly("first", "second", "third");

        fresh.stop();
    }
}
