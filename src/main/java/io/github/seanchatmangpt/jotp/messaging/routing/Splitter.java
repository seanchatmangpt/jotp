package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Splitter pattern (EIP): breaks a composite message into multiple parts for independent
 * processing.
 *
 * <p>Each part receives metadata identifying its position (sequence number) within the whole, plus
 * a shared correlation ID so an {@link Aggregator} can reassemble the parts later.
 */
public final class Splitter {

    private Splitter() {}

    /**
     * Metadata carried with each split part.
     *
     * @param correlationId shared ID linking all parts from the same split operation
     * @param sequenceNumber 1-based position of this part
     * @param totalParts total number of parts produced by the split
     */
    public record PartMetadata(UUID correlationId, int sequenceNumber, int totalParts) {
        public PartMetadata {
            Objects.requireNonNull(correlationId, "correlationId");
            if (sequenceNumber < 1 || sequenceNumber > totalParts) {
                throw new IllegalArgumentException(
                        "sequenceNumber must be 1-indexed and <= totalParts, got "
                                + sequenceNumber
                                + "/"
                                + totalParts);
            }
        }
    }

    /**
     * A single part of a split message.
     *
     * @param <P> payload type of the part
     */
    public record MessagePart<P>(P payload, PartMetadata metadata) {
        /**
         * Returns a human-readable description of this part.
         *
         * @return description string containing sequence and correlation info
         */
        public String describe() {
            return "Part "
                    + metadata.sequenceNumber()
                    + "/"
                    + metadata.totalParts()
                    + " [correlation="
                    + metadata.correlationId()
                    + "]";
        }
    }

    /**
     * Split {@code message} into parts using the provided {@code splitterFn}.
     *
     * @param <M> original message type
     * @param <P> part payload type
     * @param message the message to split (must not be null)
     * @param splitterFn function that extracts the list of parts from the message
     * @return unmodifiable list of {@link MessagePart}s with auto-generated correlation ID
     * @throws IllegalArgumentException if the splitter function returns an empty list
     */
    public static <M, P> List<MessagePart<P>> split(M message, Function<M, List<P>> splitterFn) {
        return split(message, splitterFn, UUID.randomUUID());
    }

    /**
     * Split {@code message} into parts using the provided {@code splitterFn} and a specific
     * correlation ID.
     *
     * @param <M> original message type
     * @param <P> part payload type
     * @param message the message to split (must not be null)
     * @param splitterFn function that extracts the list of parts from the message
     * @param correlationId the correlation ID to use for all parts
     * @return unmodifiable list of {@link MessagePart}s
     * @throws IllegalArgumentException if the splitter function returns an empty list
     */
    public static <M, P> List<MessagePart<P>> split(
            M message, Function<M, List<P>> splitterFn, UUID correlationId) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(splitterFn, "splitterFn must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");

        List<P> items = splitterFn.apply(message);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Splitter function must return a non-empty list of parts");
        }

        int total = items.size();
        List<MessagePart<P>> parts = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            var meta = new PartMetadata(correlationId, i + 1, total);
            parts.add(new MessagePart<>(items.get(i), meta));
        }
        return Collections.unmodifiableList(parts);
    }

    /**
     * Partition a collection-bearing message into fixed-size chunks.
     *
     * @param <M> message type
     * @param <I> item type in the collection
     * @param message the source message
     * @param itemsExtractor extracts the item list from the message
     * @param chunkSize maximum number of items per chunk
     * @param chunkFactory creates a new message from a chunk of items
     * @return unmodifiable list of {@link MessagePart}s, one per chunk
     */
    public static <M, I> List<MessagePart<M>> partition(
            M message,
            Function<M, List<I>> itemsExtractor,
            int chunkSize,
            Function<List<I>, M> chunkFactory) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(itemsExtractor, "itemsExtractor must not be null");
        Objects.requireNonNull(chunkFactory, "chunkFactory must not be null");
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be >= 1");
        }

        List<I> items = itemsExtractor.apply(message);
        List<List<I>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunkSize) {
            chunks.add(items.subList(i, Math.min(i + chunkSize, items.size())));
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Partition must produce a non-empty list of chunks");
        }

        UUID correlationId = UUID.randomUUID();
        int total = chunks.size();
        List<MessagePart<M>> parts = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            M chunk = chunkFactory.apply(Collections.unmodifiableList(new ArrayList<>(chunks.get(i))));
            var meta = new PartMetadata(correlationId, i + 1, total);
            parts.add(new MessagePart<>(chunk, meta));
        }
        return Collections.unmodifiableList(parts);
    }
}
