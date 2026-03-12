package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publish-Subscribe Channel (Vernon: "Publish-Subscribe Channel")
 *
 * <p>A messaging channel that broadcasts each message to all subscribers. Perfect for 1:N event
 * distribution where decoupling is critical.
 *
 * <p>JOTP Implementation: Uses {@code EventManager<Message>} to manage typed event subscribers.
 * Each subscriber receives a copy of the published message.
 *
 * <p>Example:
 *
 * <pre>
 * // Create a pub-sub channel
 * var channel = PublishSubscribeChannel.create();
 *
 * // Subscribe (multiple subscribers welcome)
 * channel.subscribe(event -> System.out.println("Subscriber 1: " + event));
 * channel.subscribe(event -> System.out.println("Subscriber 2: " + event));
 *
 * // Publish (all subscribers notified)
 * channel.publish(Message.event("OrderCreated", order));
 * </pre>
 */
public final class PublishSubscribeChannel<E extends Message> {

    private final EventManager<E> eventManager;
    private final List<EventManager.Handler<E>> handlers;

    /** Creates a new Publish-Subscribe channel. */
    private PublishSubscribeChannel() {
        this.eventManager = EventManager.start();
        this.handlers = new CopyOnWriteArrayList<>();
    }

    /**
     * Factory method to create a new pub-sub channel.
     *
     * @param <E> Message type
     * @return New PublishSubscribeChannel instance
     */
    public static <E extends Message> PublishSubscribeChannel<E> create() {
        return new PublishSubscribeChannel<>();
    }

    /**
     * Publishes a message to all active subscribers (1:N broadcast). Non-blocking; subscribers are
     * notified asynchronously.
     *
     * @param message The message to publish
     */
    public void publish(E message) {
        eventManager.notify(message);
    }

    /**
     * Publishes a message and waits for all subscribers to acknowledge (blocking).
     *
     * @param message The message to publish
     * @return Number of subscribers notified
     */
    public int publishSync(E message) {
        try {
            eventManager.syncNotify(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return handlers.size();
    }

    /**
     * Subscribes a handler to this channel. Handler will be called for every published message.
     *
     * @param handler Handler to invoke on each message
     * @return Handler reference (use for unsubscribe)
     */
    public EventManager.Handler<E> subscribe(EventManager.Handler<E> handler) {
        eventManager.addHandler(handler);
        handlers.add(handler);
        return handler;
    }

    /**
     * Unsubscribes a handler from this channel.
     *
     * @param handler Handler reference returned from subscribe()
     */
    public void unsubscribe(EventManager.Handler<E> handler) {
        eventManager.deleteHandler(handler);
        handlers.remove(handler);
    }

    /**
     * Returns the current number of active subscribers.
     *
     * @return Subscriber count
     */
    public int subscriberCount() {
        return handlers.size();
    }

    /** Unsubscribes all handlers from this channel. */
    public void unsubscribeAll() {
        handlers.forEach(eventManager::deleteHandler);
        handlers.clear();
    }

    /** Operations contract for Publish-Subscribe channels. */
    public interface PubSubChannelOps<E extends Message> {
        void publish(E message);

        int publishSync(E message);

        EventManager.Handler<E> subscribe(EventManager.Handler<E> handler);

        void unsubscribe(EventManager.Handler<E> handler);

        int subscriberCount();

        void unsubscribeAll();
    }
}
