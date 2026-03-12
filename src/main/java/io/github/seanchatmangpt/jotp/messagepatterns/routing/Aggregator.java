package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Aggregator pattern: collects correlated messages until a completion condition is met, then emits
 * an aggregated result.
 *
 * <p>Enterprise Integration Pattern: <em>Aggregator</em> (EIP §8.6). Erlang analog: a {@code
 * gen_server} accumulating partial results by correlation ID, emitting a combined result when all
 * parts arrive.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * PriceQuoteAggregator} collects price quotes by RFQ ID and emits a {@code QuotationFulfillment}
 * when the expected number of quotes is reached.
 *
 * @param <T> individual message type
 * @param <K> correlation key type
 * @param <R> aggregated result type
 */
public final class Aggregator<T, K, R> {

    /** Aggregation state for a single correlation group. */
    record AggregationState<T>(int expectedCount, List<T> parts) {
        AggregationState<T> withPart(T part) {
            var updated = new ArrayList<>(parts);
            updated.add(part);
            return new AggregationState<>(expectedCount, updated);
        }

        boolean isComplete() {
            return parts.size() >= expectedCount;
        }
    }

    private final Function<T, K> correlationKeyExtractor;
    private final Function<List<T>, R> aggregateFunction;
    private final Consumer<R> resultConsumer;
    private final Proc<Map<K, AggregationState<T>>, Object> proc;

    /**
     * Creates an aggregator.
     *
     * @param correlationKeyExtractor extracts the correlation key from a message
     * @param aggregateFunction combines collected parts into a result
     * @param resultConsumer receives the aggregated result
     */
    @SuppressWarnings("unchecked")
    public Aggregator(
            Function<T, K> correlationKeyExtractor,
            Function<List<T>, R> aggregateFunction,
            Consumer<R> resultConsumer) {
        this.correlationKeyExtractor = correlationKeyExtractor;
        this.aggregateFunction = aggregateFunction;
        this.resultConsumer = resultConsumer;

        this.proc = new Proc<>(new HashMap<>(), (state, msg) -> {
            if (msg instanceof Aggregator.ExpectParts<?> expect) {
                var updated = new HashMap<>(state);
                updated.put(
                        (K) expect.correlationKey(),
                        new AggregationState<>(expect.expectedCount(), new ArrayList<>()));
                return updated;
            }
            T part = (T) msg;
            K key = correlationKeyExtractor.apply(part);
            var group = state.get(key);
            if (group == null) return state;

            var updated = new HashMap<>(state);
            var newGroup = group.withPart(part);
            if (newGroup.isComplete()) {
                updated.remove(key);
                resultConsumer.accept(aggregateFunction.apply(newGroup.parts()));
            } else {
                updated.put(key, newGroup);
            }
            return updated;
        });
    }

    /** Declare expected number of parts for a correlation group. */
    public record ExpectParts<K>(K correlationKey, int expectedCount) {}

    /** Set the expected number of parts for a correlation key. */
    public void expect(K correlationKey, int expectedCount) {
        proc.tell(new ExpectParts<>(correlationKey, expectedCount));
    }

    /** Add a part to the aggregation. */
    @SuppressWarnings("unchecked")
    public void addPart(T part) {
        proc.tell(part);
    }

    /** Stop the aggregator. */
    public void stop() {
        proc.stop();
    }
}
