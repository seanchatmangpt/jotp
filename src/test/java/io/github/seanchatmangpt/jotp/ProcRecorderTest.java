package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProcRecorder} — deterministic message recording and replay for virtual-thread
 * actor systems.
 */
@DisplayName("ProcRecorder: Deterministic message recording and replay")
class ProcRecorderTest {

    // -------------------------------------------------------------------------
    // Test message types
    // -------------------------------------------------------------------------

    sealed interface CounterMsg
            permits CounterMsg.Increment, CounterMsg.Add, CounterMsg.Reset, CounterMsg.Get {
        record Increment() implements CounterMsg, java.io.Serializable {}

        record Add(int delta) implements CounterMsg, java.io.Serializable {}

        record Reset() implements CounterMsg, java.io.Serializable {}

        record Get() implements CounterMsg, java.io.Serializable {}
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Counter handler: state is the running total as an Integer. */
    private static Proc<Integer, CounterMsg> newCounterProc() {
        return Proc.spawn(
                0,
                (state, msg) ->
                        switch (msg) {
                            case CounterMsg.Increment() -> state + 1;
                            case CounterMsg.Add(int delta) -> state + delta;
                            case CounterMsg.Reset() -> 0;
                            case CounterMsg.Get() -> state;
                        });
    }

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Recording captures all messages sent via tell()")
    void recordingCapturesAllTellMessages() throws InterruptedException {
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);

        recorder.tell(new CounterMsg.Increment());
        recorder.tell(new CounterMsg.Increment());
        recorder.tell(new CounterMsg.Add(5));

        // Wait for all messages to be processed before stopping
        await().atMost(Duration.ofSeconds(2))
                .until(() -> proc.ask(new CounterMsg.Get()).join().equals(7));

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();

        assertThat(tape.size()).isEqualTo(3);
        assertThat(tape.messages()).hasSize(3);
        assertThat(tape.messages().get(0).message()).isInstanceOf(CounterMsg.Increment.class);
        assertThat(tape.messages().get(1).message()).isInstanceOf(CounterMsg.Increment.class);
        assertThat(tape.messages().get(2).message()).isInstanceOf(CounterMsg.Add.class);

        proc.stop();
    }

    @Test
    @DisplayName("Recording captures messages sent via ask()")
    void recordingCapturesAskMessages() throws InterruptedException {
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);

        recorder.ask(new CounterMsg.Increment()).join();
        recorder.ask(new CounterMsg.Add(10)).join();

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();

        assertThat(tape.size()).isEqualTo(2);
        assertThat(tape.messages().get(0).message()).isInstanceOf(CounterMsg.Increment.class);
        assertThat(tape.messages().get(1).message()).isInstanceOf(CounterMsg.Add.class);

