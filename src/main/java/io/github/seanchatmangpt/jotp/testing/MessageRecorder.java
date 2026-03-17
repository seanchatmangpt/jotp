package io.github.seanchatmangpt.jotp.testing;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records messages sent to a process for deterministic replay testing.
 *
 * <p>Wraps a {@link io.github.seanchatmangpt.jotp.ProcRef} and captures all messages with
 * sequence numbers. Messages can be replayed to verify deterministic behavior.
 */
public final class MessageRecorder<M> {
  /** Recorded message with sequence and timestamp. */
  public record RecordedMessage<M>(long sequence, M message, Instant timestamp) {}

  private final List<RecordedMessage<M>> recordedMessages = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong(0);
  private final String processName;

  private MessageRecorder(String processName) {
    this.processName = processName;
  }

  /**
   * Create a new message recorder.
   *
   * @param processName the name/identifier of the process being recorded
   * @param <M> message type
   * @return a new recorder instance
   */
  public static <M> MessageRecorder<M> create(String processName) {
    return new MessageRecorder<>(processName);
  }

  /**
   * Record a message being sent.
   *
   * @param message the message to record
   * @return the sequence number assigned to this message
   */
  public long record(M message) {
    long seq = sequence.getAndIncrement();
    recordedMessages.add(new RecordedMessage<>(seq, message, Instant.now()));
    return seq;
  }

  /**
   * Get all recorded messages in order.
   *
   * @return unmodifiable list of recorded messages
   */
  public List<RecordedMessage<M>> getRecordedMessages() {
    return Collections.unmodifiableList(new ArrayList<>(recordedMessages));
  }

  /**
   * Get a recorded message by sequence number.
   *
   * @param seq the sequence number
   * @return Optional containing the message if found
   */
  public Optional<RecordedMessage<M>> getRecordedMessage(long seq) {
    return recordedMessages.stream().filter(m -> m.sequence() == seq).findFirst();
  }

  /**
   * Get the total number of recorded messages.
   *
   * @return number of messages recorded
   */
  public long getRecordedCount() {
    return recordedMessages.size();
  }

  /**
   * Get only messages of a specific type (via instanceof).
   *
   * @param messageType the class to filter by
   * @return list of recorded messages matching the type
   */
  public List<RecordedMessage<M>> getRecordedMessagesOfType(Class<?> messageType) {
    return recordedMessages.stream()
        .filter(m -> messageType.isInstance(m.message()))
        .toList();
  }

  /**
   * Clear all recorded messages.
   */
  public void clear() {
    recordedMessages.clear();
    sequence.set(0);
  }

  /**
   * Get the process name this recorder is tracking.
   *
   * @return the process name
   */
  public String getProcessName() {
    return processName;
  }

  /**
   * Export recorded messages as a replay sequence.
   *
   * @return list of messages in order
   */
  public List<M> getReplaySequence() {
    return recordedMessages.stream().map(RecordedMessage::message).toList();
  }
}
