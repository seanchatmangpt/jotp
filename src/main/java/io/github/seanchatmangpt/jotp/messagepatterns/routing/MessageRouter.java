package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Message Router pattern: a simple router with deterministic routing logic.
 *
 * <p>Enterprise Integration Pattern: <em>Message Router</em> (EIP §8.1). A basic routing component
 * that directs messages to destinations based on routing logic (round-robin, alternating, etc.).
 *
 * <p>Erlang analog: a {@code gen_server} maintaining an internal counter or routing state, selecting
 * the next destination deterministically.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * AlternatingRouter} alternates between two processors.
 *
 * @param <T> message type
 */
public final class MessageRouter<T> {

    private final Consumer<T>[] destinations;
    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Creates a round-robin message router.
     *
     * @param destinations the destinations to alternate between
     */
    @SafeVarargs
    public MessageRouter(Consumer<T>... destinations) {
        if (destinations.length == 0) {
            throw new IllegalArgumentException("At least one destination required");
        }
        this.destinations = destinations.clone();
    }

    /**
     * Route a message to the next destination in round-robin order.
     *
     * @param message the message to route
     */
    public void route(T message) {
        int index = Math.floorMod(counter.getAndIncrement(), destinations.length);
        destinations[index].accept(message);
    }

    /** Returns the number of destinations. */
    public int destinationCount() {
        return destinations.length;
    }
}
