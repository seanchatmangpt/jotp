package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import java.time.Instant;

/**
 * Event Message pattern: a message that notifies subscribers that something happened.
 *
 * <p>Enterprise Integration Pattern: <em>Event Message</em> (EIP §6.3). Unlike a {@link
 * CommandMessage} (imperative) or {@link DocumentMessage} (data transfer), an Event Message is a
 * <em>notification</em> of a fact that already occurred.
 *
 * <p>Erlang analog: events published via {@code gen_event:notify/2} — the event manager broadcasts
 * to all registered handlers.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * sealed interface DomainEvent extends EventMessage permits
 *     OrderPlaced, OrderShipped {}
 *
 * record OrderPlaced(String orderId, Instant occurredOn) implements DomainEvent {}
 * record OrderShipped(String orderId, String trackingId, Instant occurredOn)
 *     implements DomainEvent {}
 * }</pre>
 */
public interface EventMessage {

    /** When the event occurred. */
    Instant occurredOn();
}
