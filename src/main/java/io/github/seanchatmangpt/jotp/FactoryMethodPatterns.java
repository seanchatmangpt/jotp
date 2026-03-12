package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Demonstration of standardized factory method patterns across all OTP primitives.
 *
 * <p>This class shows the canonical way to create each OTP primitive using static factory methods
 * ({@code spawn()}, {@code create()}, {@code start()}, {@code all()}, {@code retry()}). The
 * patterns are:
 *
 * <ul>
 *   <li><strong>Lightweight process:</strong> {@link Proc#spawn(Object,
 *       java.util.function.BiFunction)}
 *   <li><strong>Supervision tree:</strong> {@link Supervisor#create(Supervisor.Strategy, int,
 *       Duration)} or {@link Supervisor#create(String, Supervisor.Strategy, int, Duration)}
 *   <li><strong>State machine:</strong> {@link StateMachine#create(Object, Object,
 *       StateMachine.TransitionFn)}
 *   <li><strong>Event manager:</strong> {@link EventManager#start()} or {@link
 *       EventManager#start(Duration)}
 *   <li><strong>Parallel execution:</strong> {@link Parallel#all(List)}
 *   <li><strong>Crash recovery:</strong> {@link CrashRecovery#retry(int,
 *       java.util.function.Supplier)}
 * </ul>
 *
 * <p>All factory methods are:
 *
 * <ul>
 *   <li><strong>Explicit:</strong> Factory method names clearly indicate intent (spawn, create,
 *       start, all, retry)
 *   <li><strong>Testable:</strong> Easy to mock or extend via subclassing (unlike constructors)
 *   <li><strong>Flexible:</strong> Can add variants (named, with timeout, custom configuration)
 *       without breaking existing code
 *   <li><strong>Discoverable:</strong> IDE autocomplete and Javadoc make patterns visible
 * </ul>
 *
 * <p>Old constructors remain available but are marked {@code @Deprecated} for backward
 * compatibility. New code should use the factory methods.
 */
public final class FactoryMethodPatterns {

    private FactoryMethodPatterns() {}

    sealed interface CounterMsg {
        record Increment() implements CounterMsg {}

        record Reset() implements CounterMsg {}
    }

    record Counter(int value) {}

    sealed interface LockState {
        record Locked() implements LockState {}

        record Open() implements LockState {}
    }

    sealed interface LockEvent {
        record PushButton(char digit) implements LockEvent {}

        record Lock() implements LockEvent {}
    }

    record LockData(String entered, String code) {
        LockData withEntered(String entered) {
            return new LockData(entered, code);
        }
    }

    sealed interface LogEvent {
        record ErrorEvent(String msg) implements LogEvent {}

        record WarningEvent(String msg) implements LogEvent {}
    }

    sealed interface IntegrationCounterMsg {
        record Increment() implements IntegrationCounterMsg {}
    }

    record IntegrationCounter(int value) {}

    sealed interface IntegrationEvent {
        record Processing() implements IntegrationEvent {}
    }

    /**
     * Example 1: Lightweight process creation using {@link Proc#spawn(Object,
     * java.util.function.BiFunction)}.
     *
     * <p>Mirrors Erlang's {@code spawn(Module, Function, Args)} — launches a lightweight virtual
     * thread with isolated state and mailbox.
     *
     * <pre>{@code
     * // Define state and messages
     * record Counter(int value) {}
     * sealed interface CounterMsg permits Increment, Reset {}
     * record Increment() implements CounterMsg {}
     * record Reset() implements CounterMsg {}
     *
     * // Spawn a process (not: new Proc(...))
     * Proc<Counter, CounterMsg> counter = Proc.spawn(
     *     new Counter(0),
     *     (state, msg) -> switch (msg) {
     *         case Increment _ -> new Counter(state.value() + 1);
     *         case Reset _ -> new Counter(0);
     *     }
     * );
     *
     * // Send messages
     * counter.tell(new Increment());
     * counter.tell(new Increment());
     * var futureState = counter.ask(new Reset()); // returns next state
     * }</pre>
     */
    public static void exampleProcSpawn() {
        // State and messages
        record Counter(int value) {}

        interface CounterMsg {
            record Increment() implements CounterMsg {}

            record Reset() implements CounterMsg {}
        }

        // Spawn using factory (NOT: new Proc(...))
        Proc<Counter, CounterMsg> counter =
                Proc.spawn(
                        new Counter(0),
                        (state, msg) ->
                                switch (msg) {
                                    case CounterMsg.Increment ignored ->
                                            new Counter(state.value() + 1);
                                    case CounterMsg.Reset ignored -> new Counter(0);
                                    default -> state;
                                });

        // Send messages
        counter.tell(new CounterMsg.Increment());
        counter.tell(new CounterMsg.Increment());
    }

    /**
     * Example 2: Supervision tree creation using {@link Supervisor#create(Supervisor.Strategy, int,
     * Duration)}.
     *
     * <p>Establishes a hierarchy of supervised processes with automatic restart strategies
     * (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE).
     *
     * <pre>{@code
     * // Create supervisor (not: new Supervisor(...))
     * var supervisor = Supervisor.create(
     *     Supervisor.Strategy.ONE_FOR_ONE,
     *     5,
     *     Duration.ofSeconds(60)
     * );
     *
     * // Add supervised children
     * var worker1 = supervisor.supervise("worker-1", initialState1, handler1);
     * var worker2 = supervisor.supervise("worker-2", initialState2, handler2);
     *
     * // If worker1 crashes, only worker1 is restarted
     * // If any worker crashes > 5 times in 60 seconds, supervisor terminates
     *
     * supervisor.shutdown();
     * }</pre>
     */
    public static void exampleSupervisorCreate() {
        // Create supervisor using factory (NOT: new Supervisor(...))
        Supervisor supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        // Supervise children (would add actual handlers in practice)
        // var child1 = supervisor.supervise("child-1", state1, handler1);
    }

    /**
     * Example 3: Named supervision tree using {@link Supervisor#create(String, Supervisor.Strategy,
     * int, Duration)}.
     *
     * <p>Same as {@link #exampleSupervisorCreate()} but with an explicit name for better
     * diagnostics.
     *
     * <pre>{@code
     * var appSupervisor = Supervisor.create(
     *     "app-supervisor",
     *     Supervisor.Strategy.ONE_FOR_ALL,
     *     3,
     *     Duration.ofSeconds(30)
     * );
     * }</pre>
     */
    public static void exampleNamedSupervisor() {
        // Named supervisor for better diagnostics
        Supervisor supervisor =
                Supervisor.create(
                        "app-supervisor",
                        Supervisor.Strategy.ONE_FOR_ALL,
                        3,
                        Duration.ofSeconds(30));
    }

    /**
     * Example 4: State machine creation using {@link StateMachine#create(Object, Object,
     * StateMachine.TransitionFn)}.
     *
     * <p>Separates state, event, and data for sophisticated process control (OTP {@code
     * gen_statem}).
     *
     * <pre>{@code
     * sealed interface LockState permits Locked, Open {}
     * record Locked() implements LockState {}
     * record Open() implements LockState {}
     *
     * sealed interface LockEvent permits PushButton, Lock {}
     * record PushButton(char digit) implements LockEvent {}
     * record Lock() implements LockEvent {}
     *
     * record LockData(String entered, String code) {}
     *
     * // Create state machine (NOT: new StateMachine(...))
     * var sm = StateMachine.create(
     *     new Locked(),
     *     new LockData("", "1234"),
     *     (state, event, data) -> switch (state) {
     *         case Locked _ -> switch (event) {
     *             case PushButton(var b) -> {
     *                 var entered = data.entered() + b;
     *                 yield entered.equals(data.code())
     *                     ? Transition.nextState(new Open(), data.withEntered(""))
     *                     : Transition.keepState(data.withEntered(entered));
     *             }
     *             default -> Transition.keepState(data);
     *         };
     *         case Open _ -> switch (event) {
     *             case Lock _ -> Transition.nextState(new Locked(), data);
     *             default -> Transition.keepState(data);
     *         };
     *     }
     * );
     *
     * sm.send(new PushButton('1'));
     * sm.send(new PushButton('2'));
     * sm.send(new PushButton('3'));
     * sm.send(new PushButton('4'));
     * LockData result = sm.call(new Lock()).join();
     * }</pre>
     */
    public static void exampleStateMachineCreate() {
        interface LockState {
            record Locked() implements LockState {}

            record Open() implements LockState {}
        }

        interface LockEvent {
            record PushButton(char digit) implements LockEvent {}

            record Lock() implements LockEvent {}
        }

        record LockData(String entered, String code) {
            LockData withEntered(String entered) {
                return new LockData(entered, code);
            }
        }

        // Create state machine using factory (NOT: new StateMachine(...))
        StateMachine<LockState, LockEvent, LockData> sm =
                StateMachine.create(
                        new LockState.Locked(),
                        new LockData("", "1234"),
                        (state, event, data) ->
                                switch (state) {
                                    case LockState.Locked _ ->
                                            switch (event) {
                                                case LockEvent.PushButton(var b) -> {
                                                    var entered = data.entered() + b;
                                                    yield entered.equals(data.code())
                                                            ? StateMachine.Transition.nextState(
                                                                    new LockState.Open(),
                                                                    data.withEntered(""))
                                                            : StateMachine.Transition.keepState(
                                                                    data.withEntered(entered));
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case LockState.Open _ ->
                                            switch (event) {
                                                case LockEvent.Lock _ ->
                                                        StateMachine.Transition.nextState(
                                                                new LockState.Locked(), data);
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    default -> StateMachine.Transition.keepState(data);
                                });
    }

    /**
     * Example 5: Event manager creation using {@link EventManager#start()}.
     *
     * <p>Decouples event producers from consumers — OTP {@code gen_event} pattern.
     *
     * <pre>{@code
     * sealed interface LogEvent permits ErrorEvent, WarningEvent {}
     * record ErrorEvent(String msg) implements LogEvent {}
     * record WarningEvent(String msg) implements LogEvent {}
     *
     * // Start event manager using factory (NOT: new EventManager(...))
     * var manager = EventManager.start();
     *
     * var consoleHandler = new EventManager.Handler<LogEvent>() {
     *     public void handleEvent(LogEvent e) {
     *         switch (e) {
     *             case ErrorEvent(var msg) -> System.err.println("ERROR: " + msg);
     *             case WarningEvent(var msg) -> System.out.println("WARN: " + msg);
     *         }
     *     }
     * };
     *
     * manager.addHandler(consoleHandler);
     * manager.notify(new ErrorEvent("Something broke"));
     * manager.syncNotify(new WarningEvent("Use deprecated API"));
     * manager.deleteHandler(consoleHandler);
     * manager.stop();
     * }</pre>
     */
    public static void exampleEventManagerStart() {
        interface LogEvent {
            record ErrorEvent(String msg) implements LogEvent {}

            record WarningEvent(String msg) implements LogEvent {}
        }

        // Start event manager using factory (NOT: new EventManager(...))
        EventManager<LogEvent> manager = EventManager.start();

        // Create a handler
        EventManager.Handler<LogEvent> handler =
                new EventManager.Handler<LogEvent>() {
                    @Override
                    public void handleEvent(LogEvent e) {
                        switch (e) {
                            case LogEvent.ErrorEvent(var msg) ->
                                    System.err.println("ERROR: " + msg);
                            case LogEvent.WarningEvent(var msg) ->
                                    System.out.println("WARN: " + msg);
                            default -> {}
                        }
                    }
                };

        manager.addHandler(handler);
        manager.notify(new LogEvent.ErrorEvent("test"));
    }

    /**
     * Example 6: Parallel execution using {@link Parallel#all(List)}.
     *
     * <p>Runs multiple tasks concurrently with fail-fast semantics — all-or-nothing result.
     *
     * <pre>{@code
     * var tasks = List.of(
     *     () -> service.fetchUser(1),
     *     () -> service.fetchUser(2),
     *     () -> service.fetchUser(3)
     * );
     *
     * var result = Parallel.all(tasks);
     *
     * switch (result) {
     *     case Result.Success(var users) ->
     *         users.forEach(u -> System.out.println("Got: " + u));
     *     case Result.Failure(var ex) ->
     *         System.err.println("Fetch failed: " + ex);
     * }
     * }</pre>
     */
    public static void exampleParallelAll() {
        List<Supplier<String>> tasks =
                List.of(() -> "result1", () -> "result2", () -> "result3");

        // Run all tasks in parallel
        Result<List<String>, Exception> result = Parallel.all(tasks);

        switch (result) {
            case Result.Ok(var results) -> results.forEach(r -> System.out.println("Got: " + r));
            case Result.Success(var results) ->
                    results.forEach(r -> System.out.println("Got: " + r));
            case Result.Err(var ex) -> System.err.println("Failed: " + ex);
            case Result.Failure(var ex) -> System.err.println("Failed: " + ex);
        }
    }

    /**
     * Example 7: Crash recovery using {@link CrashRecovery#retry(int,
     * java.util.function.Supplier)}.
     *
     * <p>Resilient execution with automatic retry — isolated virtual threads, shared-nothing.
     *
     * <pre>{@code
     * var result = CrashRecovery.retry(3, () -> {
     *     var response = http.get("https://api.example.com/data");
     *     if (response.status() >= 500) {
     *         throw new RuntimeException("Server error");
     *     }
     *     return response.body();
     * });
     *
     * switch (result) {
     *     case Result.Success(var body) -> System.out.println("Got: " + body);
     *     case Result.Failure(var ex) -> System.err.println("Failed after 3 attempts: " + ex);
     * }
     * }</pre>
     */
    public static void exampleCrashRecoveryRetry() {
        Result<String, Exception> result = CrashRecovery.retry(3, () -> "result from attempt");

        switch (result) {
            case Result.Ok(var value) -> System.out.println("Success: " + value);
            case Result.Success(var value) -> System.out.println("Success: " + value);
            case Result.Err(var ex) -> System.err.println("Failed: " + ex);
            case Result.Failure(var ex) -> System.err.println("Failed: " + ex);
        }
    }

    /**
     * Example 8: Complete integration showing all factory patterns together.
     *
     * <p>Demonstrates a typical OTP-style application: supervisors manage processes, processes send
     * messages and handle crashes, state machines coordinate complex workflows, event managers
     * decouple producers and consumers.
     *
     * <pre>{@code
     * // 1. Create a supervisor hierarchy
     * var appSupervisor = Supervisor.create(
     *     "app",
     *     Supervisor.Strategy.ONE_FOR_ALL,
     *     5,
     *     Duration.ofSeconds(60)
     * );
     *
     * // 2. Spawn a worker process supervised by the app supervisor
     * var counter = appSupervisor.supervise(
     *     "counter",
     *     new Counter(0),
     *     (state, msg) -> { ... }
     * );
     *
     * // 3. Create an event manager
     * var events = EventManager.start();
     *
     * // 4. Run parallel tasks
     * var results = Parallel.all(List.of(
     *     () -> counter.ask(new Increment()).join().value(),
     *     () -> counter.ask(new Increment()).join().value()
     * ));
     *
     * // 5. Use crash recovery for transient failures
     * var success = CrashRecovery.retry(3, () -> {
     *     events.notify(new Event.Processing());
     *     return true;
     * });
     *
     * appSupervisor.shutdown();
     * }</pre>
     */
    public static void exampleCompleteIntegration() {
        record Counter(int value) {}

        interface CounterMsg {
            record Increment() implements CounterMsg {}
        }

        // 1. Create supervisor using factory
        Supervisor supervisor =
                Supervisor.create(
                        "app", Supervisor.Strategy.ONE_FOR_ALL, 5, Duration.ofSeconds(60));

        // 2. Supervise a worker process
        ProcRef<IntegrationCounter, IntegrationCounterMsg> counter =
                supervisor.supervise(
                        "counter",
                        new IntegrationCounter(0),
                        (state, msg) ->
                                switch (msg) {
                                    case CounterMsg.Increment _ -> new Counter(state.value() + 1);
                                    default -> state;
                                });

        // 3. Create event manager
        interface Event {
            record Processing() implements Event {}
        }

        EventManager<Event> events = EventManager.start();

        // 4. Run tasks in parallel
        var results = Parallel.all(List.of(() -> 1, () -> 2));

        // 5. Use crash recovery
        var recovered = CrashRecovery.retry(3, () -> true);
    }
}
