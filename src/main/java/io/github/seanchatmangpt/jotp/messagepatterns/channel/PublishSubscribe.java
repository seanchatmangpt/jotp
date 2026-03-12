package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import io.github.seanchatmangpt.jotp.EventManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Publish-Subscribe Channel pattern: every subscriber receives every message.
 *
 * <p>Enterprise Integration Pattern: <em>Publish-Subscribe Channel</em> (EIP §6.2). Erlang analog:
 * {@code gen_event} — one event manager process, arbitrarily many handlers.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, Akka's
 * {@code EventBus} with {@code SubchannelClassification} enables hierarchical topic-based pub-sub
 * (e.g., "quotes/NYSE", "quotes/NASDAQ").
 *
 * <p>In JOTP, this is backed by {@link EventManager} — handler crashes are isolated and do not
 * propagate to other subscribers or the channel itself.
 *
 * @param <T> event/message type
 */
public final class PublishSubscribe<T> {

    private final EventManager<T> eventManager = EventManager.start();
    private final ConcurrentHashMap<Consumer<T>, EventManager.Handler<T>> registry =
            new ConcurrentHashMap<>();

    /** Register a subscriber to receive all messages. */
    public void subscribe(Consumer<T> subscriber) {
        EventManager.Handler<T> handler = subscriber::accept;
        registry.put(subscriber, handler);
        eventManager.addHandler(handler);
    }

    /** Unsubscribe a previously registered subscriber. */
    public boolean unsubscribe(Consumer<T> subscriber) {
        EventManager.Handler<T> handler = registry.remove(subscriber);
        if (handler == null) return false;
        return eventManager.deleteHandler(handler);
    }

    /** Broadcast a message to all subscribers (async). */
    public void publish(T message) {
        eventManager.notify(message);
    }

    /** Broadcast a message and block until all subscribers have processed it. */
    public void publishSync(T message) throws InterruptedException {
        eventManager.syncNotify(message);
    }

    /** Stop the channel and all subscribers. */
    public void stop() throws InterruptedException {
        eventManager.stop();
    }
}
