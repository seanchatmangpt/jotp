package io.github.seanchatmangpt.jotp.messaging.construction;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Command Message: a message that instructs the receiver to perform an action.
 *
 * <p>Enterprise Integration Pattern: <em>Command Message</em> (EIP §6.3). Carries a command type,
 * payload, and reply-to address for request-reply workflows.
 *
 * <p>Instances are immutable. Use {@link #withCorrelationId} and {@link #withTimeout} to create
 * enriched copies.
 *
 * @param <P> the payload type
 */
public final class CommandMessage<P> {

    private final UUID messageId;
    private final String commandType;
    private final P payload;
    private final Object replyTo;
    private final UUID correlationId;
    private final Duration timeout;
    private final long createdAt;

    private CommandMessage(
            UUID messageId,
            String commandType,
            P payload,
            Object replyTo,
            UUID correlationId,
            Duration timeout,
            long createdAt) {
        this.messageId = messageId;
        this.commandType = commandType;
        this.payload = payload;
        this.replyTo = replyTo;
        this.correlationId = correlationId;
        this.timeout = timeout;
        this.createdAt = createdAt;
    }

    /**
     * Creates a new command message.
     *
     * @param commandType the command name (must not be null or blank)
     * @param payload the command payload (must not be null)
     * @param replyTo the reply-to address (must not be null)
     * @param <P> the payload type
     * @return a new command message
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if commandType is blank
     */
    public static <P> CommandMessage<P> create(String commandType, P payload, Object replyTo) {
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(replyTo, "replyTo must not be null");
        if (commandType.isBlank()) {
            throw new IllegalArgumentException("commandType must not be blank");
        }
        return new CommandMessage<>(
                UUID.randomUUID(),
                commandType,
                payload,
                replyTo,
                null,
                null,
                System.currentTimeMillis());
    }

    /**
     * Returns a new command message with the given correlation ID.
     *
     * @param correlationId the correlation ID to set
     * @return a new immutable command message
     */
    public CommandMessage<P> withCorrelationId(UUID correlationId) {
        return new CommandMessage<>(
                messageId, commandType, payload, replyTo, correlationId, timeout, createdAt);
    }

    /**
     * Returns a new command message with the given timeout.
     *
     * @param timeout the timeout duration (must be positive)
     * @return a new immutable command message
     * @throws NullPointerException if timeout is null
     * @throws IllegalArgumentException if timeout is not positive
     */
    public CommandMessage<P> withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return new CommandMessage<>(
                messageId, commandType, payload, replyTo, correlationId, timeout, createdAt);
    }

    /** Returns the unique message ID. */
    public UUID messageId() {
        return messageId;
    }

    /** Returns the command type name. */
    public String commandType() {
        return commandType;
    }

    /** Returns the command payload. */
    public P payload() {
        return payload;
    }

    /** Returns the reply-to address. */
    public Object replyTo() {
        return replyTo;
    }

    /** Returns the correlation ID, or {@code null} if not set. */
    public UUID correlationId() {
        return correlationId;
    }

    /** Returns {@code true} if a correlation ID has been set. */
    public boolean hasCorrelationId() {
        return correlationId != null;
    }

    /** Returns the timeout, or {@code null} if not set. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns {@code true} if a timeout has been set. */
    public boolean hasTimeout() {
        return timeout != null;
    }

    /** Returns the creation timestamp in epoch milliseconds. */
    public long createdAt() {
        return createdAt;
    }
}
