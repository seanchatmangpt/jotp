package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Content-Based Router pattern (EIP): examines message content and routes each message to the
 * first matching destination.
 *
 * <p>Routes are tested in registration order; the first route whose predicate returns {@code true}
 * handles the message (first-match semantics). Unmatched messages are forwarded to the optional
 * default handler; if no default is set, they are silently dropped.
 *
 * <p>This implementation is thread-safe. New routes may be added at any time via {@link
 * #addRoute(Predicate, Predicate)}. The underlying {@link Proc} processes one message at a time on
 * a virtual thread.
 *
 * @param <M> the message type
 */
public final class ContentBasedRouter<M> {

    /**
     * A route entry: the {@code predicate} acts as both the routing test <em>and</em> the handler.
     * If the predicate returns {@code true} the message is considered handled.
     */
    private record Route<M>(Predicate<M> predicate) {}

    private final CopyOnWriteArrayList<Route<M>> routes = new CopyOnWriteArrayList<>();
    private volatile Consumer<M> defaultHandler = null;

    /** The backing virtual-thread process that serialises message dispatch. */
    private final Proc<Void, M> proc;

    /** Creates a new content-based router. */
    public ContentBasedRouter() {
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            for (Route<M> route : routes) {
                                if (route.predicate().test(msg)) {
                                    return state;
                                }
                            }
                            Consumer<M> def = defaultHandler;
                            if (def != null) {
                                def.accept(msg);
                            }
                            return state;
                        });
    }

    /**
     * Register a route. The predicate is called for each message and acts as both the routing test
     * and the handler: if it returns {@code true}, the message is considered routed (delivered).
     *
     * @param predicate the routing predicate + handler (must not be null)
     * @param destination ignored — included for API clarity; may be null
     * @throws IllegalArgumentException if predicate is null
     */
    public void addRoute(Predicate<M> predicate, Predicate<M> destination) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        routes.add(new Route<>(predicate));
    }

    /**
     * Register a route using only a predicate (predicate both tests and handles the message).
     *
     * @param predicate the routing predicate + handler (must not be null)
     * @throws IllegalArgumentException if predicate is null
     */
    public void addRoute(Predicate<M> predicate) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        routes.add(new Route<>(predicate));
    }

    /**
     * Set or clear the default handler for unmatched messages.
     *
     * @param handler the default consumer, or {@code null} to drop unmatched messages
     */
    public void setDefault(Consumer<M> handler) {
        this.defaultHandler = handler;
    }

    /**
     * Route a message asynchronously via the backing {@link Proc}.
     *
     * @param message the message to route
     */
    public void route(M message) {
        proc.tell(message);
    }

    /**
     * Returns the backing {@link Proc} for lifecycle management (e.g., stopping).
     *
     * @return the backing process
     */
    public Proc<Void, M> process() {
        return proc;
    }
}
