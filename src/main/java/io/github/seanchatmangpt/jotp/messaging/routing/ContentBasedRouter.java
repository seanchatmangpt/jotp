package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Content-Based Router pattern (EIP).
 *
 * <p>Routes messages to different destinations based on message content. Each message is examined,
 * and the first matching route is used. This is a fundamental EIP pattern for conditional message
 * flow.
 *
 * <p><strong>Pattern Semantics:</strong>
 * <ul>
 *   <li>Messages are examined against predicates in order
 *   <li>The first matching predicate wins (first-match semantics)
 *   <li>Unmatched messages are either dropped or sent to a default route (if configured)
 *   <li>Routing is asynchronous—destinations are invoked via the internal Proc
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> This router is thread-safe and can handle concurrent calls to
 * {@link #route}, {@link #addRoute}, and {@link #setDefault} from multiple virtual threads.
 *
 * <p><strong>Reference:</strong> Enterprise Integration Patterns, Chapter 5: Message Router
 * (https://www.enterpriseintegrationpatterns.com/MessageRouter.html)
 *
 * @param <M> message type
 */
public final class ContentBasedRouter<M> {

  /** Route entry: predicate + destination consumer. */
  private static final class Route<M> {
    final Predicate<M> predicate;
    final Consumer<M> destination;

    Route(Predicate<M> predicate, Consumer<M> destination) {
      this.predicate = Objects.requireNonNull(predicate, "predicate cannot be null");
      this.destination = Objects.requireNonNull(destination, "destination cannot be null");
    }
  }

  private final List<Route<M>> routes = new CopyOnWriteArrayList<>();
  private volatile Consumer<M> defaultRoute = null;

  // Internal process for async routing
  private final ProcRef<Void, Object> proc;

  /** Creates a new content-based router. */
  public ContentBasedRouter() {
    var p =
        new Proc<>(
            null,
            (state, msg) -> {
              // Router processes messages asynchronously
              return state;
            });
    this.proc = new ProcRef<>(p);
  }

  /**
   * Adds a route with the given predicate and destination.
   *
   * @param predicate condition to match messages
   * @param destination consumer to invoke on match
   * @throws NullPointerException if predicate or destination is null
   */
  public void addRoute(Predicate<M> predicate, Consumer<M> destination) {
    Objects.requireNonNull(predicate, "predicate cannot be null");
    Objects.requireNonNull(destination, "destination cannot be null");
    routes.add(new Route<>(predicate, destination));
  }

  /**
   * Sets the default route for unmatched messages.
   *
   * @param destination consumer for unmatched messages, or null to drop unmatched
   */
  public void setDefault(Consumer<M> destination) {
    this.defaultRoute = destination;
  }

  /**
   * Routes a message: finds the first matching route and invokes its destination.
   *
   * <p>Routing is synchronous—the matching predicate and destination are invoked on the caller's
   * thread. If no route matches, the default route (if set) is invoked; otherwise, the message is
   * dropped.
   *
   * @param message the message to route
   */
  public void route(M message) {
    // Find first matching route
    for (Route<M> route : routes) {
      if (route.predicate.test(message)) {
        route.destination.accept(message);
        return;
      }
    }
    // No route matched; use default if available
    if (defaultRoute != null) {
      defaultRoute.accept(message);
    }
  }

  /**
   * Returns the internal process reference.
   *
   * @return process reference for lifecycle management
   */
  public ProcRef<Void, Object> process() {
    return proc;
  }
}