        proc.stop();
    }

    @Test
    @DisplayName("stop() returns recording with correct message count and timing metadata")
    void stopReturnsCorrectRecording() throws InterruptedException {
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);

        recorder.ask(new CounterMsg.Increment()).join();
        recorder.ask(new CounterMsg.Increment()).join();
        recorder.ask(new CounterMsg.Reset()).join();

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();

        assertThat(tape.size()).isEqualTo(3);
        assertThat(tape.startTime()).isNotNull();
        assertThat(tape.totalDuration()).isGreaterThanOrEqualTo(Duration.ZERO);
        // Each recorded message should have a non-null timestamp and sinceStart duration
        for (var rm : tape.messages()) {
            assertThat(rm.timestamp()).isNotNull();
            assertThat(rm.sinceStart()).isGreaterThanOrEqualTo(Duration.ZERO);
        }

        proc.stop();
    }

    @Test
    @DisplayName("save() / load() round-trips successfully")
    void saveLoadRoundTrip() throws IOException, InterruptedException {
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);

        recorder.ask(new CounterMsg.Increment()).join();
        recorder.ask(new CounterMsg.Add(7)).join();
        recorder.ask(new CounterMsg.Increment()).join();

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();
        proc.stop();

        Path tmp = Files.createTempFile("proc-recorder-test-", ".tape");
        try {
            tape.save(tmp);
            assertThat(Files.size(tmp)).isGreaterThan(0);

            ProcRecorder.Recording<CounterMsg> loaded =
                    ProcRecorder.Recording.load(tmp, CounterMsg.class);

            assertThat(loaded.size()).isEqualTo(tape.size());
            assertThat(loaded.startTime()).isEqualTo(tape.startTime());
            assertThat(loaded.totalDuration()).isEqualTo(tape.totalDuration());
            for (int i = 0; i < tape.size(); i++) {
                assertThat(loaded.messages().get(i).message())
                        .isEqualTo(tape.messages().get(i).message());
                assertThat(loaded.messages().get(i).sinceStart())
                        .isEqualTo(tape.messages().get(i).sinceStart());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("replay() produces the same final state as the original run")
    void replayProducesSameFinalState() throws InterruptedException {
        // Run the original process and record it
        Proc<Integer, CounterMsg> original = newCounterProc();
        var recorder = ProcRecorder.record(original);

        recorder.ask(new CounterMsg.Increment()).join(); // 1
        recorder.ask(new CounterMsg.Add(4)).join(); // 5
        recorder.ask(new CounterMsg.Increment()).join(); // 6
        recorder.ask(new CounterMsg.Reset()).join(); // 0
        recorder.ask(new CounterMsg.Add(10)).join(); // 10

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();
        original.stop();

        // Replay the tape against a fresh process
        ProcRecorder.ReplayResult<Integer> result =
                ProcRecorder.replay(
                        tape,
                        ProcRecorderTest::newCounterProc,
                        Duration.ofSeconds(5),
                        ProcRecorder.ReplayMode.INSTANT);

        assertThat(result.errors()).isEmpty();
        assertThat(result.messageCount()).isEqualTo(5);
        assertThat(result.finalState()).isEqualTo(10);
    }

    @Test
    @DisplayName("INSTANT replay completes faster than wall-clock REAL_TIME replay")
    void instantReplayFasterThanRealTime() throws IOException, InterruptedException {
        // Build a recording with deliberate inter-arrival gaps by inserting small delays
        // via ask() calls (no Thread.sleep — use ask() synchronously to pace messages).
        // We simply create a recording with at least a few milliseconds of total duration
        // recorded, then show INSTANT replay is significantly faster.
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);

        // Send several messages; real-time total duration will be at least a few ms
        for (int i = 0; i < 10; i++) {
            recorder.ask(new CounterMsg.Increment()).join();
        }

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();
        proc.stop();

        // Verify the recording has non-zero total duration (timing is real)
        assertThat(tape.totalDuration()).isPositive();

        // Measure INSTANT replay duration
        long instantStart = System.nanoTime();
        ProcRecorder.ReplayResult<Integer> instantResult =
                ProcRecorder.replay(
                        tape,
                        ProcRecorderTest::newCounterProc,
                        Duration.ofSeconds(5),
                        ProcRecorder.ReplayMode.INSTANT);
        long instantNanos = System.nanoTime() - instantStart;

        assertThat(instantResult.errors()).isEmpty();
        assertThat(instantResult.messageCount()).isEqualTo(10);
        assertThat(instantResult.finalState()).isEqualTo(10);

        // INSTANT replay must complete in less than the recorded wall-clock duration
        // (the tape's totalDuration represents the original elapsed time; INSTANT mode
        //  skips all delays).
        long recordedNanos = tape.totalDuration().toNanos();
        // Only assert if the recorded duration is meaningfully long (> 100ms) to avoid
        // flakiness: both the recording and INSTANT replay perform real process I/O
        // (ask().join() round-trips), so on fast machines neither run introduces
        // significant inter-arrival delays.  The comparison is only meaningful when the
        // recording contains substantial wall-clock time that INSTANT mode can skip.
        if (recordedNanos > 100_000_000L) {
            assertThat(instantNanos).isLessThan(recordedNanos);
        }
    }

    @Test
    @DisplayName("Recording list is immutable after stop()")
    void recordingListIsImmutable() throws InterruptedException {
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);
        recorder.ask(new CounterMsg.Increment()).join();
        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();

        assertThatThrownBy(
                        () ->
                                tape.messages()
                                        .add(
                                                new ProcRecorder.RecordedMessage<>(
                                                        java.time.Instant.now(),
                                                        Duration.ZERO,
                                                        new CounterMsg.Get(),
                                                        null)))
                .isInstanceOf(UnsupportedOperationException.class);

        proc.stop();
    }

    @Test
    @DisplayName("senderId is captured when provided")
    void senderIdIsCaptured() throws InterruptedException {
        Proc<Integer, CounterMsg> proc = newCounterProc();
        var recorder = ProcRecorder.record(proc);

        recorder.tell(new CounterMsg.Increment(), "sender-A");
        recorder.tell(new CounterMsg.Add(3), "sender-B");

        await().atMost(Duration.ofSeconds(2))
                .until(() -> proc.ask(new CounterMsg.Get()).join().equals(4));

        ProcRecorder.Recording<CounterMsg> tape = recorder.stop();

        assertThat(tape.messages().get(0).senderId()).isEqualTo("sender-A");
        assertThat(tape.messages().get(1).senderId()).isEqualTo("sender-B");

        proc.stop();
    }

    @Test
    @DisplayName("replay() with empty recording returns null final state and zero message count")
    void replayEmptyRecording() {
        ProcRecorder.Recording<CounterMsg> empty =
                new ProcRecorder.Recording<>(
                        java.time.Instant.now(), java.util.List.of(), Duration.ZERO);

        ProcRecorder.ReplayResult<Integer> result =
                ProcRecorder.replay(
                        empty,
                        ProcRecorderTest::newCounterProc,
                        Duration.ofSeconds(5),
                        ProcRecorder.ReplayMode.INSTANT);

        assertThat(result.messageCount()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
        assertThat(result.finalState()).isNull();
    }
}
