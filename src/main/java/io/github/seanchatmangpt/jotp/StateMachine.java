package io.github.seanchatmangpt.jotp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;

/**
 * Java 26 equivalent of Erlang/OTP's {@code gen_statem} behavior — full feature parity.
 *
 * <p>OTP {@code gen_statem} separates three concerns that gen_server conflates:
 *
 * <ol>
 *   <li><b>State</b> — which "mode" the machine is in (e.g. {@code locked} / {@code open})
 *   <li><b>Event</b> — external stimulus or internal trigger (e.g. {@code push_button}, {@code
 *       state_timeout})
 *   <li><b>Data</b> — mutable context carried across all states (e.g. entered digits)
 * </ol>
 *
 * <p>The transition function {@code (S state, SMEvent<E> event, D data) -> Transition<S,D>} handles
 * all event types via pattern matching:
 *
 * <ul>
 *   <li>{@link SMEvent.User} — external user event (cast or call)
 *   <li>{@link SMEvent.StateTimeout} — state_timeout fired (auto-canceled on state change)
 *   <li>{@link SMEvent.EventTimeout} — event_timeout fired (auto-canceled by any event)
 *   <li>{@link SMEvent.GenericTimeout} — named generic timeout (not auto-canceled)
 *   <li>{@link SMEvent.Internal} — self-inserted event via {@link Action.NextEvent}
 *   <li>{@link SMEvent.Enter} — state enter callback (requires {@link Builder#withStateEnter()})
 * </ul>
 *
 * <p>Transition results:
 *
 * <ul>
 *   <li>{@link Transition.NextState} — move to a new state, optionally with new data + actions
 *   <li>{@link Transition.KeepState} — stay in the current state, optionally update data + actions
 *   <li>{@link Transition.RepeatState} — like KeepState but re-triggers state enter + postpone
 *       replay
 *   <li>{@link Transition.Stop} — terminate the state machine
 *   <li>{@link Transition.StopAndReply} — stop and complete pending call futures
 * </ul>
 *
 * <p>Transition actions (returned alongside state changes):
 *
 * <ul>
 *   <li>{@link Action.Postpone} — defer current event until next state change
 *   <li>{@link Action.NextEvent} — insert a synthetic internal event at front of queue
 *   <li>{@link Action.SetStateTimeout} — start/update state_timeout (OTP: {@code state_timeout})
 *   <li>{@link Action.SetEventTimeout} — start event_timeout (OTP: {@code timeout})
 *   <li>{@link Action.SetGenericTimeout} — start named generic timeout (OTP: {@code
 *       {timeout,Name}})
 *   <li>{@link Action.Reply} — explicitly complete a pending call future
 * </ul>
 *
 * <p><b>Usage — code lock with state_timeout:</b>
 *
 * <pre>{@code
 * sealed interface LockState  permits Locked, Open {}
 * record Locked() implements LockState {}
 * record Open()   implements LockState {}
 *
 * sealed interface LockEvent  permits PushButton {}
 * record PushButton(char button) implements LockEvent {}
 *
 * var sm = StateMachine.of(
 *     new Locked(),
 *     new LockData("", "1234"),
 *     (state, event, data) -> switch (state) {
 *         case Locked() -> switch (event) {
 *             case SMEvent.User(PushButton(var b)) -> {
 *                 var entered = data.entered() + b;
 *                 yield entered.equals(data.code())
 *                     ? Transition.nextState(new Open(), data.withEntered(""),
 *                           Action.stateTimeout(10_000, "lock"))  // auto-lock after 10s
 *                     : Transition.keepState(data.withEntered(entered));
 *             }
 *             case SMEvent.StateTimeout(var _) ->
 *                 Transition.keepState(data.withEntered(""));  // reset on timeout
 *             default -> Transition.keepState(data);
 *         };
 *         case Open() -> switch (event) {
 *             case SMEvent.StateTimeout(var _) ->
 *                 Transition.nextState(new Locked(), data);   // auto-lock fires
 *             case SMEvent.User(var _) ->
 *                 Transition.keepState(data, Action.postpone());  // defer buttons to locked
 *             default -> Transition.keepState(data);
 *         };
 *     }
 * );
 * }</pre>
 *
 * <p><b>OTP feature mapping:</b>
 *
 * <pre>{@code
 * Erlang gen_statem                        Java StateMachine
 * ──────────────────────────────────────   ───────────────────────────────────────────
 * {next_state, S, D}                  →   Transition.nextState(s, d)
 * {keep_state, D}                     →   Transition.keepState(d)
 * keep_state_and_data                 →   Transition.keepState(data)   (same ref)
 * {repeat_state, D}                   →   Transition.repeatState(d)
 * {stop, Reason}                      →   Transition.stop(reason)
 * {stop_and_reply, Reason, Replies}   →   Transition.stopAndReply(reason, actions...)
 * [postpone]                          →   Action.postpone()
 * [{next_event,internal,C}]           →   Action.nextEvent(c)
 * [{state_timeout, Ms, C}]            →   Action.stateTimeout(ms, c)
 * [{timeout, Ms, C}]                  →   Action.eventTimeout(ms, c)
 * [{{timeout,N}, Ms, C}]              →   Action.genericTimeout(name, ms, c)
 * [{reply, From, V}]                  →   Action.reply(from, v)
 * gen_statem:cast/call                →   send(event) / call(event)
 * state_enter callback_mode option    →   StateMachine.create(...).withStateEnter().start()
 * }</pre>
 *
 * @param <S> state type — use a {@code sealed interface} of {@code record}s for exhaustive switch
 * @param <E> event type — use a {@code sealed interface} of {@code record}s
 * @param <D> data type — carried across state transitions (immutable record recommended)
 */
