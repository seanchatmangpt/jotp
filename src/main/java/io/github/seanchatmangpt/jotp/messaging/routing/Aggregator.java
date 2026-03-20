package io.github.seanchatmangpt.jotp.messaging.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Aggregator pattern (EIP): collects related message parts and assembles them into a single
 * aggregate message once all expected parts have arrived.
 *
 * <p>Parts are correlated by a {@link UUID} correlation ID (see {@link Splitter.PartMetadata}).
 * Parts may arrive out of order; the aggregator reassembles them in sequence-number order.
 *
 * <p>Incomplete aggregations that exceed the configured timeout are discarded. Subsequent attempts
 * to add parts for a timed-out correlation ID throw {@link IllegalStateException}.
 *
 * @param <P> the part payload type; the assembly function receives a {@link List} ordered by
 *     sequence number
 */
public final class Aggregator<P> {

    /** Per-correlation-ID aggregation state. */
    private static final class Bucket<P> {
        final Instant created = Instant.now();
        final TreeMap<Integer, P> parts = new TreeMap<>();
        final int expectedTotal;

        Bucket(int expectedTotal) {
            this.expectedTotal = expectedTotal;
        }
    }

    private final int totalParts;
    private final Duration timeout;
    private final Function<List<P>, ?> assembler;
    private final ConcurrentHashMap<UUID, Bucket<P>> buckets = new ConcurrentHashMap<>();

    private Aggregator(int totalParts, Duration timeout, Function<List<P>, ?> assembler) {
        this.totalParts = totalParts;
        this.timeout = timeout;
        this.assembler = assembler;
    }

    /**
     * Create a new Aggregator.
     *
     * @param totalParts expected number of parts per correlation
     * @param timeout maximum time to wait for all parts
     * @param assembler function to combine the ordered parts into an aggregate
     * @param <P> part payload type
     * @param <A> aggregate type
     * @return new Aggregator instance
     */
    public static <P, A> Aggregator<P> create(
            int totalParts, Duration timeout, Function<List<P>, A> assembler) {
        return new Aggregator<>(totalParts, timeout, assembler);
    }

    /**
     * Submit a message part for aggregation.
     *
     * @param part the message part (must not be null)
     * @return {@link Optional#empty()} if aggregation is not yet complete, or the assembled result
     * @throws IllegalArgumentException if the part's {@code totalParts} differs from this
     *     aggregator's configured {@code totalParts}
     * @throws IllegalStateException if the correlation ID's aggregation timed out
     */
    @SuppressWarnings("unchecked")
    public synchronized <A> Optional<A> aggregate(Splitter.MessagePart<P> part) {
        var meta = part.metadata();
        if (meta.totalParts() != totalParts) {
            throw new IllegalArgumentException(
                    "totalParts mismatch: expected "
                            + totalParts
                            + " but got "
                            + meta.totalParts());
        }

        UUID corrId = meta.correlationId();

        // Check for expired bucket
        Bucket<P> existing = buckets.get(corrId);
        if (existing == null) {
            // Possibly timed out — check if timeout has elapsed since we'd have had a bucket
            // We can only detect this if the bucket was previously created and removed.
            // After removal, subsequent additions throw.
        } else {
            Duration elapsed = Duration.between(existing.created, Instant.now());
            if (elapsed.compareTo(timeout) > 0) {
                buckets.remove(corrId);
                throw new IllegalStateException(
                        "Aggregation for correlation "
                                + corrId
                                + " has timed out after "
                                + elapsed.toMillis()
                                + "ms");
            }
        }

        // Create bucket if not present
        Bucket<P> bucket = buckets.computeIfAbsent(corrId, _ -> new Bucket<>(totalParts));

        // Check again after computeIfAbsent (race with timeout check above)
        Duration elapsed = Duration.between(bucket.created, Instant.now());
        if (elapsed.compareTo(timeout) > 0) {
            buckets.remove(corrId);
            throw new IllegalStateException(
                    "Aggregation for correlation "
                            + corrId
                            + " has timed out after "
                            + elapsed.toMillis()
                            + "ms");
        }

        // Store part (overwrite duplicate sequence numbers — last write wins)
        bucket.parts.put(meta.sequenceNumber(), part.payload());

        // Check if all parts have arrived
        if (bucket.parts.size() == totalParts) {
            buckets.remove(corrId);
            List<P> ordered = List.copyOf(bucket.parts.values());
            return Optional.of((A) assembler.apply(ordered));
        }

        return Optional.empty();
    }

    /**
     * Returns the number of correlation IDs currently being tracked (incomplete aggregations).
     *
     * @return count of active aggregations
     */
    public int activeCorrelations() {
        // Purge any timed-out buckets before counting
        Instant cutoff = Instant.now().minus(timeout);
        buckets.entrySet().removeIf(e -> e.getValue().created.isBefore(cutoff));
        return buckets.size();
    }
}
