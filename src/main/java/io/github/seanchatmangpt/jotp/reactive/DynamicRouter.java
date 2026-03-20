package io.github.seanchatmangpt.jotp.reactive;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Dynamic Router: routes messages to channels based on runtime-configurable predicate rules.
 *
 * <p>Enterprise Integration Pattern: <em>Dynamic Router</em> (EIP SS7.5). The routing table can be
 * updated at runtime without restart — routes can be added, prepended (for priority override), or
 * removed while messages continue to flow.
 *
 * <p>When no route matches, messages are sent to the dead-letter channel (if configured) or silently
 * dropped.
 *
 * @param <T> message type
 */
public final class DynamicRouter<T> implements MessageChannel<T> {

    private record Route<T>(Predicate<T> predicate, MessageChannel<T> channel) {}

    private final CopyOnWriteArrayList<Route<T>> routes = new CopyOnWriteArrayList<>();
    private final DeadLetterChannel<T> deadLetter;

    /** Creates a dynamic router with no dead-letter channel (unmatched messages are dropped). */
    public DynamicRouter() {
        this.deadLetter = null;
    }

    /**
     * Creates a dynamic router that sends unmatched messages to the given dead-letter channel.
     *
     * @param deadLetter channel for unroutable messages
     */
    public DynamicRouter(DeadLetterChannel<T> deadLetter) {
        this.deadLetter = deadLetter;
    }

    /**
     * Appends a routing rule. The first matching predicate wins (in insertion order).
     *
     * @param predicate condition for routing
     * @param channel target channel when predicate matches
     */
    public void addRoute(Predicate<T> predicate, MessageChannel<T> channel) {
        routes.add(new Route<>(predicate, channel));
    }

    /**
     * Prepends a routing rule at the front of the table, giving it highest priority.
     *
     * @param predicate condition for routing
     * @param channel target channel when predicate matches
     */
    public void prependRoute(Predicate<T> predicate, MessageChannel<T> channel) {
        routes.add(0, new Route<>(predicate, channel));
    }

    /**
     * Removes all routes that target the given channel.
     *
     * @param channel the channel whose routes should be removed
     */
    public void removeRoutesTo(MessageChannel<T> channel) {
        routes.removeIf(route -> route.channel() == channel);
    }

    /** Returns the number of active routing rules. */
    public int routeCount() {
        return routes.size();
    }

    @Override
    public void send(T message) {
        for (Route<T> route : routes) {
            if (route.predicate().test(message)) {
                route.channel().send(message);
                return;
            }
        }
        if (deadLetter != null) {
            deadLetter.send(message);
        }
    }
}
