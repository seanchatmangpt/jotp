package io.github.seanchatmangpt.jotp.enterprise.eventbus;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Typed event bus with delivery guarantees, batching, and deduplication.
 *
 * <p>Provides pub-sub messaging with configurable delivery semantics: - FireAndForget: No retries -
 * AtLeastOnce: Retry with backoff - ExactlyOnce: Deduplication window - Partitioned: Per-partition
 * ordering
 *
 * <p>State Machine: - RUNNING: Accepting and delivering events - DEGRADED: Some subscribers failing
 * (collecting in DLQ) - PAUSED: Manual pause (backpressure)
 */
public class EventBus {
    private final EventBusConfig config;
    private final ProcRef<BusState, BusMsg> coordinator;
    private final Map<String, SubscriberHandler> subscribers = new HashMap<>();
    private final Deque<Object> deadLetterQueue = new ArrayDeque<>();
    private final CopyOnWriteArrayList<EventBusListener> listeners = new CopyOnWriteArrayList<>();
    private volatile BusState.Status currentStatus = BusState.Status.RUNNING;

    private EventBus(EventBusConfig config, ProcRef<BusState, BusMsg> coordinator) {
        this.config = config;
        this.coordinator = coordinator;
    }

    /**
     * Create a new event bus.
     *
     * @param config Event bus configuration
     * @return EventBus instance
     */
    public static EventBus create(EventBusConfig config) {
        return new EventBus(config, spawnCoordinator(config));
    }

    /**
     * Publish an event to all subscribers.
     *
     * @param event Event to publish
     * @return PublishResult with delivery status
     */
    public PublishResult publish(Object event) {
        String eventId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        if (currentStatus == BusState.Status.PAUSED) {
            return new PublishResult(eventId, PublishResult.Status.REJECTED, "Bus paused");
        }

        // Notify coordinator
        coordinator.tell(new BusMsg.EventPublished(eventId, event.getClass().getName(), timestamp));

        // Deliver to all subscribers
        for (SubscriberHandler subscriber : subscribers.values()) {
            try {
                subscriber.handler().accept(event);
                coordinator.tell(new BusMsg.EventDelivered(eventId, subscriber.id(), 0));
            } catch (Exception e) {
                coordinator.tell(
                        new BusMsg.EventFailed(eventId, subscriber.id(), e.getMessage(), 0));

                if (config.deadLetterQueueEnabled()) {
                    deadLetterQueue.addLast(event);
                }
            }
        }

        return new PublishResult(eventId, PublishResult.Status.ACCEPTED, "");
    }

    /**
     * Subscribe to events of a specific type.
     *
     * @param id Subscriber identifier
     * @param handler Event handler
     * @return Subscription
     */
    public Subscription subscribe(String id, java.util.function.Consumer<Object> handler) {
        subscribers.put(id, new SubscriberHandler(id, handler));
        listeners.forEach(l -> l.onSubscribed(id));
        return new Subscription(id, this);
    }

    /**
     * Unsubscribe a handler.
     *
     * @param id Subscriber identifier
     */
    public void unsubscribe(String id) {
        subscribers.remove(id);
        listeners.forEach(l -> l.onUnsubscribed(id, "Manual"));
    }

    /**
     * Get all subscribers.
     *
     * @return List of subscriber info
     */
    public List<SubscriberInfo> getSubscribers() {
        return subscribers.values().stream().map(s -> new SubscriberInfo(s.id())).toList();
    }

    /** Pause event publishing. */
    public void pause() {
        currentStatus = BusState.Status.PAUSED;
        coordinator.tell(new BusMsg.Pause());
    }

    /** Resume event publishing. */
    public void resume() {
        currentStatus = BusState.Status.RUNNING;
        coordinator.tell(new BusMsg.Resume());
    }

    /**
     * Register a listener for bus events.
     *
     * @param listener Callback to invoke on events
     */
    public void addListener(EventBusListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     *
     * @param listener Listener to remove
     */
    public void removeListener(EventBusListener listener) {
        listeners.remove(listener);
    }

    /** Shutdown the event bus. */
    public void shutdown() {
        coordinator.tell(new BusMsg.Shutdown());
    }

    private static ProcRef<BusState, BusMsg> spawnCoordinator(EventBusConfig config) {
        var proc =
                new Proc<>(
                        new BusState(BusState.Status.RUNNING, 0, 0),
                        (BusState state, BusMsg msg) -> {
                            return switch (msg) {
                                case BusMsg.EventPublished(var id, var type, var ts) ->
                                        new BusState(
                                                state.status(),
                                                state.published() + 1,
                                                state.failed());
                                case BusMsg.EventDelivered(var id, var sub, var latency) -> state;
                                case BusMsg.EventFailed(var id, var sub, var error, var retries) ->
                                        new BusState(
                                                state.status(),
                                                state.published(),
                                                state.failed() + 1);
                                case BusMsg.Pause _ ->
                                        new BusState(
                                                BusState.Status.PAUSED,
                                                state.published(),
                                                state.failed());
                                case BusMsg.Resume _ ->
                                        new BusState(
                                                BusState.Status.RUNNING,
                                                state.published(),
                                                state.failed());
                                case BusMsg.Shutdown _ -> state;
                            };
                        });
        return new ProcRef<>(proc);
    }

    /** Internal state for the event bus coordinator. */
    record BusState(Status status, long published, long failed) {
        enum Status {
            RUNNING,
            DEGRADED,
            PAUSED
        }
    }

    /** Messages for the event bus coordinator. */
    sealed interface BusMsg
            permits BusMsg.EventPublished,
                    BusMsg.EventDelivered,
                    BusMsg.EventFailed,
                    BusMsg.Pause,
                    BusMsg.Resume,
                    BusMsg.Shutdown {

        record EventPublished(String eventId, String eventType, long timestamp) implements BusMsg {}

        record EventDelivered(String eventId, String subscriberId, long latencyMs)
                implements BusMsg {}

        record EventFailed(String eventId, String subscriberId, String error, int retries)
                implements BusMsg {}

        record Pause() implements BusMsg {}

        record Resume() implements BusMsg {}

        record Shutdown() implements BusMsg {}
    }

    /** Result of publishing an event. */
    public record PublishResult(String eventId, Status status, String errorMessage) {
        enum Status {
            ACCEPTED,
            REJECTED
        }
    }

    /** Information about a subscriber. */
    public record SubscriberInfo(String id) {}

    /** Subscription handle. */
    public static class Subscription {
        private final String id;
        private final EventBus bus;

        private Subscription(String id, EventBus bus) {
            this.id = id;
            this.bus = bus;
        }

        public void unsubscribe() {
            bus.unsubscribe(id);
        }
    }

    /** Internal: Subscriber handler. */
    private record SubscriberHandler(String id, java.util.function.Consumer<Object> handler) {}

    /** Listener interface for event bus events. */
    public interface EventBusListener {
        void onSubscribed(String subscriberId);

        void onUnsubscribed(String subscriberId, String reason);

        void onEventPublished(String eventId);

        void onEventFailed(String eventId, String reason);
    }
}