public final class StateMachine<S, E, D> {

    // ── SMEvent sealed hierarchy ──────────────────────────────────────────────

    /**
     * Sealed wrapper for all event types delivered to the transition function.
     *
     * <p>Mirrors OTP's event type + content separation. Use Java 26 nested record patterns: {@code
     * case SMEvent.User(PushButton(var b)) ->} to cleanly destructure.
     */
    public sealed interface SMEvent<E>
            permits SMEvent.User,
                    SMEvent.StateTimeout,
                    SMEvent.EventTimeout,
                    SMEvent.GenericTimeout,
                    SMEvent.Internal,
                    SMEvent.Enter {

        /** External user event (cast or call). OTP: {@code cast} / {@code {call,From}} */
        record User<E>(E event) implements SMEvent<E> {}

        /**
         * State timeout fired. Auto-canceled on any state change.
         *
         * <p>OTP: {@code state_timeout} event type.
         */
        record StateTimeout<E>(Object content) implements SMEvent<E> {}

        /**
         * Event timeout fired. Auto-canceled when any event (including internal) arrives.
         *
         * <p>OTP: {@code timeout} event type.
         */
        record EventTimeout<E>(Object content) implements SMEvent<E> {}

        /**
         * Named generic timeout fired. NOT auto-canceled on state change.
         *
         * <p>OTP: {@code {timeout, Name}} event type.
         */
        record GenericTimeout<E>(String name, Object content) implements SMEvent<E> {}

        /**
         * Self-inserted synthetic event, processed before the external mailbox.
         *
         * <p>OTP: {@code internal} event type via {@code {next_event, internal, Content}}.
         */
        record Internal<E>(Object content) implements SMEvent<E> {}

        /**
         * State enter callback, fired each time a state is entered (requires {@link
         * Builder#withStateEnter()}).
         *
         * <p>OTP: {@code enter} event type when {@code state_enter} is in callback_mode list.
         *
         * @param previousState the state transitioned from; {@code null} for the initial state
         */
        record Enter<E>(Object previousState) implements SMEvent<E> {}
    }

    // ── Action sealed hierarchy ───────────────────────────────────────────────

