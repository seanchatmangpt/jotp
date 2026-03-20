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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
    public static <M> ControlBus<M> controlBus() {
        return new ControlBusImpl<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DETOUR
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a detour that conditionally routes messages to a detour channel.
     *
     * @param <M> the message type
     * @param predicate the predicate to determine if a message should be detoured
     * @param detourChannel the channel for detoured messages
     * @param primaryChannel the primary channel for non-detoured messages
     * @return a new detour
     */
    public static <M> Detour<M> detour(
            Predicate<M> predicate,
            MessageChannel<M> detourChannel,
            MessageChannel<M> primaryChannel) {
        return new DetourImpl<>(predicate, detourChannel, primaryChannel);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INNER IMPLEMENTATIONS
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

    /** Control bus interface. */
    public interface ControlBus<M> {
        /** Registers a process with the control bus. */
        void register(String name, Proc<?, M> process);

        /** Unregisters a process from the control bus. */
        void unregister(String name);

        /** Lists all registered process names. */
        List<String> listProcesses();

        /** Returns statistics for all registered processes. */
        Map<String, ProcessStats> getStats();

        /** Process statistics. */
        record ProcessStats(String name, long messagesProcessed, double avgProcessingTime) {}
    }

    /** Detour interface. */
    public interface Detour<M> extends Channel<M> {
        /** Returns the number of messages detoured. */
        long detourCount();

        /** Returns the number of messages sent to primary. */
        long primaryCount();
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
            var registeredTypes = new java.util.ArrayList<>(
                    (java.util.Collection<Class<? extends Message<?>>>) (java.util.Collection<?>) routes.keySet());
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

    private static final class ControlBusImpl<M> implements ControlBus<M> {
        private final Map<String, Proc<?, M>> processes =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void register(String name, Proc<?, M> process) {
            processes.put(name, process);
        }

        @Override
        public void unregister(String name) {
            processes.remove(name);
        }

        @Override
        public List<String> listProcesses() {
            return List.copyOf(processes.keySet());
        }

        @Override
        public Map<String, ProcessStats> getStats() {
            var stats = new java.util.HashMap<String, ProcessStats>();
            for (var entry : processes.entrySet()) {
                stats.put(entry.getKey(), new ProcessStats(entry.getKey(), 0, 0.0));
            }
            return stats;
        }
    }

    private static final class DetourImpl<M> implements Detour<M> {
        private final Predicate<M> predicate;
        private final MessageChannel<M> detourChannel;
        private final MessageChannel<M> primaryChannel;
        private final java.util.concurrent.atomic.AtomicLong detourCount =
                new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong primaryCount =
                new java.util.concurrent.atomic.AtomicLong(0);

        DetourImpl(
                Predicate<M> predicate,
                MessageChannel<M> detourChannel,
                MessageChannel<M> primaryChannel) {
            this.predicate = predicate;
            this.detourChannel = detourChannel;
            this.primaryChannel = primaryChannel;
        }

        @Override
        public void send(M message) {
            if (predicate.test(message)) {
                detourCount.incrementAndGet();
                detourChannel.send(message);
            } else {
                primaryCount.incrementAndGet();
                primaryChannel.send(message);
            }
        }

        @Override
        public void sendSync(M message) {
            send(message);
        }

        @Override
        public void subscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException("Detour channels don't have subscribers");
        }

        @Override
        public void unsubscribe(Consumer<M> consumer) {
            throw new UnsupportedOperationException("Detour channels don't have subscribers");
        }

        @Override
        public long detourCount() {
            return detourCount.get();
        }

        @Override
        public long primaryCount() {
            return primaryCount.get();
        }

        @Override
        public void stop() throws InterruptedException {
            detourChannel.stop();
            primaryChannel.stop();
        }
    }
}
