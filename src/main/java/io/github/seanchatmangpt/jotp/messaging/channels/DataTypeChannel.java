package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Data Type Channel (Vernon: "Data Type Channel")
 *
 * <p>Strongly-typed message routing using sealed message hierarchies. Routes incoming messages to
 * type-specific handlers based on pattern matching against Java sealed interface variants.
 */
public final class DataTypeChannel {

    private DataTypeChannel() {}

    /** State container for a DataTypeChannel router. */
    public static final class ChannelState {
        private final Map<Class<?>, ProcRef<?, Message>> routes;
        private final Map<Class<?>, Integer> dispatchCounts;
        private int totalDispatched = 0;

        private ChannelState(Map<Class<?>, ProcRef<?, Message>> routes) {
            this.routes = new ConcurrentHashMap<>(routes);
            this.dispatchCounts = new ConcurrentHashMap<>();
        }

        private ChannelState() {
            this(new ConcurrentHashMap<>());
        }

        public <M extends Message> ChannelState addRoute(
                Class<M> messageType, ProcRef<?, Message> handler) {
            routes.put(messageType, handler);
            dispatchCounts.putIfAbsent(messageType, 0);
            return this;
        }

        public Optional<ProcRef<?, Message>> getRoute(Class<?> messageType) {
            return Optional.ofNullable(routes.get(messageType));
        }

        public boolean hasRoute(Class<?> messageType) {
            return routes.containsKey(messageType);
        }

        public Set<Class<?>> registeredTypes() {
            return Collections.unmodifiableSet(routes.keySet());
        }

        public int getDispatchCount(Class<?> messageType) {
            return dispatchCounts.getOrDefault(messageType, 0);
        }

        public int getTotalDispatched() {
            return totalDispatched;
        }

        private ChannelState incrementDispatch(Class<?> messageType) {
            dispatchCounts.compute(messageType, (k, v) -> v == null ? 1 : v + 1);
            totalDispatched++;
            return this;
        }
    }

    /**
     * Factory: Create a new DataTypeChannel router backed by a ChannelState. Returns the
     * ChannelState directly (no Proc wrapping needed).
     *
     * @return A ChannelState representing the router
     */
    public static ChannelState create() {
        return new ChannelState();
    }

    /** Create a DataTypeChannel with pre-configured routes. */
    public static ChannelState create(Map<Class<? extends Message>, ProcRef<?, Message>> routes) {
        ChannelState state = new ChannelState();
        for (var entry : routes.entrySet()) {
            state.addRoute(entry.getKey(), entry.getValue());
        }
        return state;
    }

    /** Register a handler for a specific message type on an existing channel. */
    public static <M extends Message> void addRoute(
            ChannelState channel, Class<M> messageType, ProcRef<?, Message> handler) {
        channel.addRoute(messageType, handler);
    }

    /** Dispatch a message through the channel router. */
    @SuppressWarnings("unchecked")
    public static void dispatch(ChannelState channel, Message message) {
        Class<?> messageType =
                switch (message) {
                    case Message.CommandMsg _ -> Message.CommandMsg.class;
                    case Message.EventMsg _ -> Message.EventMsg.class;
                    case Message.QueryMsg _ -> Message.QueryMsg.class;
                    case Message.DocumentMsg _ -> Message.DocumentMsg.class;
                };

        channel.getRoute(messageType).ifPresent(handler -> handler.tell((Message) message));
        channel.incrementDispatch(messageType);
    }

    /** Get current channel state (registered routes and metrics). */
    public static ChannelState getState(ChannelState channel) {
        return channel;
    }

    /** Create a strongly-typed handler wrapper for a specific message variant. */
    public static <M extends Message, State> Proc<State, Message> createTypedHandler(
            Class<M> messageType, Function<State, Function<M, State>> handler, State initialState) {

        return new Proc<>(
                initialState,
                (state, msg) -> {
                    if (messageType.isInstance(msg)) {
                        @SuppressWarnings("unchecked")
                        M typedMsg = (M) msg;
                        return handler.apply(state).apply(typedMsg);
                    }
                    return state;
                });
    }

    /**
     * Create a multi-handler router that processes multiple message types with different handlers.
     */
    public static <State> ChannelState createMultiTypeRouter(
            Function<State, Function<Message.CommandMsg, State>> commandHandler,
            Function<State, Function<Message.EventMsg, State>> eventHandler,
            Function<State, Function<Message.QueryMsg, State>> queryHandler,
            Function<State, Function<Message.DocumentMsg, State>> documentHandler,
            State initialState) {

        var cmdHandlerProc =
                createTypedHandler(Message.CommandMsg.class, commandHandler, initialState);
        var evtHandlerProc = createTypedHandler(Message.EventMsg.class, eventHandler, initialState);
        var qryHandlerProc = createTypedHandler(Message.QueryMsg.class, queryHandler, initialState);
        var docHandlerProc =
                createTypedHandler(Message.DocumentMsg.class, documentHandler, initialState);

        // Wrap Procs as ProcRefs via Supervisor
        var sv =
                new Supervisor(
                        Supervisor.Strategy.ONE_FOR_ONE, 5, java.time.Duration.ofSeconds(60));
        ProcRef<State, Message> cmdRef =
                sv.supervise(
                        "cmd-handler",
                        initialState,
                        (s, m) -> commandHandler.apply(s).apply((Message.CommandMsg) m));
        ProcRef<State, Message> evtRef =
                sv.supervise(
                        "evt-handler",
                        initialState,
                        (s, m) -> eventHandler.apply(s).apply((Message.EventMsg) m));
        ProcRef<State, Message> qryRef =
                sv.supervise(
                        "qry-handler",
                        initialState,
                        (s, m) -> queryHandler.apply(s).apply((Message.QueryMsg) m));
        ProcRef<State, Message> docRef =
                sv.supervise(
                        "doc-handler",
                        initialState,
                        (s, m) -> documentHandler.apply(s).apply((Message.DocumentMsg) m));

        var channel = create();
        channel.addRoute(Message.CommandMsg.class, cmdRef);
        channel.addRoute(Message.EventMsg.class, evtRef);
        channel.addRoute(Message.QueryMsg.class, qryRef);
        channel.addRoute(Message.DocumentMsg.class, docRef);

        return channel;
    }
}
