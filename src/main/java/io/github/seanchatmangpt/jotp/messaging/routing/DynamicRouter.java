package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Dynamic Router: routes messages to destinations determined at runtime via a registry lookup.
 *
 * <p>Enterprise Integration Pattern: <em>Dynamic Router</em> (EIP §7.5). The routing table is not
 * fixed at compile time — destinations are resolved dynamically from the {@link ProcRegistry}.
 *
 * <p>Optional per-destination handlers can be registered to perform pre-routing side effects (e.g.,
 * logging, tracing, metrics). Handlers are invoked synchronously before the message is routed.
 *
 * @param <M> message type
 */
public final class DynamicRouter<M> {

    private final Function<M, String> destinationResolver;
    private final Map<String, Consumer<M>> handlers = new ConcurrentHashMap<>();

    /**
     * Creates a dynamic router with the given destination resolver.
     *
     * @param destinationResolver function that maps a message to a destination name; may return
     *     {@code null} if no destination is applicable
     * @throws NullPointerException if {@code destinationResolver} is null
     */
    public DynamicRouter(Function<M, String> destinationResolver) {
        this.destinationResolver =
                Objects.requireNonNull(
                        destinationResolver, "destinationResolver cannot be null");
    }

    /**
     * Routes a message to the destination resolved at runtime.
     *
     * <p>The destination name is looked up in {@link ProcRegistry}. If found, any registered
     * handler is invoked first, then the message is delivered to the process.
     *
     * @param message the message to route
     * @return {@code true} if the message was successfully delivered; {@code false} if the
     *     destination was not found in the registry
     * @throws NullPointerException if {@code message} is null
     */
    @SuppressWarnings("unchecked")
    public boolean route(M message) {
        Objects.requireNonNull(message, "message cannot be null");
        String destination = destinationResolver.apply(message);
        if (destination == null) {
            return false;
        }
        Consumer<M> handler = handlers.get(destination);
        if (handler != null) {
            handler.accept(message);
        }
        var procOpt = ProcRegistry.whereis(destination);
        if (procOpt.isEmpty()) {
            return false;
        }
        ((io.github.seanchatmangpt.jotp.Proc<Object, M>) procOpt.get()).tell(message);
        return true;
    }

    /**
     * Registers a handler for a specific destination. The handler is invoked before message
     * delivery whenever a message is routed to that destination.
     *
     * @param destinationName the destination name to associate the handler with
     * @param handler the pre-routing handler
     * @throws NullPointerException if {@code destinationName} or {@code handler} is null
     */
    public void registerHandler(String destinationName, Consumer<M> handler) {
        Objects.requireNonNull(destinationName, "destinationName cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");
        handlers.put(destinationName, handler);
    }

    /**
     * Removes the handler for the given destination.
     *
     * @param destinationName the destination whose handler should be removed
     */
    public void unregisterHandler(String destinationName) {
        if (destinationName != null) {
            handlers.remove(destinationName);
        }
    }

    /** Returns the number of registered handlers. */
    public int handlerCount() {
        return handlers.size();
    }
}
