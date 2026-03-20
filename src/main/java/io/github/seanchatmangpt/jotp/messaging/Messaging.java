/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.messaging;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.messagepatterns.construction.CorrelationIdentifier;
import io.github.seanchatmangpt.jotp.messagepatterns.management.SmartProxy;
import io.github.seanchatmangpt.jotp.messagepatterns.transformation.ContentFilter;
import io.github.seanchatmangpt.jotp.messaging.endpoints.MessagingGateway;
import io.github.seanchatmangpt.jotp.messaging.endpoints.MessagingMapper;
import io.github.seanchatmangpt.jotp.messaging.system.ChannelPurger;
import io.github.seanchatmangpt.jotp.messaging.transformation.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Factory class for creating messaging components in JOTP.
 *
 * <p>This class provides fluent factory methods for creating channels, routers, transformers, and
 * other messaging components.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * // Create a point-to-point channel
 * var channel = Messaging.<String>pointToPoint(msg -> System.out.println(msg));
 *
 * // Create a publish-subscribe channel
 * var pubSub = Messaging.<Integer>publishSubscribe();
 * pubSub.subscribe(msg -> System.out.println("Received: " + msg));
 *
 * // Create a content-based router
 * var router = Messaging.<String>contentBasedRouter()
 *     .route(msg -> msg.startsWith("A"), channelA)
 *     .route(msg -> msg.startsWith("B"), channelB)
 *     .build();
 * }</pre>
 */
public final class Messaging {

