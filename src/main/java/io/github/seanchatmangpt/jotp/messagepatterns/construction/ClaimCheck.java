package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claim Check pattern: store a large message payload externally, pass a lightweight token through
 * the pipeline, and retrieve the payload when needed.
 *
 * <p>Enterprise Integration Pattern: <em>Claim Check</em> (EIP §7.3). Prevents large messages from
 * consuming channel bandwidth — only a small claim token travels through the pipeline.
 *
 * <p>Erlang analog: ETS table ({@code ets:insert/2}, {@code ets:lookup/2}) acting as shared storage
 * keyed by a reference ({@code make_ref()}).
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, an
 * {@code ItemChecker} stores {@code CheckedItem} entries keyed by {@code ClaimCheck} UUIDs. Each
 * pipeline step claims only the part it needs.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var store = new ClaimCheck<CompositePayload>();
 * CheckToken token = store.checkIn(new CompositePayload(part1, part2, part3));
 *
 * // Pass only the token through the pipeline
 * pipeline.send(new ProcessStep(token));
 *
 * // Later, retrieve the payload
 * CompositePayload payload = store.checkOut(token).orElseThrow();
 * }</pre>
 *
 * @param <T> the type of item being stored
 */
public final class ClaimCheck<T> {

    /** Opaque claim token referencing stored data. */
    public record CheckToken(UUID id) {
        /** Creates a new unique claim token. */
        public static CheckToken create() {
            return new CheckToken(UUID.randomUUID());
        }
    }

    private final ConcurrentHashMap<UUID, T> store = new ConcurrentHashMap<>();

    /**
     * Store an item and return a claim token.
     *
     * @param item the item to store
     * @return a claim token for later retrieval
     */
    public CheckToken checkIn(T item) {
        var token = CheckToken.create();
        store.put(token.id(), item);
        return token;
    }

    /**
     * Retrieve a stored item by its claim token without removing it.
     *
     * @param token the claim token
     * @return the stored item, or empty if not found
     */
    public Optional<T> claim(CheckToken token) {
        return Optional.ofNullable(store.get(token.id()));
    }

    /**
     * Retrieve and remove a stored item by its claim token.
     *
     * @param token the claim token
     * @return the stored item, or empty if not found
     */
    public Optional<T> checkOut(CheckToken token) {
        return Optional.ofNullable(store.remove(token.id()));
    }

    /** Returns the number of items currently stored. */
    public int size() {
        return store.size();
    }
}
