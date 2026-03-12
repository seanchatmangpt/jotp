package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import java.util.UUID;

/**
 * Correlation Identifier pattern: a unique ID linking request and reply messages.
 *
 * <p>Enterprise Integration Pattern: <em>Correlation Identifier</em> (EIP §6.4). Erlang analog:
 * tagged references created by {@code make_ref()} — each request-reply pair shares a unique
 * reference for matching.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var correlationId = CorrelationIdentifier.create();
 * var request = new PriceQuoteRequest(correlationId, "AAPL", 100);
 * // ... later, match reply by correlationId
 * var reply = new PriceQuoteReply(correlationId, 150.0);
 * assert correlationId.matches(reply.correlationId());
 * }</pre>
 *
 * @param id the unique correlation identifier
 */
public record CorrelationIdentifier(UUID id) {

    /** Creates a new unique correlation identifier. */
    public static CorrelationIdentifier create() {
        return new CorrelationIdentifier(UUID.randomUUID());
    }

    /**
     * Creates a correlation identifier from an existing UUID.
     *
     * @param id the existing UUID
     * @return a CorrelationIdentifier wrapping the given UUID
     */
    public static CorrelationIdentifier of(UUID id) {
        return new CorrelationIdentifier(id);
    }

    /**
     * Check if this identifier matches another.
     *
     * @param other the other identifier to compare
     * @return true if they share the same UUID
     */
    public boolean matches(CorrelationIdentifier other) {
        return this.id.equals(other.id);
    }

    @Override
    public String toString() {
        return "CorrelationId[" + id + "]";
    }
}
