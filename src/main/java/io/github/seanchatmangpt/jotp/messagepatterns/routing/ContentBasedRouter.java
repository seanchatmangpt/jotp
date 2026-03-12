package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Content-Based Router pattern: inspects each message and routes to the appropriate destination
 * based on message content.
 *
 * <p>Enterprise Integration Pattern: <em>Content-Based Router</em> (EIP §8.1). Erlang analog:
 * pattern matching in {@code receive} clauses — the first matching clause handles the message.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * OrderRouter} pattern-matches on {@code order.orderType} to dispatch to different inventory
 * systems.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var router = ContentBasedRouter.<Order>builder()
 *     .when(o -> o.type().equals("TypeABC"), inventoryA::tell)
 *     .when(o -> o.type().equals("TypeXYZ"), inventoryX::tell)
 *     .otherwise(deadLetter::tell)
 *     .build();
 *
 * router.route(new Order("o1", "TypeABC", items));
 * }</pre>
 *
 * @param <T> message type
 */
public final class ContentBasedRouter<T> {

    private record Route<T>(Predicate<T> predicate, Consumer<T> destination) {}

    private final List<Route<T>> routes;
    private final Consumer<T> otherwise;

    private ContentBasedRouter(List<Route<T>> routes, Consumer<T> otherwise) {
        this.routes = List.copyOf(routes);
        this.otherwise = otherwise;
    }

    /**
     * Route a message to the first matching destination.
     *
     * @param message the message to route
     * @return true if a matching route was found
     */
    public boolean route(T message) {
        for (Route<T> route : routes) {
            if (route.predicate().test(message)) {
                route.destination().accept(message);
                return true;
            }
        }
        if (otherwise != null) {
            otherwise.accept(message);
            return true;
        }
        return false;
    }

    /** Returns a new builder. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent builder for ContentBasedRouter. */
    public static final class Builder<T> {
        private final List<Route<T>> routes = new ArrayList<>();
        private Consumer<T> otherwise;

        public Builder<T> when(Predicate<T> predicate, Consumer<T> destination) {
            routes.add(new Route<>(predicate, destination));
            return this;
        }

        public Builder<T> otherwise(Consumer<T> destination) {
            this.otherwise = destination;
            return this;
        }

        public ContentBasedRouter<T> build() {
            return new ContentBasedRouter<>(routes, otherwise);
        }
    }
}
