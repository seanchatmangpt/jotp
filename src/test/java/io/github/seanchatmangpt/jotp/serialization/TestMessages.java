package io.github.seanchatmangpt.jotp.serialization;

/**
 * Test message definitions for serialization testing.
 *
 * <p>These sealed message types model a typical JOTP application domain (user management). They
 * demonstrate the expected patterns: sealed interfaces, records, and enums.
 */
public final class TestMessages {
  private TestMessages() {}

  /** Sealed interface representing all user-related events. */
  public sealed interface UserEvent permits UserCreated, UserDeleted, UserUpdated {
    String userId();
  }

  /** Record: user creation event. */
  public record UserCreated(String userId, String email, String name) implements UserEvent {}

  /** Record: user deletion event. */
  public record UserDeleted(String userId, String reason) implements UserEvent {}

  /** Record: user update event. */
  public record UserUpdated(String userId, String email) implements UserEvent {}

  /** Sealed interface for order events. */
  public sealed interface OrderEvent permits OrderPlaced, OrderCancelled {
    String orderId();
  }

  /** Record: order placed event. */
  public record OrderPlaced(String orderId, String customerId, double total) implements OrderEvent {}

  /** Record: order cancelled event. */
  public record OrderCancelled(String orderId, String reason) implements OrderEvent {}

  /** Enum for testing enum serialization. */
  public enum Status {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED
  }

  /** Simple record without interface (for testing edge cases). */
  public record SimpleMessage(String content, int count) {}
}