    /**
     * Transition actions returned alongside a {@link Transition} to command the engine.
     *
     * <p>OTP: the {@code [Actions]} list in {@code {next_state, S, D, [Actions]}}.
     */
    public sealed interface Action
            permits Action.Reply,
                    Action.Postpone,
                    Action.NextEvent,
                    Action.SetStateTimeout,
                    Action.CancelStateTimeout,
                    Action.SetEventTimeout,
                    Action.CancelEventTimeout,
                    Action.SetGenericTimeout,
                    Action.CancelGenericTimeout {

        /** Reply to a pending call future. OTP: {@code {reply, From, Reply}} */
        record Reply(CompletableFuture<Object> from, Object value) implements Action {}

        /** Defer the current event until the next state change, then re-deliver. */
        record Postpone() implements Action {}

        /**
         * Insert a synthetic {@link SMEvent.Internal} event at the head of the event queue,
         * processed before the next external message.
         */
        record NextEvent(Object content) implements Action {}

        /** Start (or restart) the state_timeout. OTP: {@code {state_timeout, Ms, Content}} */
        record SetStateTimeout(long delayMs, Object content) implements Action {}

        /** Cancel any pending state_timeout. OTP: {@code {state_timeout, cancel}} */
        record CancelStateTimeout() implements Action {}

        /** Start (or restart) the event_timeout. OTP: {@code {timeout, Ms, Content}} */
        record SetEventTimeout(long delayMs, Object content) implements Action {}

        /** Cancel any pending event_timeout. OTP: {@code {timeout, cancel}} */
        record CancelEventTimeout() implements Action {}

        /** Start (or restart) a named generic timeout. OTP: {@code {{timeout,N}, Ms, C}} */
        record SetGenericTimeout(String name, long delayMs, Object content) implements Action {}

        /** Cancel a named generic timeout. OTP: {@code {{timeout,N}, cancel}} */
        record CancelGenericTimeout(String name) implements Action {}

        // ── Factory methods ─────────────────────────────────────────────────

        /** OTP: {@code {reply, From, Value}} */
        @SuppressWarnings("unchecked")
        static Action reply(CompletableFuture<?> from, Object value) {
            return new Reply((CompletableFuture<Object>) from, value);
        }

        /** OTP: {@code postpone} */
        static Action postpone() {
            return new Postpone();
        }

        /** OTP: {@code {next_event, internal, Content}} */
        static Action nextEvent(Object content) {
            return new NextEvent(content);
        }

        /** OTP: {@code {state_timeout, Ms, Content}} */
        static Action stateTimeout(long delayMs, Object content) {
            return new SetStateTimeout(delayMs, content);
        }

        /** OTP: {@code {state_timeout, cancel}} */
        static Action cancelStateTimeout() {
            return new CancelStateTimeout();
        }

        /** OTP: {@code {timeout, Ms, Content}} */
        static Action eventTimeout(long delayMs, Object content) {
            return new SetEventTimeout(delayMs, content);
        }

        /** OTP: {@code {timeout, cancel}} */
        static Action cancelEventTimeout() {
            return new CancelEventTimeout();
        }

        /** OTP: {@code {{timeout, Name}, Ms, Content}} */
        static Action genericTimeout(String name, long delayMs, Object content) {
            return new SetGenericTimeout(name, delayMs, content);
        }

        /** OTP: {@code {{timeout, Name}, cancel}} */
        static Action cancelGenericTimeout(String name) {
            return new CancelGenericTimeout(name);
        }
    }

    // ── Transition sealed hierarchy ───────────────────────────────────────────