    private Messaging() {
        // Utility class - no instantiation
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // POINT-TO-POINT CHANNEL
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a point-to-point channel where exactly one consumer receives each message.
     *
     * @param <M> the message type
     * @param consumer the consumer to receive messages
     * @return a new point-to-point channel
     */
    public static <M> MessageChannel<M> pointToPoint(Consumer<M> consumer) {
        return new PointToPointChannelImpl<>(consumer);
    }

    /**
     * Creates a point-to-point channel without a consumer. Consumers can be added later via {@link
     * MessageChannel#subscribe(Consumer)}.
     *
     * @param <M> the message type
     * @return a new point-to-point channel
     */
    public static <M> MessageChannel<M> pointToPoint() {
        return new PointToPointChannelImpl<>(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PUBLISH-SUBSCRIBE CHANNEL
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a publish-subscribe channel where all subscribers receive every message.
     *
     * @param <M> the message type
     * @return a new publish-subscribe channel
     */
    public static <M> PublishSubscribeChannel<M> publishSubscribe() {
        return new PublishSubscribeChannelImpl<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DATA TYPE CHANNEL
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a data type channel that routes messages based on their runtime type.
     *
     * @param <M> the message type (must extend Message)
     * @return a new data type channel
     */
    @SuppressWarnings("unchecked")
    public static <M extends Message<?>> DataTypeChannel<M> dataTypeChannel() {
        return (DataTypeChannel<M>) new DataTypeChannelImpl<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DEAD LETTER CHANNEL
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a dead letter channel for capturing undeliverable messages.
     *
     * @param <M> the message type
     * @return a new dead letter channel
     */
    public static <M> DeadLetterChannel<M> deadLetterChannel() {
        return new DeadLetterChannelImpl<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONTROL BUS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a control bus for system management and monitoring.
     *
     * @param <M> the message type
     * @return a new control bus
     */
    public static <M> io.github.seanchatmangpt.jotp.messaging.system.ControlBus<M> controlBus() {
        return new io.github.seanchatmangpt.jotp.messaging.system.ControlBus<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DETOUR
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a detour that conditionally routes messages to a detour channel.
     *
     * @param <M> the message type
     * @param condition the predicate to determine if a message should be detoured
     * @param detourChannel the channel for detoured messages
     * @param primaryChannel the primary channel for non-detoured messages
     * @return a new detour
     * @throws IllegalArgumentException if any argument is null
     */
    public static <M> io.github.seanchatmangpt.jotp.messaging.system.Detour<M> detour(
            Predicate<M> condition,
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> detourChannel,
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> primaryChannel) {
        if (condition == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        if (detourChannel == null) {
            throw new IllegalArgumentException("detour channel must not be null");
        }
        if (primaryChannel == null) {
            throw new IllegalArgumentException("primary channel must not be null");
        }
        // Wrap top-level MessageChannel as Messaging.MessageChannel for the system Detour
        Messaging.MessageChannel<M> dc = asInnerChannel(detourChannel);
        Messaging.MessageChannel<M> pc = asInnerChannel(primaryChannel);
        return new io.github.seanchatmangpt.jotp.messaging.system.Detour<>(condition, dc, pc);
    }

    /**
     * Adapts a top-level {@link io.github.seanchatmangpt.jotp.messaging.MessageChannel} to the
     * inner {@link Messaging.MessageChannel} type, or returns it directly if it already implements
     * the inner type.
     */
    @SuppressWarnings("unchecked")
    private static <M> Messaging.MessageChannel<M> asInnerChannel(
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> channel) {
        if (channel instanceof Messaging.MessageChannel<M> inner) {
            return inner;
        }
        // Wrap in a delegating adapter
        return new Messaging.MessageChannel<>() {
            @Override
            public int queueDepth() {
                return channel.queueDepth();
            }

            @Override
            public void send(M message) {
                channel.send(message);
            }

            @Override
            public void sendSync(M message) {
                channel.sendSync(message);
            }

            @Override
            public void subscribe(Consumer<M> consumer) {
                channel.subscribe(consumer);
            }

            @Override
            public void unsubscribe(Consumer<M> consumer) {
                channel.unsubscribe(consumer);
            }

            @Override
            public void stop() throws InterruptedException {
                channel.stop();
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CHANNEL PURGER
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a channel purger with a custom strategy and interval.
     *
     * @param <M> the message type
     * @param channel the channel to purge
     * @param strategy the purge strategy
     * @param interval the interval between purge runs
     * @return a new channel purger
     */
    public static <M> ChannelPurger<M> channelPurger(
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> channel,
            ChannelPurger.PurgeStrategy strategy,
            Duration interval) {
        return new ChannelPurger<>(strategy, interval);
    }

    /**
     * Creates an age-based channel purger that removes messages older than the specified age.
     *
     * @param <M> the message type
     * @param channel the channel to purge
     * @param maxAge the maximum age of messages to retain
     * @param interval the interval between purge runs
     * @return a new channel purger
     */
    public static <M> ChannelPurger<M> channelPurgerByAge(
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> channel,
            Duration maxAge,
            Duration interval) {
        return new ChannelPurger<>(ChannelPurger.PurgeStrategy.byAge(maxAge), interval);
    }

    /**
     * Creates a count-based channel purger that removes messages when count exceeds the threshold.
     *
     * @param <M> the message type
     * @param channel the channel to purge
     * @param maxCount the maximum number of messages to retain
     * @param interval the interval between purge runs
     * @return a new channel purger
     */
    public static <M> ChannelPurger<M> channelPurgerByCount(
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> channel,
            int maxCount,
            Duration interval) {
        return new ChannelPurger<>(ChannelPurger.PurgeStrategy.byCount(maxCount), interval);
    }

    /**
     * Creates a predicate-based channel purger that removes messages matching the predicate.
     *
     * @param <M> the message type
     * @param channel the channel to purge
     * @param predicate messages matching this predicate are purged
     * @param interval the interval between purge runs
     * @return a new channel purger
     */
    public static <M> ChannelPurger<M> channelPurgerByPredicate(
            io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> channel,
            Predicate<M> predicate,
            Duration interval) {
        return new ChannelPurger<>(ChannelPurger.PurgeStrategy.byPredicate(predicate), interval);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST MESSAGE
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a test message with the given type and fields.
     *
     * @param <M> the message type (used for type inference at call site)
     * @param type the message type name
     * @param fields the fields to populate in the test message
     * @return a new test message
     */
    public static <M> TestMessage testMessage(String type, Map<String, Object> fields) {
        return new TestMessage(type, new LinkedHashMap<>(fields));
    }

    /**
     * Creates an empty test message with the given type.
     *
     * @param <M> the message type (used for type inference at call site)
     * @param type the message type name
     * @return a new test message with no fields
     */
    public static <M> TestMessage testMessage(String type) {
        return new TestMessage(type, Map.of());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CANONICAL MODEL (NORMALIZER)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a normalizer that converts between different message formats and a canonical model.
     *
     * @return a new normalizer for canonical model transformations
     */
    public static Normalizer<String, Message<?>> canonicalModel() {
        return new Normalizer<>(raw -> Message.event("CANONICAL", raw));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SMART PROXY
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a smart proxy that tracks request-reply exchanges using correlation identifiers.
     *
     * @param <REQ> the request type
     * @param <REP> the reply type
     * @param requestIdExtractor extracts correlation ID from requests
     * @param replyIdExtractor extracts correlation ID from replies
     * @param serviceProvider the downstream service consumer
     * @return a new smart proxy
     */
    public static <REQ, REP> SmartProxy<REQ, REP> smartProxy(
            Function<REQ, CorrelationIdentifier> requestIdExtractor,
            Function<REP, CorrelationIdentifier> replyIdExtractor,
            Consumer<REQ> serviceProvider) {
        return new SmartProxy<>(requestIdExtractor, replyIdExtractor, serviceProvider);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MESSAGING GATEWAY
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a request-reply messaging gateway backed by a channel.
     *
     * @param <M> the message type
     * @param channel the channel to send requests to
     * @param timeout the timeout for synchronous request-reply
     * @return a new messaging gateway
     */
    public static <M> MessagingGateway<M, M> gateway(Channel<M> channel, Duration timeout) {
        return new MessagingGateway<>(channel, timeout);
    }

    /**
     * Creates a one-way (fire-and-forget) messaging gateway.
     *
     * @param <M> the message type
     * @param consumer the consumer to send messages to
     * @return a new messaging gateway
     */
    public static <M> MessagingGateway<M, Void> gateway(Consumer<M> consumer) {
        return new MessagingGateway<>(consumer);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MESSAGING MAPPER
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a messaging mapper that transforms messages and forwards them downstream.
     *
     * @param <From> the source message type
     * @param <To> the target message type
     * @param mapperFn the mapping function
     * @param downstream the channel to forward mapped messages to
     * @return a new messaging mapper
     */
    public static <From, To> MessagingMapper<From, To> mapper(
            Function<From, To> mapperFn, Channel<To> downstream) {
        Objects.requireNonNull(mapperFn, "mapper function must not be null");
        Objects.requireNonNull(downstream, "downstream channel must not be null");
        return new MessagingMapper<>(mapperFn, downstream);
    }

    /**
     * Creates a messaging mapper with an error channel for handling mapping failures.
     *
     * @param <From> the source message type
     * @param <To> the target message type
     * @param mapperFn the mapping function
     * @param downstream the channel to forward mapped messages to
     * @param errorChannel the channel to route unmappable messages to
     * @return a new messaging mapper
     */
    public static <From, To> MessagingMapper<From, To> mapper(
            Function<From, To> mapperFn, Channel<To> downstream, Channel<From> errorChannel) {
        Objects.requireNonNull(mapperFn, "mapper function must not be null");
        Objects.requireNonNull(downstream, "downstream channel must not be null");
        return new MessagingMapper<>(mapperFn, downstream, errorChannel);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONTENT FILTER
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a content filter that strips unnecessary fields from messages.
     *
     * @param <A> the unfiltered (large) message type
     * @param <B> the filtered (lean) message type
     * @param filterFn the function that extracts essential fields
     * @param destination the consumer to receive filtered messages
     * @return a new content filter
     */
    public static <A, B> ContentFilter<A, B> contentFilter(
            Function<A, B> filterFn, Consumer<B> destination) {
        return new ContentFilter<>(filterFn, destination);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INNER INTERFACES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Basic message channel interface.
     *
     * @see io.github.seanchatmangpt.jotp.messaging.MessageChannel
     */
    public interface MessageChannel<M>
            extends io.github.seanchatmangpt.jotp.messaging.MessageChannel<M> {}

    /** Publish-subscribe channel interface. */
    public interface PublishSubscribeChannel<M> extends Channel<M> {
        /** Returns the number of subscribers. */
        int subscriberCount();
    }

    /** Data type channel interface. */
    public interface DataTypeChannel<M extends Message<?>> extends Channel<M> {
        /**
         * Adds a route for the given message type.
         *
         * @param messageType the message type class
         * @param handler the handler to receive messages of this type
         */
        <T extends M> void addRoute(
                Class<T> messageType, io.github.seanchatmangpt.jotp.ProcRef<?, T> handler);

        /**
         * Dispatches a message to the appropriate handler based on its type.
         *
         * @param message the message to dispatch
         */
        void dispatch(M message);

        /** Returns the current state of the channel. */
        ChannelState getState();

        /** Channel state snapshot. */
        record ChannelState(
                List<Class<? extends Message<?>>> registeredTypes,
                long totalDispatched,
                Map<Class<?>, Long> dispatchCountByType) {
            public boolean hasRoute(Class<?> messageType) {
                return registeredTypes.contains(messageType);
            }
        }
    }

    /** Dead letter channel interface. */
    public interface DeadLetterChannel<M> extends Channel<M> {
        /** Returns the number of undelivered messages. */
        int size();

        /** Drains all undelivered messages from the channel. */
        List<DeadLetter<M>> drain();

        /** Sends a message with a specific failure reason. */
        void send(M message, String reason);

        /** Dead letter with reason. */
        record DeadLetter<M>(M message, String reason, Instant timestamp) {
            public DeadLetter {
                if (timestamp == null) timestamp = Instant.now();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IMPLEMENTATION CLASSES
    // ═══════════════════════════════════════════════════════════════════════════════

    private static final class PointToPointChannelImpl<M> implements MessageChannel<M> {
        private final Proc<Void, M> proc;

        PointToPointChannelImpl(Consumer<M> consumer) {
            this.proc =
                    new Proc<>(
                            null,
                            (state, msg) -> {
                                if (consumer != null) {
                                    consumer.accept(msg);
                                }
                                return state;
                            });
        }

        @Override
        public void send(M message) {
            proc.tell(message);
        }

        @Override
        public void sendSync(M message) {
            proc.tell(message);
        }

        @Override
        public void subscribe(Consumer<M> consumer) {
            // Point-to-point channels have a single consumer
            throw new UnsupportedOperationException(
                    "Point-to-point channels have a fixed consumer");
        }

        @Override
        public void unsubscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException(
                    "Cannot unsubscribe from point-to-point channels");
        }

        @Override
        public int queueDepth() {
            return proc.mailboxSize();
        }

        @Override
        public void stop() throws InterruptedException {
            proc.stop();
        }
    }

    private static final class PublishSubscribeChannelImpl<M>
            implements PublishSubscribeChannel<M> {
        private final List<Consumer<M>> subscribers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Proc<Void, M> proc;

        PublishSubscribeChannelImpl() {
            this.proc =
                    new Proc<>(
                            null,
                            (state, msg) -> {
                                for (var subscriber : subscribers) {
                                    try {
                                        subscriber.accept(msg);
                                    } catch (Exception e) {
                                        // Crashing subscribers are removed (OTP fault isolation)
                                        subscribers.remove(subscriber);
                                    }
                                }
                                return state;
                            });
        }

        @Override
        public void send(M message) {
            proc.tell(message);
        }

        @Override
        public void sendSync(M message) {
            send(message);
            // Small delay to ensure delivery
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void subscribe(Consumer<M> consumer) {
            subscribers.add(consumer);
        }

        @Override
        public void unsubscribe(Consumer<M> consumer) {
            subscribers.remove(consumer);
        }

        @Override
        public int subscriberCount() {
            return subscribers.size();
        }

        @Override
        public void stop() throws InterruptedException {
            proc.stop();
        }
    }

    private static final class DataTypeChannelImpl<M extends Message<?>>
            implements DataTypeChannel<M> {

        private final Map<Class<?>, io.github.seanchatmangpt.jotp.ProcRef<?, ?>> routes =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong totalDispatched =
                new java.util.concurrent.atomic.AtomicLong(0);

        @Override
        public <T extends M> void addRoute(
                Class<T> messageType, io.github.seanchatmangpt.jotp.ProcRef<?, T> handler) {
            routes.put(messageType, handler);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void dispatch(M message) {
            totalDispatched.incrementAndGet();
            var handler = routes.get(message.getClass());
            if (handler != null) {
                ((io.github.seanchatmangpt.jotp.ProcRef<?, M>) handler).tell(message);
            }
        }

        @Override
        public void send(M message) {
            dispatch(message);
        }

        @Override
        public void sendSync(M message) {
            dispatch(message);
        }

        @Override
        public void subscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException("Use addRoute() for type-based routing");
        }

        @Override
        public void unsubscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException("Cannot unsubscribe from type-based routing");
        }

        @Override
        public void stop() throws InterruptedException {
            // No resources to release
        }

        @Override
        @SuppressWarnings("unchecked")
        public ChannelState getState() {
            var dispatchCounts = new java.util.HashMap<Class<?>, Long>();
            for (var type : routes.keySet()) {
                dispatchCounts.put(type, totalDispatched.get());
            }
            var registeredTypes =
                    new java.util.ArrayList<>(
                            (java.util.Collection<Class<? extends Message<?>>>)
                                    (java.util.Collection<?>) routes.keySet());
            return new ChannelState(registeredTypes, totalDispatched.get(), dispatchCounts);
        }
    }

    private static final class DeadLetterChannelImpl<M> implements DeadLetterChannel<M> {
        private final java.util.concurrent.ConcurrentLinkedQueue<DeadLetter<M>> messages =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        @Override
        public void send(M message) {
            messages.offer(new DeadLetter<>(message, "undeliverable", null));
        }

        @Override
        public void sendSync(M message) {
            send(message);
        }

        @Override
        public void send(M message, String reason) {
            messages.offer(new DeadLetter<>(message, reason, null));
        }

        @Override
        public void subscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException("Dead letter channels don't have subscribers");
        }

        @Override
        public void unsubscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException("Dead letter channels don't have subscribers");
        }

        @Override
        public int size() {
            return messages.size();
        }

        @Override
        public List<DeadLetter<M>> drain() {
            var result = new java.util.ArrayList<DeadLetter<M>>();
            DeadLetter<M> msg;
            while ((msg = messages.poll()) != null) {
                result.add(msg);
            }
            return result;
        }

        @Override
        public void stop() throws InterruptedException {
            messages.clear();
        }
    }
}
