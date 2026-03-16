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

package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Data Type Channel - routes messages based on their runtime type.
 *
 * <p>This pattern implements type-safe message routing using sealed interfaces and pattern
 * matching. Messages are dispatched to handlers based on their concrete type at runtime.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var channel = DataTypeChannel.create();
 *
 * var commandHandler = new ProcRef<CommandMsg>(commandProc);
 * var eventHandler = new ProcRef<EventMsg>(eventProc);
 *
 * DataTypeChannel.addRoute(channel, CommandMsg.class, commandHandler);
 * DataTypeChannel.addRoute(channel, EventMsg.class, eventHandler);
 *
 * DataTypeChannel.dispatch(channel, myMessage);
 * }</pre>
 *
 * @param <M> the message type (must extend Message)
 */
public final class DataTypeChannel<M extends Message<?>> {

    private final Map<Class<?>, ProcRef<?>> routes = new ConcurrentHashMap<>();
    private final AtomicLong totalDispatched = new AtomicLong(0);
    private final Map<Class<?>, AtomicLong> dispatchCountByType = new ConcurrentHashMap<>();

    private DataTypeChannel() {
        // Use factory method create()
    }

    /**
     * Creates a new data type channel.
     *
     * @param <M> the message type
     * @return a new data type channel
     */
    public static <M extends Message<?>> DataTypeChannel<M> create() {
        return new DataTypeChannel<>();
    }

    /**
     * Adds a route for the given message type.
     *
     * @param messageType the message type class
     * @param handler the handler to receive messages of this type
     * @param <T> the specific message type
     */
    @SuppressWarnings("unchecked")
    public <T extends M> void addRoute(Class<T> messageType, ProcRef<T> handler) {
        routes.put(messageType, handler);
        dispatchCountByType.put(messageType, new AtomicLong(0));
    }

    /**
     * Dispatches a message to the appropriate handler based on its type.
     *
     * @param message the message to dispatch
     */
    @SuppressWarnings("unchecked")
    public void dispatch(M message) {
        totalDispatched.incrementAndGet();
        var handler = routes.get(message.getClass());
        if (handler != null) {
            ((ProcRef<M>) handler).tell(message);
            var counter = dispatchCountByType.get(message.getClass());
            if (counter != null) {
                counter.incrementAndGet();
            }
        }
    }

    /**
     * Returns the current state of the channel.
     *
     * @param channel the channel to get state from
     * @return the channel state snapshot
     */
    public static <M extends Message<?>> ChannelState getState(DataTypeChannel<M> channel) {
        var dispatchCounts = new ConcurrentHashMap<Class<?>, Long>();
        for (var entry : channel.dispatchCountByType.entrySet()) {
            dispatchCounts.put(entry.getKey(), entry.getValue().get());
        }
        return new ChannelState(
                new ArrayList<>(channel.routes.keySet()),
                channel.totalDispatched.get(),
                dispatchCounts);
    }

    /**
     * Channel state snapshot.
     *
     * @param registeredTypes list of registered message types
     * @param totalDispatched total messages dispatched
     * @param dispatchCountByType count of dispatches per type
     */
    public record ChannelState(
            List<Class<? extends Message<?>>> registeredTypes,
            long totalDispatched,
            Map<Class<?>, Long> dispatchCountByType) {

        /**
         * Checks if a route is registered for the given message type.
         *
         * @param messageType the message type to check
         * @return true if a route is registered, false otherwise
         */
        @SuppressWarnings("unchecked")
        public boolean hasRoute(Class<?> messageType) {
            return registeredTypes.contains(messageType);
        }

        /**
         * Returns the total number of messages dispatched.
         *
         * @return total dispatched count
         */
        public long getTotalDispatched() {
            return totalDispatched;
        }
    }

    /**
     * Returns the total number of messages dispatched.
     *
     * @return total dispatched count
     */
    public long getTotalDispatched() {
        return totalDispatched.get();
    }
}