    /**
     * The return type of a state machine transition function.
     *
     * <p>Obtain instances via the static factory methods: {@link #nextState}, {@link #keepState},
     * {@link #repeatState}, {@link #stop}, {@link #stopAndReply}.
     */
    public sealed interface Transition<S, D>
            permits Transition.NextState,
                    Transition.KeepState,
                    Transition.RepeatState,
                    Transition.Stop,
                    Transition.StopAndReply {

        /** Actions to execute after the state/data update. */
        List<Action> actions();

        // ── Variants ───────────────────────────────────────────────────────

        /** OTP: {@code {next_state, State, Data [, Actions]}} */
        record NextState<S, D>(S state, D data, List<Action> actions) implements Transition<S, D> {}

        /** OTP: {@code {keep_state, Data [, Actions]}} / {@code keep_state_and_data} */
        record KeepState<S, D>(D data, List<Action> actions) implements Transition<S, D> {}

        /**
         * Like KeepState but re-triggers state enter calls and postponed-event replay.
         *
         * <p>OTP: {@code {repeat_state, Data [, Actions]}}
         */
        record RepeatState<S, D>(D data, List<Action> actions) implements Transition<S, D> {}

        /** OTP: {@code {stop, Reason}} */
        record Stop<S, D>(String reason) implements Transition<S, D> {
            public List<Action> actions() {
                return List.of();
            }
        }

        /**
         * Stop and complete pending call futures with explicit reply values.
         *
         * <p>OTP: {@code {stop_and_reply, Reason, Replies}}
         */
        record StopAndReply<S, D>(String reason, List<Action> actions)
                implements Transition<S, D> {}

        // ── Factory methods ─────────────────────────────────────────────────

        /** OTP: {@code {next_state, S, D}} / {@code {next_state, S, D, [Actions]}} */
        static <S, D> Transition<S, D> nextState(S state, D data, Action... actions) {
            return new NextState<>(state, data, List.of(actions));
        }

        /** OTP: {@code {keep_state, D}} / {@code keep_state_and_data} */
        static <S, D> Transition<S, D> keepState(D data, Action... actions) {
            return new KeepState<>(data, List.of(actions));
        }

        /** OTP: {@code {repeat_state, D}} */
        static <S, D> Transition<S, D> repeatState(D data, Action... actions) {
            return new RepeatState<>(data, List.of(actions));
        }

        /** OTP: {@code {stop, Reason}} */
        static <S, D> Transition<S, D> stop(String reason) {
            return new Stop<>(reason);
        }

        /** OTP: {@code {stop_and_reply, Reason, [Replies]}} */
        static <S, D> Transition<S, D> stopAndReply(String reason, Action... replies) {
            return new StopAndReply<>(reason, List.of(replies));
        }
    }

    // ── TransitionFn ──────────────────────────────────────────────────────────

    /**
     * The pure transition function — equivalent to OTP's {@code handle_event/4}.
     *
     * <p>OTP: {@code handle_event(EventType, EventContent, State, Data) -> Transition}
     *
     * <p>The {@code event} parameter is an {@link SMEvent}{@code <E>} that wraps both user events
     * and internal engine events (timeouts, enter calls, inserted events). Use nested record
     * patterns to destructure: {@code case SMEvent.User(MyEvent(var field)) ->}.
     */
    @FunctionalInterface
    public interface TransitionFn<S, E, D> {
        Transition<S, D> apply(S state, SMEvent<E> event, D data);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Builder for {@link StateMachine} — configure options before starting. */
    public static final class Builder<S, E, D> {
        private final S initialState;
        private final D initialData;
        private final TransitionFn<S, E, D> fn;
        private boolean stateEnterEnabled = false;

        private Builder(S state, D data, TransitionFn<S, E, D> fn) {
            this.initialState = Objects.requireNonNull(state, "initialState");
            this.initialData = Objects.requireNonNull(data, "initialData");
            this.fn = Objects.requireNonNull(fn, "fn");
        }

        /**
         * Enable state enter callbacks.
         *
         * <p>When enabled, the transition function is called with {@link SMEvent.Enter} each time
         * the machine enters a state (including the initial state on startup). Actions returned
         * from an enter callback are processed; state/data changes are ignored (you must return
         * keepState/repeatState from an enter callback).
         *
         * <p>OTP: {@code callback_mode() -> [handle_event_function, state_enter]}
         */
        public Builder<S, E, D> withStateEnter() {
            this.stateEnterEnabled = true;
            return this;
        }

        /** Create and start the state machine. */
        public StateMachine<S, E, D> start() {
            return new StateMachine<>(this);
        }
    }

    // ── Internal envelope ─────────────────────────────────────────────────────

    private record Envelope<E>(SMEvent<E> event, CompletableFuture<Object> reply) {}

    // ── State machine fields ──────────────────────────────────────────────────

    private volatile S currentState;
    private volatile D currentData;
    private volatile String stopReason = null;
    private volatile boolean running = true;

    private final TransferQueue<Envelope<E>> mailbox = new LinkedTransferQueue<>();
    private final Thread thread;
    private final boolean stateEnterEnabled;

