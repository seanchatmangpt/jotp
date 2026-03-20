package io.github.seanchatmangpt.jotp.reactive;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Content Enricher: augments messages with data from an external resource before forwarding.
 *
 * <p>Enterprise Integration Pattern: <em>Content Enricher</em> (EIP SS8.3). The enricher applies a
 * transformation function to each incoming message, optionally using an external resource (database,
 * cache, service), and forwards the enriched result to a downstream channel.
 *
 * <p>Three factory methods support different enrichment styles:
 *
 * <ul>
 *   <li>{@link #of(Object, BiFunction, MessageChannel)} — enrichment with an external resource
 *   <li>{@link #of(BiFunction, MessageChannel, DeadLetterChannel)} — enrichment with error routing
 *   <li>{@link #of(Function, MessageChannel)} — simple mapping without external resource
 * </ul>
 *
 * @param <I> input message type
 */
public final class ContentEnricher<I> implements MessageChannel<I> {

    private final Function<I, ?> enrichFn;
    private final MessageChannel<Object> downstream;
    private final DeadLetterChannel<I> errorChannel;

    @SuppressWarnings("unchecked")
    private <O> ContentEnricher(
            Function<I, O> enrichFn,
            MessageChannel<O> downstream,
            DeadLetterChannel<I> errorChannel) {
        this.enrichFn = enrichFn;
        this.downstream = (MessageChannel<Object>) downstream;
        this.errorChannel = errorChannel;
    }

    /**
     * Creates an enricher that uses an external resource to augment each message.
     *
     * @param resource the external resource (database, cache, etc.)
     * @param enrichFn function that takes (message, resource) and produces the enriched output
     * @param downstream channel to receive enriched messages
     * @param <I> input message type
     * @param <O> output (enriched) message type
     * @param <R> resource type
     * @return a new content enricher
     */
    public static <I, O, R> ContentEnricher<I> of(
            R resource, BiFunction<I, R, O> enrichFn, MessageChannel<O> downstream) {
        return new ContentEnricher<>(msg -> enrichFn.apply(msg, resource), downstream, null);
    }

    /**
     * Creates an enricher with error routing. Failed enrichments send the original message to the
     * error channel instead of crashing.
     *
     * @param enrichFn function that takes (message, Void) and produces the enriched output
     * @param downstream channel to receive enriched messages
     * @param errorChannel channel to receive messages that failed enrichment
     * @param <I> input message type
     * @param <O> output (enriched) message type
     * @return a new content enricher with error handling
     */
    public static <I, O> ContentEnricher<I> of(
            BiFunction<I, Void, O> enrichFn,
            MessageChannel<O> downstream,
            DeadLetterChannel<I> errorChannel) {
        return new ContentEnricher<>(msg -> enrichFn.apply(msg, null), downstream, errorChannel);
    }

    /**
     * Creates a simple mapping enricher that requires no external resource.
     *
     * @param mapFn function that transforms the input message
     * @param downstream channel to receive transformed messages
     * @param <I> input message type
     * @param <O> output message type
     * @return a new content enricher
     */
    public static <I, O> ContentEnricher<I> of(
            Function<I, O> mapFn, MessageChannel<O> downstream) {
        return new ContentEnricher<>(mapFn, downstream, null);
    }

    @Override
    public void send(I message) {
        try {
            Object enriched = enrichFn.apply(message);
            downstream.send(enriched);
        } catch (Exception e) {
            if (errorChannel != null) {
                errorChannel.send(message);
            }
        }
    }
}
