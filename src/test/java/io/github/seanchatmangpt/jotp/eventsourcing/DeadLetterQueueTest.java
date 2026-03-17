package io.github.seanchatmangpt.jotp.eventsourcing;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DeadLetterQueueTest {

  @Test
  public void createDLQMessage() {
    Exception cause = new RuntimeException("Payment timeout");
    DeadLetterQueue.DLQMessage msg =
        DeadLetterQueue.DLQMessage.of("msg-1", "order-123", "event-data", cause);

    assertThat(msg.messageId()).isEqualTo("msg-1");
    assertThat(msg.aggregateId()).isEqualTo("order-123");
    assertThat(msg.eventData()).isEqualTo("event-data");
    assertThat(msg.errorMessage()).isEqualTo("Payment timeout");
    assertThat(msg.cause()).isEqualTo(cause);
    assertThat(msg.retryCount()).isZero();
    assertThat(msg.maxRetries()).isEqualTo(3);
  }

  @Test
  public void dlqMessageWithoutAggregateId() {
    Exception cause = new RuntimeException("Processing error");
    DeadLetterQueue.DLQMessage msg =
        DeadLetterQueue.DLQMessage.of("msg-2", "event-payload", cause);

    assertThat(msg.messageId()).isEqualTo("msg-2");
    assertThat(msg.aggregateId()).isNull();
    assertThat(msg.errorMessage()).isEqualTo("Processing error");
  }

  @Test
  public void dlqStats() {
    DeadLetterQueue.DLQStats stats =
        new DeadLetterQueue.DLQStats(10, 5, 2.5, 30, List.of("Timeout", "Network error"));

    assertThat(stats.queueSize()).isEqualTo(10);
    assertThat(stats.totalFailed()).isEqualTo(5);
    assertThat(stats.avgRetryCount()).isEqualTo(2.5);
    assertThat(stats.oldestMessageAgeMinutes()).isEqualTo(30);
    assertThat(stats.failureReasons()).hasSize(2);
  }

  @Test
  public void dlqStatsWithEmptyReasons() {
    DeadLetterQueue.DLQStats stats =
        new DeadLetterQueue.DLQStats(0, 0, 0.0, 0, new ArrayList<>());

    assertThat(stats.queueSize()).isZero();
    assertThat(stats.totalFailed()).isZero();
    assertThat(stats.failureReasons()).isEmpty();
  }

  @Test
  public void dlqMessageRetryTracking() {
    Exception cause = new RuntimeException("Error 1");
    DeadLetterQueue.DLQMessage msg =
        DeadLetterQueue.DLQMessage.of("msg-3", "agg-1", "payload", cause);

    assertThat(msg.retryCount()).isZero();
    assertThat(msg.maxRetries()).isEqualTo(3);
    assertThat(msg.cause()).isNotNull();
  }

  @Test
  public void multipleFailureReasons() {
    List<String> reasons = List.of(
        "Connection timeout",
        "Invalid payload",
        "Service unavailable",
        "Authentication failed"
    );

    DeadLetterQueue.DLQStats stats =
        new DeadLetterQueue.DLQStats(100, 25, 1.5, 60, reasons);

    assertThat(stats.failureReasons()).hasSize(4);
    assertThat(stats.failureReasons()).contains("Connection timeout");
  }

  @Test
  public void dlqMessageCreatedAtTracking() {
    Exception cause = new RuntimeException("Error");
    DeadLetterQueue.DLQMessage msg =
        DeadLetterQueue.DLQMessage.of("msg-4", "agg-1", "payload", cause);

    assertThat(msg.createdAt()).isNotNull();
    assertThat(msg.createdAt()).isBefore(java.time.Instant.now().plusSeconds(1));
  }

  @Test
  public void dlqMessageMaxRetries() {
    Exception cause = new RuntimeException("Error");
    DeadLetterQueue.DLQMessage msg =
        DeadLetterQueue.DLQMessage.of("msg-5", "agg-1", "payload", cause);

    // Default max retries is 3
    assertThat(msg.maxRetries()).isEqualTo(3);

    // Simulate retries
    int attempts = 0;
    while (attempts < msg.maxRetries()) {
      attempts++;
    }

    assertThat(attempts).isEqualTo(msg.maxRetries());
  }

  @Test
  public void dlqStatsCalculations() {
    // Test with specific numbers
    DeadLetterQueue.DLQStats stats =
        new DeadLetterQueue.DLQStats(
            20, // queueSize
            10, // totalFailed
            1.5, // avgRetryCount
            120, // oldestMessageAgeMinutes
            List.of("Error A", "Error B")
        );

    assertThat(stats.queueSize()).isEqualTo(20);
    assertThat(stats.avgRetryCount()).isGreaterThan(0);
    assertThat(stats.oldestMessageAgeMinutes()).isGreaterThanOrEqualTo(0);
  }

  @Test
  public void dlqMessageValidation() {
    Exception ex = new RuntimeException("Test error");

    // Create with all fields
    DeadLetterQueue.DLQMessage msg =
        new DeadLetterQueue.DLQMessage(
            "msg-id",
            "agg-id",
            "payload",
            "Error message",
            ex,
            2,
            5,
            java.time.Instant.now()
        );

    assertThat(msg.messageId()).isNotEmpty();
    assertThat(msg.aggregateId()).isNotEmpty();
    assertThat(msg.eventData()).isNotNull();
    assertThat(msg.cause()).isNotNull();
  }

  @Test
  public void dlqStatsAgeCalculations() {
    // Test various age scenarios
    DeadLetterQueue.DLQStats recentStats =
        new DeadLetterQueue.DLQStats(5, 2, 0.5, 5, List.of("Recent error"));

    DeadLetterQueue.DLQStats oldStats =
        new DeadLetterQueue.DLQStats(2, 1, 2.0, 1440, List.of("Old error"));

    assertThat(recentStats.oldestMessageAgeMinutes())
        .isLessThan(oldStats.oldestMessageAgeMinutes());
  }
}
