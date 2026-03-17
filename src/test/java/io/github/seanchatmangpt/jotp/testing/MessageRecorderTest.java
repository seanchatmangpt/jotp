package io.github.seanchatmangpt.jotp.testing;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for MessageRecorder. */
class MessageRecorderTest {

  private MessageRecorder<String> recorder;

  @BeforeEach
  void setup() {
    recorder = MessageRecorder.create("test-proc");
  }

  @Test
  void testCreateRecorder() {
    assertThat(recorder.getProcessName()).isEqualTo("test-proc");
    assertThat(recorder.getRecordedCount()).isZero();
  }

  @Test
  void testRecordSingleMessage() {
    long seq = recorder.record("hello");

    assertThat(seq).isEqualTo(0);
    assertThat(recorder.getRecordedCount()).isOne();
    assertThat(recorder.getRecordedMessages()).hasSize(1);
  }

  @Test
  void testRecordMultipleMessages() {
    recorder.record("msg1");
    recorder.record("msg2");
    recorder.record("msg3");

    assertThat(recorder.getRecordedCount()).isEqualTo(3);
    List<MessageRecorder.RecordedMessage<String>> messages = recorder.getRecordedMessages();
    assertThat(messages.get(0).sequence()).isEqualTo(0);
    assertThat(messages.get(1).sequence()).isEqualTo(1);
    assertThat(messages.get(2).sequence()).isEqualTo(2);
  }

  @Test
  void testSequenceNumbers() {
    long seq1 = recorder.record("msg1");
    long seq2 = recorder.record("msg2");
    long seq3 = recorder.record("msg3");

    assertThat(seq1).isEqualTo(0);
    assertThat(seq2).isEqualTo(1);
    assertThat(seq3).isEqualTo(2);
  }

  @Test
  void testGetRecordedMessage() {
    recorder.record("msg1");
    recorder.record("msg2");
    recorder.record("msg3");

    var msg = recorder.getRecordedMessage(1);
    assertThat(msg).isPresent();
    assertThat(msg.get().message()).isEqualTo("msg2");
  }

  @Test
  void testGetNonexistentMessage() {
    recorder.record("msg1");

    var msg = recorder.getRecordedMessage(99);
    assertThat(msg).isEmpty();
  }

  @Test
  void testTimestampRecorded() {
    Instant before = Instant.now();
    recorder.record("msg1");
    Instant after = Instant.now();

    var msg = recorder.getRecordedMessage(0);
    assertThat(msg).isPresent();
    assertThat(msg.get().timestamp()).isGreaterThanOrEqualTo(before);
    assertThat(msg.get().timestamp()).isLessThanOrEqualTo(after.plusMillis(10));
  }

  @Test
  void testClear() {
    recorder.record("msg1");
    recorder.record("msg2");
    assertThat(recorder.getRecordedCount()).isEqualTo(2);

    recorder.clear();
    assertThat(recorder.getRecordedCount()).isZero();
    assertThat(recorder.getRecordedMessages()).isEmpty();
  }

  @Test
  void testUnmodifiableList() {
    recorder.record("msg1");
    recorder.record("msg2");

    List<MessageRecorder.RecordedMessage<String>> messages = recorder.getRecordedMessages();
    assertThatThrownBy(() -> messages.add(null)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testGetReplaySequence() {
    recorder.record("msg1");
    recorder.record("msg2");
    recorder.record("msg3");

    List<String> sequence = recorder.getReplaySequence();
    assertThat(sequence).containsExactly("msg1", "msg2", "msg3");
  }

  @Test
  void testProcessNameImmutable() {
    MessageRecorder<String> rec1 = MessageRecorder.create("proc-1");
    MessageRecorder<String> rec2 = MessageRecorder.create("proc-2");

    assertThat(rec1.getProcessName()).isEqualTo("proc-1");
    assertThat(rec2.getProcessName()).isEqualTo("proc-2");
  }

  @Test
  void testConcurrentRecording() throws InterruptedException {
    MessageRecorder<Integer> intRecorder = MessageRecorder.create("concurrent");

    Thread t1 = new Thread(() -> intRecorder.record(1));
    Thread t2 = new Thread(() -> intRecorder.record(2));
    Thread t3 = new Thread(() -> intRecorder.record(3));

    t1.start();
    t2.start();
    t3.start();

    t1.join();
    t2.join();
    t3.join();

    assertThat(intRecorder.getRecordedCount()).isEqualTo(3);
    List<Long> seqs =
        intRecorder.getRecordedMessages().stream()
            .map(MessageRecorder.RecordedMessage::sequence)
            .toList();
    assertThat(seqs).contains(0L, 1L, 2L);
  }
}
