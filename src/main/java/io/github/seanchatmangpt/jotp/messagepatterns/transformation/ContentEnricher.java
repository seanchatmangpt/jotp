package io.github.seanchatmangpt.jotp.messagepatterns.transformation;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Content Enricher pattern: augments a message with additional data from an external resource.
 *
 * <p>Enterprise Integration Pattern: <em>Content Enricher</em> (EIP §7.3). The enricher queries an
 * external source (database, service, cache) and merges the result into the message.
 *
 * <p>Erlang analog: a process that receives a sparse message, queries an ETS table or external
 * service for enrichment data, and forwards the augmented message.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * AccountingEnricherDispatcher} enriches a {@code DoctorVisitCompleted} message with patient
 * details (lastName, SSN, carrier) from an external lookup.
 *
 * @param <T> original message type
 * @param <R> enrichment resource type
 * @param <U> enriched message type
 */
public final class ContentEnricher<T, R, U> {

    private final Function<T, R> resourceLookup;
    private final BiFunction<T, R, U> enrichFunction;
    private final Proc<Void, T> proc;

    /**
     * Creates a content enricher.
     *
     * @param resourceLookup function to fetch enrichment data
     * @param enrichFunction function to merge original message with enrichment data
     * @param destination receives the enriched message
     */
    public ContentEnricher(
            Function<T, R> resourceLookup,
            BiFunction<T, R, U> enrichFunction,
            Consumer<U> destination) {
        this.resourceLookup = resourceLookup;
        this.enrichFunction = enrichFunction;
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            R resource = resourceLookup.apply(msg);
                            U enriched = enrichFunction.apply(msg, resource);
                            destination.accept(enriched);
                            return state;
                        });
    }

    /** Enrich and forward a message. */
    public void enrich(T message) {
        proc.tell(message);
    }

    /** Apply the enrichment synchronously without sending. */
    public U apply(T message) {
        R resource = resourceLookup.apply(message);
        return enrichFunction.apply(message, resource);
    }

    /** Stop the enricher. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
