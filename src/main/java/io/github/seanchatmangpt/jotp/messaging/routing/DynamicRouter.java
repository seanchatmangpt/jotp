package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Dynamic Router pattern (EIP).
 *
 * <p>Routes messages to destinations determined at runtime. Unlike content-based routing, the
 * destination is resolved dynamically based on message content or external configuration. The
 * router integrates with {@link ProcRegistry} to look up destination processes by name.
 *
 * <p><strong>Pattern Semantics:</strong>
 * <ul>
 *   <li>A resolver function determines the destination name at runtime
 *   <li>The destination is looked up in the registry
 *   <li>If found, the message is sent to that destination
 *   <li>If not found, routing fails (returns false)
 *   <li>Optional handlers can be pre-registered for destinations
 * </ul>
 *
 * <p><strong>Use Cases:</strong>
 * <ul>
 *   <li>Service discovery routing (destination determined by message content)
 *   <li>Tenant-specific routing (multi-tenancy)
 *   <li>Dynamic flow control (destinations can be added/removed at runtime)
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Thread-safe; can handle concurrent routing and handler
 * registration from multiple virtual threads.
 *
 * <p><strong>Reference:</strong> Enterprise Integration Patterns, Chapter 5: Dynamic Router
 * (https://www.enterpriseintegrationpatterns.com/DynamicRouter.html)
 *
 * @param <M> message type
 */
public final class DynamicRouter<M> {

  private final Function<M, String> destinationResolver;
  private final ConcurrentHashMap<String, Consumer<M>> handlers = new ConcurrentHashMap<>();

  /**
   * Creates a dynamic router with the given destination resolver.
   *
   * @param destinationResolver function that determines the destination name from a message
   * @throws NullPointerException if resolver is null
   */
  public DynamicRouter(Function<M, String> destinationResolver) {
    this.destinationResolver =
        Objects.requireNonNull(destinationResolver, "destinationResolver cannot be null");
  }

  /**
   * Routes a message: resolves the destination and attempts to deliver.
   *
   * <p>Steps:
   * <ol>
   *   <li>Call resolver to get destination name
   *   <li>Look up handler in registry (if registered)
   *   <li>Invoke handler if present
   *   <li>Look up ProcRef in ProcRegistry
   *   <li>Send message to process if found
   *   <li>Return false if destination not found
   * </ol>
   *
   * @param message the message to route
   * @return true if successfully routed; false if destination not found
   * @throws NullPointerException if message is null
   */
  public boolean route(M message) {
    Objects.requireNonNull(message, "message cannot be null");

    // Resolve destination name
    String destinationName = destinationResolver.apply(message);

    // Invoke pre-registered handler if present
    Consumer<M> handler = handlers.get(destinationName);
    if (handler != null) {
      handler.accept(message);
    }

    // Look up destination in ProcRegistry and send message
    ProcRef<?, M> dest = ProcRegistry.lookup(destinationName);
    if (dest != null) {
      dest.tell(message);
      return true;
    }

    return false;
  }

  /**
   * Registers a handler for the given destination name.
   *
   * <p>Handlers are invoked before attempting ProcRegistry lookup. This allows pre-processing or
   * side effects (logging, metrics) before message delivery.
   *
   * @param destinationName the destination name
   * @param handler the handler consumer
   * @throws NullPointerException if destinationName or handler is null
   */
  public void registerHandler(String destinationName, Consumer<M> handler) {
    Objects.requireNonNull(destinationName, "destinationName cannot be null");
    Objects.requireNonNull(handler, "handler cannot be null");
    handlers.put(destinationName, handler);
  }

  /**
   * Unregisters the handler for the given destination name.
   *
   * @param destinationName the destination name
   */
  public void unregisterHandler(String destinationName) {
    Objects.requireNonNull(destinationName, "destinationName cannot be null");
    handlers.remove(destinationName);
  }

  /**
   * Returns the number of registered handlers.
   *
   * @return handler count
   */
  public int handlerCount() {
    return handlers.size();
  }
}