    // ── Per-instance internal state (accessed only from virtual thread) ───────

    private final Deque<Envelope<E>> insertedEvents = new ArrayDeque<>();
    private final Deque<Envelope<E>> postponedEvents = new ArrayDeque<>();
    private ScheduledFuture<?> stateTimeoutFuture = null;
    private ScheduledFuture<?> eventTimeoutFuture = null;
    private boolean eventTimeoutActive = false;
    private final Map<String, ScheduledFuture<?>> genericTimers = new HashMap<>();

    // ── Shared daemon timer scheduler ─────────────────────────────────────────

    private static final ScheduledExecutorService TIMER_SCHEDULER =
            Executors.newScheduledThreadPool(
                    1,
                    r -> {
                        var t = new Thread(r, "statem-timer");
                        t.setDaemon(true);
                        return t;
                    });

    // ── Constructor (private — use Builder or factory methods) ────────────────

    private StateMachine(Builder<S, E, D> builder) {
        this.currentState = builder.initialState;
        this.currentData = builder.initialData;
        this.stateEnterEnabled = builder.stateEnterEnabled;
        this.thread = Thread.ofVirtual().name("statem").start(() -> loop(builder.fn));
    }

    /**
     * @deprecated Use {@link #create(Object, Object, TransitionFn)} builder or {@link #of(Object,
     *     Object, TransitionFn)} shortcut instead.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public StateMachine(S initialState, D initialData, TransitionFn<S, E, D> fn) {
        this(new Builder<>(initialState, initialData, fn));
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Create a builder for a new state machine.
     *
     * <pre>{@code
     * var sm = StateMachine.create(initialState, initialData, fn)
     *     .withStateEnter()
     *     .start();
     * }</pre>
     */
    public static <S, E, D> Builder<S, E, D> create(
            S initialState, D initialData, TransitionFn<S, E, D> fn) {
        return new Builder<>(initialState, initialData, fn);
    }

    /**
     * Shortcut: create and immediately start a state machine with default options (no state enter).
     *
     * <p>Equivalent to {@code StateMachine.create(s, d, fn).start()}.
     */
    public static <S, E, D> StateMachine<S, E, D> of(
            S initialState, D initialData, TransitionFn<S, E, D> fn) {
        return new Builder<>(initialState, initialData, fn).start();
    }

    // ── Event API ─────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget: deliver {@code event} to the state machine without waiting.
     *
     * <p>OTP: {@code gen_statem:cast(Pid, Event)}
     */
    public void send(E event) {
        if (!running) return;
        mailbox.add(new Envelope<>(new SMEvent.User<>(event), null));
    }

    /**
     * Request-reply: deliver {@code event} and return a future that completes with the machine's
     * data after the event is processed (or with a reply value set via {@link Action.Reply}).
     *
     * <p>OTP: {@code gen_statem:call(Pid, Event)}
     *
     * @return future completing with {@link D} after the transition, or failing with {@link
     *     IllegalStateException} if the machine stopped
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<D> call(E event) {
        if (!running) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("state machine stopped: " + stopReason));
        }
        var future = new CompletableFuture<Object>();
        mailbox.add(new Envelope<>(new SMEvent.User<>(event), future));
        return future.thenApply(d -> (D) d);
    }

    // ── State/data queries ────────────────────────────────────────────────────

    /** Returns the current state. Thread-safe (volatile read). */
    public S state() {
        return currentState;
    }

    /** Returns the current data. Thread-safe (volatile read). */
    public D data() {
        return currentData;
    }

