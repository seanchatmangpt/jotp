package io.github.seanchatmangpt.jotp.testing;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for MessageRecorder — record/replay for deterministic testing.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Recording message sequences with source, target, timestamp
 *   <li>Replaying recorded sequences and comparing checksums
 *   <li>Fault injection at specific message boundaries
 *   <li>DeterministicClock integration for reproducible timing
 *   <li>Multiple processes recorded together
 * </ul>
 */
class MessageRecorderTest {

  @TempDir Path tempDir;

  // ─── Basic Recording Tests ───────────────────────────────────────────

  @Test
  void recordSingleMessage() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "hello");
      assertThat(recorder.messages()).hasSize(1);
      assertThat(recorder.messages().get(0).message()).isEqualTo("hello");
    }
    // File should exist after close
    assertThat(recordFile).exists();
  }

  @Test
  void recordMultipleMessages() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1");
      recorder.recordMessage("proc-b", "proc-c", "msg2");
      recorder.recordMessage("proc-c", "proc-a", "msg3");

      assertThat(recorder.messages()).hasSize(3);
      assertThat(recorder.messages().get(0).sequence()).isEqualTo(0);
      assertThat(recorder.messages().get(1).sequence()).isEqualTo(1);
      assertThat(recorder.messages().get(2).sequence()).isEqualTo(2);
    }
  }

  @Test
  void recordMessageWithResult() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "increment", 42);

      MessageRecorder.RecordedMessage msg = recorder.messages().get(0);
      assertThat(msg.message()).isEqualTo("increment");
      assertThat(msg.result()).isEqualTo(42);
    }
  }

  // ─── Query Tests ───────────────────────────────────────────────────

  @Test
  void messagesTo() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1");
      recorder.recordMessage("proc-a", "proc-c", "msg2");
      recorder.recordMessage("proc-a", "proc-b", "msg3");

      var toBProcB = recorder.messagesTo("proc-b");
      assertThat(toBProcB).hasSize(2);
      assertThat(toBProcB).allMatch(m -> m.targetProcessId().equals("proc-b"));
    }
  }

  @Test
  void messagesFrom() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1");
      recorder.recordMessage("proc-a", "proc-c", "msg2");
      recorder.recordMessage("proc-b", "proc-a", "msg3");

      var fromProcA = recorder.messagesFrom("proc-a");
      assertThat(fromProcA).hasSize(2);
      assertThat(fromProcA).allMatch(m -> m.sourceProcessId().equals("proc-a"));
    }
  }

  @Test
  void messagesBetween() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      for (int i = 0; i < 10; i++) {
        recorder.recordMessage("src", "dst", "msg-" + i);
      }

      var range = recorder.messagesBetween(3, 6);
      assertThat(range).hasSize(4);
      assertThat(range.get(0).sequence()).isEqualTo(3);
      assertThat(range.get(3).sequence()).isEqualTo(6);
    }
  }

  // ─── Crash and Fault Injection Tests ───────────────────────────────

  @Test
  void recordCrash() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1");
      Exception ex = new RuntimeException("simulated crash");
      recorder.recordCrash("proc-b", ex);

      assertThat(recorder.crashes()).hasSize(1);
      assertThat(recorder.crashes().get(0).processId()).isEqualTo("proc-b");
      assertThat(recorder.crashes().get(0).errorMessage()).contains("simulated crash");
    }
  }

  @Test
  void injectCrashAtSequence() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1");
      recorder.recordMessage("proc-a", "proc-b", "msg2");

      Exception ex = new RuntimeException("injected at msg 5");
      recorder.injectCrashAt("proc-b", 5, ex);

      assertThat(recorder.shouldInjectCrash("proc-b", 5)).isTrue();
      assertThat(recorder.shouldInjectCrash("proc-b", 3)).isFalse();
    }
  }

  // ─── Deterministic Clock Integration Tests ──────────────────────────

  @Test
  void logicalTimeWithDeterministicClock() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    DeterministicClock clock = DeterministicClock.create().install();
    try {
      try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
        long time1 = clock.nanoTime();
        recorder.recordMessage("src", "dst", "msg1");

        clock.advanceNanos(1000);
        long time2 = clock.nanoTime();
        recorder.recordMessage("src", "dst", "msg2");

        var messages = recorder.messages();
        assertThat(messages.get(0).logicalTime()).isEqualTo(time1);
        assertThat(messages.get(1).logicalTime()).isEqualTo(time2);
      }
    } finally {
      DeterministicClock.uninstall();
    }
  }

  @Test
  void timeoutDeterminism() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    DeterministicClock clock = DeterministicClock.create().install();
    try {
      try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
        long start = clock.nanoTime();
        recorder.recordMessage("src", "dst", "timeout-test");

        // Advance clock by 100ms
        clock.advanceMillis(100);

        long elapsed = clock.elapsedSince(start).toMillis();
        assertThat(elapsed).isEqualTo(100);
      }
    } finally {
      DeterministicClock.uninstall();
    }
  }

  // ─── Multi-Process Recording Tests ──────────────────────────────────

  @Test
  void multiProcessSequence() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      // Simulate a chain: A -> B -> C -> A
      recorder.recordMessage("proc-a", "proc-b", "init");
      recorder.recordMessage("proc-b", "proc-c", "process");
      recorder.recordMessage("proc-c", "proc-a", "result");

      var messages = recorder.messages();
      assertThat(messages).hasSize(3);

      // Verify chain integrity
      assertThat(messages.get(0).sourceProcessId()).isEqualTo("proc-a");
      assertThat(messages.get(0).targetProcessId()).isEqualTo("proc-b");

      assertThat(messages.get(1).sourceProcessId()).isEqualTo("proc-b");
      assertThat(messages.get(1).targetProcessId()).isEqualTo("proc-c");

      assertThat(messages.get(2).sourceProcessId()).isEqualTo("proc-c");
      assertThat(messages.get(2).targetProcessId()).isEqualTo("proc-a");
    }
  }

  // ─── Serialization Tests ───────────────────────────────────────────

  @Test
  void serializeToJson() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.setApplicationVersion("1.2.3");
      recorder.setNodeId("node-main");
      recorder.recordMessage("proc-a", "proc-b", "hello");
      recorder.recordMessage("proc-b", "proc-c", "world");
    }

    String json = Files.readString(recordFile);
    assertThat(json).contains("applicationVersion", "1.2.3");
    assertThat(json).contains("nodeId", "node-main");
    assertThat(json).contains("hello");
    assertThat(json).contains("world");
  }

  @Test
  void jsonIsHumanReadable() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1", "state1");
      recorder.recordMessage("proc-b", "proc-c", "msg2", "state2");
    }

    String json = Files.readString(recordFile);
    // Should have proper structure
    assertThat(json).contains("{");
    assertThat(json).contains("}");
    assertThat(json).contains("\"messages\"");
    assertThat(json).contains("\"sequence\"");
  }

  // ─── Replay Tests ──────────────────────────────────────────────────

  @Test
  void loadAndReplayRecording() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");

    // First: record
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "msg1");
      recorder.recordMessage("proc-b", "proc-c", "msg2");
      recorder.recordMessage("proc-c", "proc-a", "msg3");
    }

    // Second: load and verify
    try (MessageRecorder loaded = MessageRecorder.loadRecording(recordFile)) {
      var messages = loaded.messages();
      assertThat(messages).hasSize(3);
      assertThat(messages.get(0).message()).isEqualTo("msg1");
      assertThat(messages.get(1).message()).isEqualTo("msg2");
      assertThat(messages.get(2).message()).isEqualTo("msg3");
    }
  }

  @Test
  void replayPreservesMetadata() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");

    // Record with metadata
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.setApplicationVersion("2.0.1");
      recorder.setNodeId("replicated-node");
      recorder.recordMessage("src", "dst", "test");
    }

    // Load and verify metadata preserved
    try (MessageRecorder loaded = MessageRecorder.loadRecording(recordFile)) {
      var recording = loaded.getLoadedRecording();
      assertThat(recording).isNotNull();
      assertThat(recording.applicationVersion()).isEqualTo("2.0.1");
    }
  }

  @Test
  void stateChecksumVerification() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");

    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("a", "b", "op1", "state-1");
      recorder.recordMessage("b", "c", "op2", "state-2");
    }

    try (MessageRecorder loaded = MessageRecorder.loadRecording(recordFile)) {
      String checksum = loaded.getFinalStateChecksum();
      assertThat(checksum).isNotNull();
      assertThat(checksum).isNotEmpty();
    }
  }

  // ─── Lifecycle Tests ──────────────────────────────────────────────────

  @Test
  void closeClosesRecorder() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    MessageRecorder recorder = MessageRecorder.startRecording(recordFile);
    recorder.recordMessage("a", "b", "msg");
    recorder.close();

    // Attempting to record after close should fail
    assertThatThrownBy(() -> recorder.recordMessage("c", "d", "msg2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void tryWithResourcesAutoCloses() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("a", "b", "msg");
    }
    // Should be saved automatically
    assertThat(recordFile).exists();
  }

  // ─── Edge Cases ───────────────────────────────────────────────────

  @Test
  void emptyRecording() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      // Record nothing
    }

    try (MessageRecorder loaded = MessageRecorder.loadRecording(recordFile)) {
      assertThat(loaded.messages()).isEmpty();
    }
  }

  @Test
  void recordDebugString() throws IOException {
    Path recordFile = tempDir.resolve("test.jrecord");
    try (MessageRecorder recorder = MessageRecorder.startRecording(recordFile)) {
      recorder.recordMessage("proc-a", "proc-b", "test-msg");
      var msg = recorder.messages().get(0);
      String debug = msg.toDebugString();

      assertThat(debug).contains("seq=0");
      assertThat(debug).contains("from=proc-a");
      assertThat(debug).contains("to=proc-b");
      assertThat(debug).contains("msg=test-msg");
    }
  }
}
