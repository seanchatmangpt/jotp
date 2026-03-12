package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Message Metadata pattern: a message carries an audit trail of processing entries.
 *
 * <p>Enterprise Integration Pattern: <em>Message History</em> / <em>Message Metadata</em> (EIP
 * §11.2). Each processor in a pipeline appends a metadata entry documenting who processed the
 * message, what happened, and why.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * SomeMessage} carries a {@code Metadata(entries: List[Entry])} where each entry has
 * who/what/where/ when/why fields.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var metadata = MessageMetadata.empty()
 *     .addEntry("OrderService", "validated", "OrderRouter", "business rule check")
 *     .addEntry("InventoryService", "reserved", "StockManager", "stock allocated");
 *
 * record ProcessableOrder(String orderId, MessageMetadata metadata) {}
 * }</pre>
 *
 * @param entries the ordered list of metadata entries (audit trail)
 */
public record MessageMetadata(List<Entry> entries) {

    /** A single metadata entry in the message's audit trail. */
    public record Entry(String who, String what, String where, Instant when, String why) {

        /** Creates an entry with the current timestamp. */
        public static Entry of(String who, String what, String where, String why) {
            return new Entry(who, what, where, Instant.now(), why);
        }
    }

    /** Canonical constructor ensuring immutability. */
    public MessageMetadata {
        entries = List.copyOf(entries);
    }

    /** Creates an empty metadata container. */
    public static MessageMetadata empty() {
        return new MessageMetadata(List.of());
    }

    /**
     * Appends a new entry to the audit trail.
     *
     * @param who the processor identity
     * @param what what action was taken
     * @param where the processing location/actor
     * @param why the reason for the action
     * @return a new MessageMetadata with the entry appended
     */
    public MessageMetadata addEntry(String who, String what, String where, String why) {
        var updated = new ArrayList<>(entries);
        updated.add(Entry.of(who, what, where, why));
        return new MessageMetadata(Collections.unmodifiableList(updated));
    }

    /** Returns the number of entries in the audit trail. */
    public int size() {
        return entries.size();
    }

    /** Returns the most recent entry, or null if empty. */
    public Entry lastEntry() {
        return entries.isEmpty() ? null : entries.getLast();
    }
}
