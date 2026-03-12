package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Splitter pattern: decomposes a composite message into individual parts and routes each part.
 *
 * <p>Enterprise Integration Pattern: <em>Splitter</em> (EIP §8.5). Erlang analog: a process that
 * decomposes a list-based message and sends each element individually — {@code [Worker ! Item ||
 * Item <- Items]}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * OrderRouter} iterates over {@code order.orderItems} and routes each item to a typed processor
 * based on item type.
 *
 * @param <T> composite message type
 * @param <P> individual part type
 */
public final class Splitter<T, P> {

    private final Function<T, List<P>> splitFunction;
    private final Consumer<P> partConsumer;

    /**
     * Creates a splitter.
     *
     * @param splitFunction function decomposing a composite message into parts
     * @param partConsumer consumer handling each individual part
     */
    public Splitter(Function<T, List<P>> splitFunction, Consumer<P> partConsumer) {
        this.splitFunction = splitFunction;
        this.partConsumer = partConsumer;
    }

    /**
     * Split a composite message and route each part.
     *
     * @param message the composite message
     * @return the number of parts generated
     */
    public int split(T message) {
        List<P> parts = splitFunction.apply(message);
        for (P part : parts) {
            partConsumer.accept(part);
        }
        return parts.size();
    }

    /**
     * Creates a splitter with a content-based part router.
     *
     * @param splitFunction function decomposing the message
     * @param router content-based router for dispatching parts
     * @param <T> composite type
     * @param <P> part type
     * @return a new splitter with routing
     */
    public static <T, P> Splitter<T, P> withRouter(
            Function<T, List<P>> splitFunction, ContentBasedRouter<P> router) {
        return new Splitter<>(splitFunction, router::route);
    }
}
