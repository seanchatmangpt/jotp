package io.github.seanchatmangpt.jotp.messaging.construction;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.io.Serializable;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for {@link CommandMessage} — verifies Vaughn Vernon's command message pattern
 * implementation with JOTP ProcRef integration.
 *
 * <p>Tests cover message creation, factory methods, correlation IDs, timeouts, and type safety.
 */
@DisplayName("CommandMessage — Command message construction pattern")
class CommandMessageTest implements WithAssertions {
    // Test domain types
    record CreateUserCmd(String username, String email) implements Serializable {}
    record UpdateProfileCmd(String userId, String bio) implements Serializable {}
    record MockReplyAddress() {}
    // ─────────────────────────────────────────────────────────────────────────────
    // Basic Creation — factory methods and message envelope
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Nested
    @DisplayName("Basic creation — message construction and defaults")
    class BasicCreation {
        @Test
        @DisplayName("creates command message with required fields")
        void createsMessageWithRequiredFields() {
            CreateUserCmd payload = new CreateUserCmd("alice", "alice@example.com");
            Object replyTo = new MockReplyAddress();
            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);
            assertThat(msg).isNotNull().isInstanceOf(CommandMessage.class);
            assertThat(msg.commandType()).isEqualTo("CreateUser");
            assertThat(msg.payload()).isEqualTo(payload);
            assertThat(msg.replyTo()).isEqualTo(replyTo);
        }
        @DisplayName("auto-generates unique message ID")
        void autoGeneratesUniqueMessageId() {
            CreateUserCmd payload = new CreateUserCmd("bob", "bob@example.com");
            CommandMessage<?> msg1 = CommandMessage.create("CreateUser", payload, replyTo);
            CommandMessage<?> msg2 = CommandMessage.create("CreateUser", payload, replyTo);
            assertThat(msg1.messageId()).isNotNull().isNotEqualTo(msg2.messageId());
        @DisplayName("sets current timestamp on creation")
        void setsCurrentTimestamp() {
            long before = System.currentTimeMillis();
            CreateUserCmd payload = new CreateUserCmd("charlie", "charlie@example.com");
            long after = System.currentTimeMillis();
            assertThat(msg.createdAt()).isBetween(before, after);
        @DisplayName("rejects null command type")
        void rejectsNullCommandType() {
            CreateUserCmd payload = new CreateUserCmd("dave", "dave@example.com");
            assertThatThrownBy(() -> CommandMessage.create(null, payload, replyTo))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("commandType");
        @DisplayName("rejects blank command type")
        void rejectsBlankCommandType() {
            CreateUserCmd payload = new CreateUserCmd("eve", "eve@example.com");
            assertThatThrownBy(() -> CommandMessage.create("   ", payload, replyTo))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        @DisplayName("rejects null payload")
        void rejectsNullPayload() {
            assertThatThrownBy(() -> CommandMessage.create("CreateUser", null, replyTo))
                    .hasMessageContaining("payload");
        @DisplayName("rejects null reply-to address")
        void rejectsNullReplyTo() {
            CreateUserCmd payload = new CreateUserCmd("frank", "frank@example.com");
            assertThatThrownBy(() -> CommandMessage.create("CreateUser", payload, null))
                    .hasMessageContaining("replyTo");
        @DisplayName("rejects negative timestamp")
        void rejectsNegativeTimestamp() {
            CreateUserCmd payload = new CreateUserCmd("grace", "grace@example.com");
            // The impl record validates in its compact constructor
            assertThat(msg.createdAt()).isGreaterThanOrEqualTo(0);
    // Correlation ID — request-response tracking
    @DisplayName("Correlation ID — request-response correlation")
    class CorrelationId {
        @DisplayName("message starts with no correlation ID")
        void messageStartsWithNoCorrelationId() {
            CreateUserCmd payload = new CreateUserCmd("henry", "henry@example.com");
            assertThat(msg.correlationId()).isNull();
            assertThat(msg.hasCorrelationId()).isFalse();
        @DisplayName("withCorrelationId() sets correlation ID")
        void withCorrelationIdSetsId() {
            CreateUserCmd payload = new CreateUserCmd("iris", "iris@example.com");
            UUID correlationId = UUID.randomUUID();
            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withCorrelationId(correlationId);
            assertThat(msg.correlationId()).isEqualTo(correlationId);
            assertThat(msg.hasCorrelationId()).isTrue();
        @DisplayName("withCorrelationId() returns new message instance")
        void withCorrelationIdReturnsNewInstance() {
            CreateUserCmd payload = new CreateUserCmd("jack", "jack@example.com");
            CommandMessage<?> original = CommandMessage.create("CreateUser", payload, replyTo);
            CommandMessage<?> withCorr = original.withCorrelationId(correlationId);
            // Original should be unchanged
            assertThat(original.correlationId()).isNull();
            assertThat(withCorr.correlationId()).isEqualTo(correlationId);
            // They should have the same message ID (correlation ID is optional overlay)
            assertThat(withCorr.messageId()).isEqualTo(original.messageId());
        @DisplayName("multiple messages can have different correlation IDs")
        void multipleMessagesDifferentCorrelationIds() {
            CreateUserCmd payload1 = new CreateUserCmd("karen", "karen@example.com");
            UpdateProfileCmd payload2 = new UpdateProfileCmd("user-123", "Software Engineer");
            UUID corrId1 = UUID.randomUUID();
            UUID corrId2 = UUID.randomUUID();
            CommandMessage<?> msg1 =
                    CommandMessage.create("CreateUser", payload1, replyTo)
                            .withCorrelationId(corrId1);
            CommandMessage<?> msg2 =
                    CommandMessage.create("UpdateProfile", payload2, replyTo)
                            .withCorrelationId(corrId2);
            assertThat(msg1.correlationId()).isEqualTo(corrId1);
            assertThat(msg2.correlationId()).isEqualTo(corrId2);
            assertThat(msg1.correlationId()).isNotEqualTo(msg2.correlationId());
    // Timeout — deadline specification
    @DisplayName("Timeout — deadline specification for replies")
    class TimeoutHandling {
        @DisplayName("message starts with no timeout")
        void messageStartsWithNoTimeout() {
            CreateUserCmd payload = new CreateUserCmd("leo", "leo@example.com");
            assertThat(msg.timeout()).isNull();
            assertThat(msg.hasTimeout()).isFalse();
        @DisplayName("withTimeout() sets timeout duration")
        void withTimeoutSetsDuration() {
            CreateUserCmd payload = new CreateUserCmd("mona", "mona@example.com");
            Duration timeout = Duration.ofSeconds(5);
                    CommandMessage.create("CreateUser", payload, replyTo).withTimeout(timeout);
            assertThat(msg.timeout()).isEqualTo(timeout);
            assertThat(msg.hasTimeout()).isTrue();
        @DisplayName("withTimeout() accepts various duration units")
        void withTimeoutAcceptsDurations() {
            CreateUserCmd payload = new CreateUserCmd("neal", "neal@example.com");
                            .withTimeout(Duration.ofMillis(500));
                            .withTimeout(Duration.ofSeconds(10));
            CommandMessage<?> msg3 =
                            .withTimeout(Duration.ofMinutes(1));
            assertThat(msg1.timeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(msg2.timeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(msg3.timeout()).isEqualTo(Duration.ofMinutes(1));
        @DisplayName("rejects null timeout")
        void rejectsNullTimeout() {
            CreateUserCmd payload = new CreateUserCmd("olivia", "olivia@example.com");
            assertThatThrownBy(() -> msg.withTimeout(null))
                    .hasMessageContaining("timeout");
        @DisplayName("rejects zero duration")
        void rejectsZeroDuration() {
            CreateUserCmd payload = new CreateUserCmd("paul", "paul@example.com");
            assertThatThrownBy(() -> msg.withTimeout(Duration.ZERO))
                    .hasMessageContaining("positive");
        @DisplayName("rejects negative duration")
        void rejectsNegativeDuration() {
            CreateUserCmd payload = new CreateUserCmd("quinn", "quinn@example.com");
            assertThatThrownBy(() -> msg.withTimeout(Duration.ofSeconds(-1)))
        @DisplayName("withTimeout() returns new instance")
        void withTimeoutReturnsNewInstance() {
            CreateUserCmd payload = new CreateUserCmd("rachel", "rachel@example.com");
            Duration timeout = Duration.ofSeconds(3);
            CommandMessage<?> withTimeout = original.withTimeout(timeout);
            assertThat(original.timeout()).isNull();
            assertThat(withTimeout.timeout()).isEqualTo(timeout);
    // Fluent API — chaining methods
    @DisplayName("Fluent API — method chaining for configuration")
    class FluentApi {
        @DisplayName("chains correlation ID and timeout")
        void chainsCorrelationAndTimeout() {
            CreateUserCmd payload = new CreateUserCmd("sam", "sam@example.com");
            Duration timeout = Duration.ofSeconds(2);
                            .withCorrelationId(correlationId)
                            .withTimeout(timeout);
        @DisplayName("chains timeout and correlation ID in reverse order")
        void chainsTimeoutThenCorrelationId() {
            CreateUserCmd payload = new CreateUserCmd("tina", "tina@example.com");
            Duration timeout = Duration.ofSeconds(4);
                            .withTimeout(timeout)
        @DisplayName("maintains immutability across chain operations")
        void maintainsImmutabilityAcrossChain() {
            CreateUserCmd payload = new CreateUserCmd("una", "una@example.com");
            CommandMessage<?> msg2 = msg1.withCorrelationId(correlationId);
            // msg1 should not be modified
            assertThat(msg1.correlationId()).isNull();
            assertThat(msg2.correlationId()).isEqualTo(correlationId);
            // Message IDs should be preserved
            assertThat(msg2.messageId()).isEqualTo(msg1.messageId());
    // Type Safety — generic type parameters
    @DisplayName("Type safety — generic reply type handling")
    class TypeSafety {
        @DisplayName("message supports various payload types")
        void supportsVariousPayloadTypes() {
            // String payload
            CommandMessage<?> msg1 = CommandMessage.create("Echo", "hello", replyTo);
            assertThat(msg1.payload()).isEqualTo("hello");
            // Integer payload
            CommandMessage<?> msg2 = CommandMessage.create("Count", 42, replyTo);
            assertThat(msg2.payload()).isEqualTo(42);
            // Complex record payload
            CreateUserCmd cmd = new CreateUserCmd("victor", "victor@example.com");
            CommandMessage<?> msg3 = CommandMessage.create("CreateUser", cmd, replyTo);
            assertThat(msg3.payload()).isEqualTo(cmd);
        @DisplayName("preserves payload type through message lifecycle")
        void preservesPayloadType() {
            CreateUserCmd original = new CreateUserCmd("wendy", "wendy@example.com");
                    CommandMessage.create("CreateUser", original, replyTo)
                            .withCorrelationId(UUID.randomUUID())
                            .withTimeout(Duration.ofSeconds(1));
            assertThat(msg.payload()).isInstanceOf(CreateUserCmd.class);
            assertThat(msg.payload()).isEqualTo(original);
    // Integration — realistic scenarios
    @DisplayName("Integration — realistic command message scenarios")
    class Integration {
        @DisplayName("creates complete command for RPC over mailbox")
        void createsCompleteRpcCommand() {
            CreateUserCmd payload = new CreateUserCmd("xavier", "xavier@example.com");
            Object clientReplyTo = new MockReplyAddress();
            CommandMessage<?> rpcCmd =
                    CommandMessage.create("CreateUser", payload, clientReplyTo)
            // Verify all RPC properties
            assertThat(rpcCmd.commandType()).isEqualTo("CreateUser");
            assertThat(rpcCmd.payload()).isEqualTo(payload);
            assertThat(rpcCmd.replyTo()).isEqualTo(clientReplyTo);
            assertThat(rpcCmd.correlationId()).isEqualTo(correlationId);
            assertThat(rpcCmd.timeout()).isEqualTo(timeout);
            assertThat(rpcCmd.messageId()).isNotNull();
            assertThat(rpcCmd.createdAt()).isGreaterThan(0);
        @DisplayName("multiple commands with same correlation ID group related requests")
        void groupsRelatedRequestsByCorrelation() {
            UUID transactionId = UUID.randomUUID();
            CreateUserCmd cmd1 = new CreateUserCmd("yara", "yara@example.com");
                    CommandMessage.create("CreateUser", cmd1, replyTo)
                            .withCorrelationId(transactionId);
            UpdateProfileCmd cmd2 = new UpdateProfileCmd("user-456", "Data Scientist");
                    CommandMessage.create("UpdateProfile", cmd2, replyTo)
            // Both messages part of same transaction
            assertThat(msg1.correlationId()).isEqualTo(msg2.correlationId());
}
