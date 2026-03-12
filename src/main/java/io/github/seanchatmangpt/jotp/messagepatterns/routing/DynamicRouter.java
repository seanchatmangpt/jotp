package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Dynamic Router pattern: a router whose routing table can be modified at runtime.
 *
 * <p>Enterprise Integration Pattern: <em>Dynamic Router</em> (EIP §8.3). Consumers register and
 * unregister interest dynamically — the routing table is hot-reconfigurable while messages flow.
 *
 * <p>Erlang analog: a {@code gen_server} maintaining a dynamic dispatch table updated by {@code
 * register/unregister} casts.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * TypedMessageInterestRouter} maintains primary and secondary interest registries using reflection
 * to match message types.
 *
 * @param <T> message type
 */
public final class DynamicRouter<T> {

    private record Interest<T>(String id, Predicate<T> predicate, Consumer<T> consumer) {}

    private final CopyOnWriteArrayList<Interest<T>> interests = new CopyOnWriteArrayList<>();
    private volatile Consumer<T> undeliverable;

    /**
     * Register interest in messages matching the predicate.
     *
     * @param id unique interest identifier
     * @param predicate message matching condition
     * @param consumer destination for matched messages
     */
    public void registerInterest(String id, Predicate<T> predicate, Consumer<T> consumer) {
        interests.add(new Interest<>(id, predicate, consumer));
    }

    /**
     * Remove a previously registered interest by ID.
     *
     * @param id the interest identifier
     * @return true if the interest was found and removed
     */
    public boolean removeInterest(String id) {
        return interests.removeIf(i -> i.id().equals(id));
    }

    /** Set a handler for messages that match no registered interest. */
    public void onUndeliverable(Consumer<T> handler) {
        this.undeliverable = handler;
    }

    /**
     * Route a message to the first matching interest.
     *
     * @param message the message to route
     * @return true if a matching interest was found
     */
    public boolean route(T message) {
        for (Interest<T> interest : interests) {
            if (interest.predicate().test(message)) {
                interest.consumer().accept(message);
                return true;
            }
        }
        if (undeliverable != null) {
            undeliverable.accept(message);
        }
        return false;
    }

    /** Returns the number of registered interests. */
    public int interestCount() {
        return interests.size();
    }
}