    /** Returns {@code true} if the machine is still running. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the stop reason if the machine has stopped, or {@code null} if still running.
     *
     * <p>OTP: the reason atom/term passed to {@code {stop, Reason}}.
     */
    public String stopReason() {
        return stopReason;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Graceful shutdown: stop the machine and wait for the virtual thread to finish.
     *
     * <p>OTP: {@code gen_statem:stop(Pid)}
     */
    public void stop() throws InterruptedException {
        running = false;
        thread.interrupt();
        thread.join();
    }

    // ── Internal engine loop ──────────────────────────────────────────────────

    private void loop(TransitionFn<S, E, D> fn) {
        try {
            // Fire initial state enter call if enabled
            if (stateEnterEnabled) {
                dispatchEnter(null, fn);
            }

            while (running) {
                // Priority: inserted events (from next_event actions) before mailbox
                Envelope<E> env = insertedEvents.pollFirst();
                if (env == null) {
                    try {
                        env = mailbox.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                        drainAll();
                        return;
                    }
                }

                // Event timeout: auto-canceled by any non-EventTimeout event
                if (!(env.event() instanceof SMEvent.EventTimeout<?>)) {
                    if (eventTimeoutActive) {
                        cancelEventTimeout();
                    }
                }

                // Discard stale StateTimeout that arrived after state change canceled it
                if (env.event() instanceof SMEvent.StateTimeout<?> && stateTimeoutFuture == null) {
                    // Stale timeout — silently discard
                    continue;
                }

                // Discard stale EventTimeout that arrived after it was canceled
                if (env.event() instanceof SMEvent.EventTimeout<?> && !eventTimeoutActive) {
                    continue;
                }

                S prevState = currentState;
                Transition<S, D> transition = fn.apply(currentState, env.event(), currentData);

                boolean forceEnter = false;
                boolean wasPostponed = false;

                switch (transition) {
                    case Transition.NextState<S, D>(var newState, var newData, var actions) -> {
                        currentState = newState;
                        currentData = newData;
                        wasPostponed = processActions(actions, env);
                    }
                    case Transition.KeepState<S, D>(var newData, var actions) -> {
                        currentData = newData;
                        wasPostponed = processActions(actions, env);
                    }
                    case Transition.RepeatState<S, D>(var newData, var actions) -> {
                        currentData = newData;
                        forceEnter = true;
                        wasPostponed = processActions(actions, env);
                    }
                    case Transition.Stop<S, D>(var reason) -> {
                        stopReason = reason;
                        running = false;
                        if (env.reply() != null) {
                            env.reply()
                                    .completeExceptionally(
                                            new IllegalStateException(
                                                    "state machine stopped: " + reason));
                        }
                        drainAll();
                        return;
                    }
                    case Transition.StopAndReply<S, D>(var reason, var replies) -> {
                        // Process reply actions before stopping
                        for (Action action : replies) {
                            if (action instanceof Action.Reply(var from, var value)
                                    && from != null) {
                                from.complete(value);
                            }
                        }
                        stopReason = reason;
                        running = false;
                        if (env.reply() != null) {
                            env.reply()
                                    .completeExceptionally(
                                            new IllegalStateException(
                                                    "state machine stopped: " + reason));
                        }
                        drainAll();
                        return;
                    }
                }

                // Complete reply future if not already handled by a Reply action and not postponed
                if (!wasPostponed && env.reply() != null && !env.reply().isDone()) {
                    env.reply().complete(currentData);
                }

                boolean isStateChange = !Objects.equals(currentState, prevState) || forceEnter;
                if (isStateChange) {
                    cancelStateTimeout();
                    replayPostponed();
                    if (stateEnterEnabled) {
                        dispatchEnter(prevState, fn);
                    }
                }
            }
        } catch (Exception e) {
            running = false;
            drainAll();
        }
    }

    /** Process transition actions. Returns {@code true} if the current event was postponed. */
    private boolean processActions(List<Action> actions, Envelope<E> currentEnv) {
        boolean wasPostponed = false;
        for (Action action : actions) {
            switch (action) {
                case Action.Reply(var from, var value) -> from.complete(value);

                case Action.Postpone() -> {
                    postponedEvents.addLast(currentEnv);
                    wasPostponed = true;
                }

                case Action.NextEvent(var content) ->
                        insertedEvents.addFirst(
                                new Envelope<>(new SMEvent.Internal<>(content), null));

                case Action.SetStateTimeout(var delayMs, var content) -> {
                    cancelStateTimeout();
                    stateTimeoutFuture =
                            TIMER_SCHEDULER.schedule(
                                    () ->
                                            mailbox.add(
                                                    new Envelope<>(
                                                            new SMEvent.StateTimeout<>(content),
                                                            null)),
                                    delayMs,
                                    TimeUnit.MILLISECONDS);
                }

                case Action.CancelStateTimeout() -> cancelStateTimeout();

                case Action.SetEventTimeout(var delayMs, var content) -> {
                    cancelEventTimeout();
                    eventTimeoutActive = true;
                    eventTimeoutFuture =
                            TIMER_SCHEDULER.schedule(
                                    () ->
                                            mailbox.add(
                                                    new Envelope<>(
                                                            new SMEvent.EventTimeout<>(content),
                                                            null)),
                                    delayMs,
                                    TimeUnit.MILLISECONDS);
                }

                case Action.CancelEventTimeout() -> cancelEventTimeout();

                case Action.SetGenericTimeout(var name, var delayMs, var content) -> {
                    cancelGenericTimeout(name);
                    genericTimers.put(
                            name,
                            TIMER_SCHEDULER.schedule(
                                    () ->
                                            mailbox.add(
                                                    new Envelope<>(
                                                            new SMEvent.GenericTimeout<>(
                                                                    name, content),
                                                            null)),
                                    delayMs,
                                    TimeUnit.MILLISECONDS));
                }

                case Action.CancelGenericTimeout(var name) -> cancelGenericTimeout(name);
            }
        }
        return wasPostponed;
    }

    /** Fire state enter callback for the current state. */
    private void dispatchEnter(S previousState, TransitionFn<S, E, D> fn) {
        SMEvent<E> enterEvent = new SMEvent.Enter<>(previousState);
        Transition<S, D> result = fn.apply(currentState, enterEvent, currentData);
        // Only process actions from enter callbacks; state/data changes are ignored.
        processActions(result.actions(), new Envelope<>(enterEvent, null));
        // Update data from enter callback (keepState/repeatState data is accepted)
        switch (result) {
            case Transition.KeepState<S, D>(var newData, var _) -> currentData = newData;
            case Transition.RepeatState<S, D>(var newData, var _) -> currentData = newData;
            default -> {} // NextState/Stop in enter callback: ignore state change
        }
    }

    /** Move all postponed events to the front of the inserted-events queue (in order). */
    private void replayPostponed() {
        if (postponedEvents.isEmpty()) return;
        // Add in reverse order to insertedEvents.addFirst so original order is preserved
        var replay = new ArrayList<>(postponedEvents);
        postponedEvents.clear();
        for (int i = replay.size() - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            Envelope<E> env = (Envelope<E>) replay.get(i);
            insertedEvents.addFirst(env);
        }
    }

    private void cancelStateTimeout() {
        if (stateTimeoutFuture != null) {
            stateTimeoutFuture.cancel(false);
            stateTimeoutFuture = null;
        }
    }

    private void cancelEventTimeout() {
        if (eventTimeoutFuture != null) {
            eventTimeoutFuture.cancel(false);
            eventTimeoutFuture = null;
        }
        eventTimeoutActive = false;
    }

    private void cancelGenericTimeout(String name) {
        ScheduledFuture<?> f = genericTimers.remove(name);
        if (f != null) f.cancel(false);
    }

    private void cancelAllTimers() {
        cancelStateTimeout();
        cancelEventTimeout();
        genericTimers.forEach((name, f) -> f.cancel(false));
        genericTimers.clear();
    }

    /** Drain all remaining envelopes, failing any pending call futures. */
    private void drainAll() {
        cancelAllTimers();
        // Drain inserted events
        Envelope<E> env;
        while ((env = insertedEvents.pollFirst()) != null) {
            failEnvelope(env);
        }
        // Drain postponed events
        while ((env = postponedEvents.pollFirst()) != null) {
            failEnvelope(env);
        }
        // Drain external mailbox
        while ((env = mailbox.poll()) != null) {
            failEnvelope(env);
        }
    }

    private void failEnvelope(Envelope<E> env) {
        if (env.reply() != null && !env.reply().isDone()) {
            env.reply()
                    .completeExceptionally(
                            new IllegalStateException("state machine stopped: " + stopReason));
        }
    }
}
