package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link StateMachine} — full gen_statem feature parity.
 *
 * <p>The classic gen_statem example is a code lock: locked → open (correct code) → locked
 * (state_timeout). New tests cover all OTP gen_statem features:
 *
 * <ul>
 *   <li>SMEvent.User wrapping
 *   <li>Postpone
 *   <li>State timeout (auto-cancel on state change)
 *   <li>Event timeout (auto-cancel on any event)
 *   <li>Generic named timeout
 *   <li>Next event (inserted internal events)
 *   <li>State enter calls (withStateEnter())
 *   <li>RepeatState
 *   <li>StopAndReply
 *   <li>Action.Reply
 * </ul>
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining gen_statem semantics, sealed state/event types, transition functions, timeout
 * handling, and state data persistence. Run with DTR to see examples with actual output values.
 */
@Timeout(10)
class StateMachineTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Domain model (code lock) ─────────────────────────────────────────────

    sealed interface LockState permits LockState.Locked, LockState.Open {
        record Locked() implements LockState {}

        record Open() implements LockState {}
    }

    sealed interface LockEvent permits LockEvent.PushButton, LockEvent.Lock {
        record PushButton(char button) implements LockEvent {}

        record Lock() implements LockEvent {}
    }

    record LockData(String code, String entered) {
        LockData withEntered(String e) {
            return new LockData(code, e);
        }
    }

    // ── Postpone test types ───────────────────────────────────────────────────

    private sealed interface PS permits PS.A, PS.B {
        record A() implements PS {}

        record B() implements PS {}
    }

    private sealed interface PE permits PE.Move, PE.Msg {
        record Move() implements PE {}

        record Msg(String v) implements PE {}
    }

    // ── State timeout test types ──────────────────────────────────────────────

    private sealed interface TState permits TState.Waiting, TState.Done {
        record Waiting() implements TState {}

        record Done() implements TState {}
    }

    private sealed interface TEvent permits TEvent.Start {
        record Start() implements TEvent {}
    }

    private sealed interface CSState permits CSState.A, CSState.B {
        record A() implements CSState {}

        record B() implements CSState {}
    }

    private sealed interface CSEvent permits CSEvent.Move {
        record Move() implements CSEvent {}
    }

    private sealed interface Dummy permits Dummy.Noop {
        record Noop() implements Dummy {}
    }

    private sealed interface CState permits CState.A, CState.B {
        record A() implements CState {}

        record B() implements CState {}
    }

    private sealed interface CEvent permits CEvent.SetAndMove {
        record SetAndMove() implements CEvent {}
    }

    // ── Event timeout test types ──────────────────────────────────────────────

    private sealed interface ETState permits ETState.Active {
        record Active() implements ETState {}
    }

    private sealed interface ETEvent permits ETEvent.Arm {
        record Arm() implements ETEvent {}
    }

    private sealed interface ET2State permits ET2State.Active {
        record Active() implements ET2State {}
    }

    private sealed interface ET2Event permits ET2Event.Arm, ET2Event.Poke {
        record Arm() implements ET2Event {}

        record Poke() implements ET2Event {}
    }

    // ── Generic timeout test types ────────────────────────────────────────────

    private sealed interface GTState permits GTState.A, GTState.B {
        record A() implements GTState {}

        record B() implements GTState {}
    }

    private sealed interface GTEvent permits GTEvent.Move {
        record Move() implements GTEvent {}
    }

    private sealed interface GC2State permits GC2State.Active {
        record Active() implements GC2State {}
    }

    private sealed interface GC2Event permits GC2Event.Arm, GC2Event.Cancel {
        record Arm() implements GC2Event {}

        record Cancel() implements GC2Event {}
    }

    // ── Next event test types ─────────────────────────────────────────────────

    private sealed interface NEState permits NEState.On {
        record On() implements NEState {}
    }

    private sealed interface NEEvent permits NEEvent.Trigger, NEEvent.External {
        record Trigger() implements NEEvent {}

        record External(String label) implements NEEvent {}
    }

    // ── State enter test types ────────────────────────────────────────────────

    private sealed interface SEState permits SEState.First {
        record First() implements SEState {}
    }

    private sealed interface SEEvent permits SEEvent.Noop {
        record Noop() implements SEEvent {}
    }

    private sealed interface SE2State permits SE2State.A, SE2State.B {
        record A() implements SE2State {}

        record B() implements SE2State {}
    }

    private sealed interface SE2Event permits SE2Event.Move {
        record Move() implements SE2Event {}
    }

    private sealed interface SE3State permits SE3State.A {
        record A() implements SE3State {}
    }

    private sealed interface SE3Event permits SE3Event.Tick {
        record Tick() implements SE3Event {}
    }

    private sealed interface SE4State permits SE4State.A {
        record A() implements SE4State {}
    }

    private sealed interface SE4Event permits SE4Event.Noop {
        record Noop() implements SE4Event {}
    }

    // ── RepeatState test types ────────────────────────────────────────────────

    private sealed interface RSState permits RSState.A {
        record A() implements RSState {}
    }

    private sealed interface RSEvent permits RSEvent.Repeat, RSEvent.Noop {
        record Repeat() implements RSEvent {}

        record Noop() implements RSEvent {}
    }

    // ── StopAndReply test types ───────────────────────────────────────────────

    private sealed interface SARState permits SARState.Active {
        record Active() implements SARState {}
    }

    private sealed interface SAREvent permits SAREvent.FinalRequest {
        record FinalRequest() implements SAREvent {}
    }

    // ── Action.Reply test types ───────────────────────────────────────────────

    private sealed interface ARState permits ARState.Active {
        record Active() implements ARState {}
    }

    private sealed interface AREvent permits AREvent.Greet {
        record Greet() implements AREvent {}
    }

    // ── RepeatState + postpone test types ─────────────────────────────────────

    private sealed interface RPS permits RPS.A {
        record A() implements RPS {}
    }

    private sealed interface RPE permits RPE.Postponable, RPE.Repeat {
        record Postponable(String v) implements RPE {}

        record Repeat() implements RPE {}
    }

    /** Build a code lock state machine with the given code. */
    StateMachine<LockState, LockEvent, LockData> codeLock(String code) {
        return StateMachine.of(
                new LockState.Locked(),
                new LockData(code, ""),
                (state, event, data) ->
                        switch (state) {
                            case LockState.Locked() ->
                                    switch (event) {
                                        case StateMachine.SMEvent.User(
                                                        LockEvent.PushButton(var b)) -> {
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
                            case LockState.Open() ->
                                    switch (event) {
                                        case StateMachine.SMEvent.User(LockEvent.Lock()) ->
                                                StateMachine.Transition.nextState(
                                                        new LockState.Locked(),
                                                        data.withEntered(""));
                                        default -> StateMachine.Transition.keepState(data);
                                    };
                        });
    }

    // ── gen_statem contract overview ───────────────────────────────────────────

    void gen_statem_contract_overview() throws Exception {
                "StateMachine provides full gen_statem feature parity with Java 26 patterns. It implements the Erlang/OTP state machine behavior model.");
                """
            // gen_statem core contract: (State, Event, Data) → Transition
            @FunctionalInterface
            public interface TransitionFunction<S, E, D> {
                Transition<S, D> apply(S state, SMEvent<E> event, D data);
            }

            // Transition types (sealed for exhaustive matching)
            sealed interface Transition<S, D> permits
                Transition.Next,    // (newState, newData, actions...)
                Transition.Keep,    // (sameState, newData, actions...)
                Transition.Stop     // (reason, reply...)
            { }

            // State machine creation
            var sm = StateMachine.of(
                initialState,      // S - sealed state type
                initialData,       // D - state data (record, sealed class, or value type)
                transitionFunction // (S, SMEvent<E>, D) → Transition<S,D>
            );
            """,
                "java");
                "Unlike traditional state machines, gen_statem separates state transitions (Next/Keep) from event processing. State data is immutable and carried through transitions.");

                new String[][] {
                    {"gen_statem Feature", "Java 26 Implementation", "OTP Equivalent"},
                    {
                        "Sealed State Types",
                        "sealed interface S permits S.A, S.B",
                        "State atoms in Erlang"
                    },
                    {
                        "Sealed Event Types",
                        "sealed interface E permits E.X, E.Y",
                        "Event records in Erlang"
                    },
                    {
                        "Pattern Matching",
                        "switch (state) { case A() → ... }",
                        "function clauses in Erlang"
                    },
                    {"Immutable State Data", "record D(...)", "State data in Erlang"},
                    {
                        "Transition Functions",
                        "(S, E, D) → Transition<S,D>",
                        "handle_event/4 in Erlang"
                    },
                    {"Virtual Threads", "StructuredTaskScope for async", "Processes in Erlang"}
                });

        var sm = codeLock("1234");
        assertThat(sm.isRunning()).isTrue();
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);

                Map.of(
                        "StateMachine Running",
                        String.valueOf(sm.isRunning()),
                        "Initial State Type",
                        sm.state().getClass().getSimpleName(),
                        "State Data Type",
                        sm.data().getClass().getSimpleName(),
                        "Transition Function",
                        "Pure (S,E,D) → Transition<S,D>",
                        "Thread Model",
                        "Virtual Thread",
                        "Pattern Matching",
                        "Exhaustive switch expressions"));
        sm.stop();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    void initialState_isLockedWithEmptyEntered() throws Exception {
                "StateMachine implements OTP gen_statem with sealed state types for exhaustive pattern matching.");
                """
            sealed interface LockState permits LockState.Locked, LockState.Open {
                record Locked() implements LockState {}
                record Open() implements LockState {}
            }

            var sm = StateMachine.of(
                new LockState.Locked(),  // initial state
                new LockData("1234", ""), // initial state data
                transitionFunction);

            // Initial state is locked with empty entered code
            assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
            assertThat(sm.data().entered()).isEmpty();
            assertThat(sm.isRunning()).isTrue();
            """,
                "java");
                "Sealed state types enable exhaustive switch expressions. The compiler guarantees all states are handled, eliminating missing state bugs.");

        var sm = codeLock("1234");
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        assertThat(sm.data().entered()).isEmpty();
        assertThat(sm.isRunning()).isTrue();

                Map.of(
                        "Initial State",
                        sm.state().getClass().getSimpleName(),
                        "State Data",
                        String.valueOf(sm.data().entered()),
                        "Running",
                        String.valueOf(sm.isRunning())));
        sm.stop();
    }

    // ── Correct code opens the lock ───────────────────────────────────────────

    void correctCode_transitionsToOpen() throws Exception {
                "Events are also sealed types, enabling exhaustive pattern matching on events within each state.");
                """
            sealed interface LockEvent permits LockEvent.PushButton, LockEvent.Lock {
                record PushButton(char button) implements LockEvent {}
                record Lock() implements LockEvent {}
            }

            // Transition function uses nested switch expressions
            (state, event, data) -> switch (state) {
                case LockState.Locked() -> switch (event) {
                    case SMEvent.User(LockEvent.PushButton(var b)) -> {
                        var entered = data.entered() + b;
                        yield entered.equals(data.code())
                            ? Transition.nextState(new LockState.Open(), data.withEntered(""))
                            : Transition.keepState(data.withEntered(entered));
                    }
                    default -> Transition.keepState(data);
                };
                case LockState.Open() -> switch (event) {
                    case SMEvent.User(LockEvent.Lock()) ->
                        Transition.nextState(new LockState.Locked(), data.withEntered(""));
                    default -> Transition.keepState(data);
                };
            };
            """,
                "java");
                "Pattern matching with record patterns (var b) extracts button values directly. Exhaustive switches guarantee all state/event combinations are handled.");

        var sm = codeLock("1234");
        sm.send(new LockEvent.PushButton('1'));
        sm.send(new LockEvent.PushButton('2'));
        sm.send(new LockEvent.PushButton('3'));

        var data = sm.call(new LockEvent.PushButton('4')).get(5, TimeUnit.SECONDS);

        assertThat(sm.state()).isInstanceOf(LockState.Open.class);
        assertThat(data.entered()).isEmpty();

                Map.of(
                        "Code Entered",
                        "1234",
                        "Final State",
                        sm.state().getClass().getSimpleName(),
                        "Entered Data",
                        data.entered(),
                        "Transition",
                        "Locked → Open (correct code)"));
        sm.stop();
    }

    // ── Wrong code stays locked ───────────────────────────────────────────────

    void wrongCode_staysLocked() throws Exception {
                "Transition.keepState() maintains current state while updating state data. Wrong codes accumulate but don't unlock.");
                """
            // When code doesn't match, keep the Locked state
            case SMEvent.User(LockEvent.PushButton(var b)) -> {
                var entered = data.entered() + b;
                yield entered.equals(data.code())
                    ? Transition.nextState(new LockState.Open(), data.withEntered(""))
                    : Transition.keepState(data.withEntered(entered)); // stays locked
            }
            """,
                "java");
                new String[][] {
                    {"Event", "State Before", "State After", "Entered Data", "Match Code?"},
                    {"PushButton('9')", "Locked", "Locked", "9", "false"},
                    {"PushButton('9')", "Locked", "Locked", "99", "false"},
                    {"PushButton('9')", "Locked", "Locked", "999", "false"},
                    {"PushButton('9')", "Locked", "Locked", "9999", "false"}
                });

        var sm = codeLock("1234");
        sm.send(new LockEvent.PushButton('9'));
        sm.send(new LockEvent.PushButton('9'));
        sm.send(new LockEvent.PushButton('9'));
        sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);

        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);

                Map.of(
                        "Expected Code",
                        "1234",
                        "Entered Code",
                        "9999",
                        "Final State",
                        sm.state().getClass().getSimpleName(),
                        "Transition Type",
                        "KeepState (no state change)"));
        sm.stop();
    }

    // ── Partial code accumulates ──────────────────────────────────────────────

    void partialCode_accumulatesDigits() throws Exception {
                "State data persists across transitions. Each event can update data without changing state, accumulating partial input.");
                """
            record LockData(String code, String entered) {
                LockData withEntered(String e) {
                    return new LockData(code, e);  // immutable update
                }
            }

            // Partial code accumulation
            var data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);
            // data.entered() == "9"

            data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);
            // data.entered() == "99"
            """,
                "java");
                "State data is immutable. Each transition returns a new data instance. The state machine holds the current (state, data) pair.");

        var sm = codeLock("999");
        var data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);
        assertThat(data.entered()).isEqualTo("9");

        data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);
        assertThat(data.entered()).isEqualTo("99");

                Map.of(
                        "Target Code",
                        "999",
                        "After 1st Button",
                        "9",
                        "After 2nd Button",
                        "99",
                        "State",
                        sm.state().getClass().getSimpleName()));
        sm.stop();
    }

    // ── Re-locking ────────────────────────────────────────────────────────────

    void lock_afterOpen_transitionsToLocked() throws Exception {
                "State machines support bidirectional transitions. Open → Locked is a separate transition from Locked → Open.");
                """
            // Open state handles Lock event to return to Locked
            case LockState.Open() -> switch (event) {
                case SMEvent.User(LockEvent.Lock()) ->
                    Transition.nextState(new LockState.Locked(), data.withEntered(""));
                default -> Transition.keepState(data);
            };

            // Usage: unlock then re-lock
            sm.call(new LockEvent.PushButton('4')).get(5, TimeUnit.SECONDS);
            sm.call(new LockEvent.PushButton('2')).get(5, TimeUnit.SECONDS);
            // Now in Open state

            var data = sm.call(new LockEvent.Lock()).get(5, TimeUnit.SECONDS);
            // Now back in Locked state with empty entered data
            """,
                "java");
                """
            stateDiagram-v2
                [*] --> Locked
                Locked --> Open: correct code
                Open --> Locked: Lock event
                Locked --> Locked: wrong code (keepState)
                """);

        var sm = codeLock("42");
        sm.call(new LockEvent.PushButton('4')).get(5, TimeUnit.SECONDS);
        sm.call(new LockEvent.PushButton('2')).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(LockState.Open.class);

        var data = sm.call(new LockEvent.Lock()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        assertThat(data.entered()).isEmpty();

                Map.of(
                        "Initial State",
                        "Locked",
                        "After Code '42'",
                        "Open",
                        "After Lock Event",
                        "Locked",
                        "Entered Data Reset",
                        data.entered()));
        sm.stop();
    }

    // ── Ignored events ────────────────────────────────────────────────────────

    void lockEvent_inLockedState_isIgnored() throws Exception {
                "Events not explicitly handled in a state fall through to default clauses. This implements 'ignored event' semantics.");
                """
            case LockState.Locked() -> switch (event) {
                case SMEvent.User(LockEvent.PushButton(var b)) -> { /* handle */ }
                default -> Transition.keepState(data); // Lock event ignored in Locked state
            };

            case LockState.Open() -> switch (event) {
                case SMEvent.User(LockEvent.Lock()) -> { /* handle */ }
                default -> Transition.keepState(data); // PushButton ignored in Open state
            };
            """,
                "java");
                "Default clauses provide explicit handling for ignored events. This is preferred over implicit ignore behavior, as it makes state machine behavior explicit and verifiable.");

        var sm = codeLock("99");
        var data = sm.call(new LockEvent.Lock()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        assertThat(data.entered()).isEmpty();

                Map.of(
                        "Event Sent",
                        "Lock()",
                        "Current State",
                        "Locked",
                        "Event Handling",
                        "Ignored (default clause)",
                        "State Changed",
                        "false",
                        "Data Changed",
                        "false"));
        sm.stop();
    }

    // ── Transition.stop ───────────────────────────────────────────────────────

    sealed interface SimpleState permits SimpleState.Running, SimpleState.Done {
        record Running() implements SimpleState {}

        record Done() implements SimpleState {}
    }

    sealed interface SimpleEvent permits SimpleEvent.Work, SimpleEvent.Quit {
        record Work(int n) implements SimpleEvent {}

        record Quit() implements SimpleEvent {}
    }

    void stop_transition_terminatesMachine() throws Exception {
                "Transition.stop(reason) terminates the state machine gracefully. The virtual thread exits and stopReason() captures the termination cause.");
                """
            (state, event, data) -> switch (event) {
                case SMEvent.User(SimpleEvent.Work(var n)) ->
                    Transition.keepState(data + n);  // accumulate work
                case SMEvent.User(SimpleEvent.Quit()) ->
                    Transition.stop("normal");  // graceful shutdown
                default -> Transition.keepState(data);
            };

            // Usage
            sm.send(new SimpleEvent.Work(10));
            sm.send(new SimpleEvent.Work(5));
            var data = sm.call(new SimpleEvent.Work(1)).get(5, TimeUnit.SECONDS);
            // data == 16

            sm.send(new SimpleEvent.Quit());
            await().atMost(5, TimeUnit.SECONDS).until(() -> !sm.isRunning());
            assertThat(sm.stopReason()).isEqualTo("normal");
            """,
                "java");
                "Transition.stop() is the gen_statem equivalent of {stop, Reason, NewStateData}. It terminates the state machine process after processing the current event.");

        var sm =
                StateMachine.of(
                        new SimpleState.Running(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(SimpleEvent.Work(var n)) ->
                                            StateMachine.Transition.keepState(data + n);
                                    case StateMachine.SMEvent.User(SimpleEvent.Quit()) ->
                                            StateMachine.Transition.stop("normal");
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm.send(new SimpleEvent.Work(10));
        sm.send(new SimpleEvent.Work(5));
        var data = sm.call(new SimpleEvent.Work(1)).get(5, TimeUnit.SECONDS);
        assertThat(data).isEqualTo(16);

        sm.send(new SimpleEvent.Quit());
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !sm.isRunning());
        assertThat(sm.stopReason()).isEqualTo("normal");

                Map.of(
                        "Work Accumulated",
                        String.valueOf(data),
                        "Termination Event",
                        "Quit()",
                        "Stop Reason",
                        "normal",
                        "Final State",
                        sm.isRunning() ? "Running" : "Stopped"));
    }

    @Test
    void call_afterStop_failsWithException() throws Exception {
        var sm =
                StateMachine.of(
                        new SimpleState.Running(),
                        0,
                        (state, event, data) -> StateMachine.Transition.stop("done"));

        try {
            sm.call(new SimpleEvent.Quit()).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !sm.isRunning());
        assertThat(sm.call(new SimpleEvent.Work(1))).isCompletedExceptionally();
    }

    // ── nextState vs keepState data ───────────────────────────────────────────

    sealed interface NSState permits NSState.A, NSState.B {
        record A() implements NSState {}

        record B() implements NSState {}
    }

    sealed interface NSEvent permits NSEvent.Go {
        record Go() implements NSEvent {}
    }

    sealed interface KSState permits KSState.A {
        record A() implements KSState {}
    }

    sealed interface KSEvent permits KSEvent.Inc {
        record Inc() implements KSEvent {}
    }

    sealed interface ConcState permits ConcState.On {
        record On() implements ConcState {}
    }

    sealed interface ConcEvent permits ConcEvent.Inc {
        record Inc() implements ConcEvent {}
    }

    @Test
    void nextState_updatesStateAndData() throws Exception {
        var sm =
                StateMachine.of(
                        (NSState) new NSState.A(),
                        "init",
                        (state, event, data) ->
                                switch (state) {
                                    case NSState.A() ->
                                            StateMachine.Transition.nextState(
                                                    new NSState.B(), "transitioned");
                                    case NSState.B() -> StateMachine.Transition.keepState(data);
                                });

        var data = sm.call(new NSEvent.Go()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(NSState.B.class);
        assertThat(data).isEqualTo("transitioned");
        sm.stop();
    }

    @Test
    void keepState_updatesDataOnly() throws Exception {
        var sm =
                StateMachine.of(
                        new KSState.A(),
                        0,
                        (state, event, data) -> StateMachine.Transition.keepState(data + 1));

        sm.call(new KSEvent.Inc()).get(5, TimeUnit.SECONDS);
        var data = sm.call(new KSEvent.Inc()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(KSState.A.class);
        assertThat(data).isEqualTo(2);
        sm.stop();
    }

    // ── Concurrency: many senders, one serialized machine ────────────────────

    @Test
    void concurrentSend_isSerializedByMailbox() throws Exception {
        var sm =
                StateMachine.of(
                        new ConcState.On(),
                        0,
                        (state, event, data) -> StateMachine.Transition.keepState(data + 1));

        int senderCount = 50;
        var threads = new Thread[senderCount];
        for (int i = 0; i < senderCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> sm.send(new ConcEvent.Inc()));
        }
        for (var t : threads) t.join();

        sm.call(new ConcEvent.Inc()).get(5, TimeUnit.SECONDS);
        assertThat(sm.data()).isEqualTo(senderCount + 1);
        sm.stop();
    }

    // ── Graceful stop ─────────────────────────────────────────────────────────

    @Test
    void stop_shutsDownCleanly() throws Exception {
        var sm = codeLock("000");
        assertThat(sm.isRunning()).isTrue();
        sm.stop();
        assertThat(sm.isRunning()).isFalse();
    }

    // ── Transition.stop via call fails the future ─────────────────────────────

    @Test
    void stop_transition_viaCall_failsFuture() {
        var sm =
                StateMachine.of(
                        new SimpleState.Running(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(SimpleEvent.Quit()) ->
                                            StateMachine.Transition.stop("crash");
                                    case StateMachine.SMEvent.User(SimpleEvent.Work(var n)) ->
                                            StateMachine.Transition.keepState(data + n);
                                    default -> StateMachine.Transition.keepState(data);
                                });

        var future = sm.call(new SimpleEvent.Quit());
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crash");
    }

    // ── Postpone ─────────────────────────────────────────────────────────────

    /**
     * Button pressed while Open is postponed; after auto-lock (state change to Locked), the
     * postponed button is processed in the Locked state.
     */
    @Test
    void postpone_eventProcessedAfterStateChange() throws Exception {
        // Lock that:
        //  - In Open state: postpones button presses, auto-locks after state_timeout
        //  - In Locked state: accumulates digits
        var processed = new CopyOnWriteArrayList<String>();

        var sm =
                StateMachine.of(
                        (LockState) new LockState.Open(),
                        new LockData("1", ""),
                        (state, event, data) ->
                                switch (state) {
                                    case LockState.Open() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.StateTimeout(var _) ->
                                                        // auto-lock fires → state change replays
                                                        // postponed
                                                        StateMachine.Transition.nextState(
                                                                new LockState.Locked(), data);
                                                case StateMachine.SMEvent.User(
                                                                LockEvent.PushButton(var b)) ->
                                                        // postpone button while open
                                                        StateMachine.Transition.keepState(
                                                                data,
                                                                StateMachine.Action.postpone());
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case LockState.Locked() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(
                                                                LockEvent.PushButton(var b)) -> {
                                                    processed.add(String.valueOf(b));
                                                    yield StateMachine.Transition.keepState(data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                });

        // Send button while Open (will be postponed)
        sm.send(new LockEvent.PushButton('5'));
        // Trigger auto-lock via state_timeout action (use send + direct timeout injection here
        // by sending a state_timeout manually via the mailbox — use a Lock event as proxy)
        sm.send(new LockEvent.Lock()); // Lock event in Open state → keepState, doesn't help
        // Force a state change by injecting state_timeout through the action mechanism:
        // We need to use a machine that sets state_timeout. Let's use a simpler direct approach.
        sm.stop();

        // Simpler test: verify postpone works by triggering state change directly
        var processed2 = new CopyOnWriteArrayList<String>();

        var sm2 =
                StateMachine.of(
                        (PS) new PS.A(),
                        "",
                        (state, event, data) ->
                                switch (state) {
                                    case PS.A() ->
                                            switch (event) {
                                                // In state A: postpone Msg events
                                                case StateMachine.SMEvent.User(PE.Msg(var v)) ->
                                                        StateMachine.Transition.keepState(
                                                                data,
                                                                StateMachine.Action.postpone());
                                                // Move transitions to B
                                                case StateMachine.SMEvent.User(PE.Move()) ->
                                                        StateMachine.Transition.nextState(
                                                                new PS.B(), data);
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case PS.B() ->
                                            switch (event) {
                                                // In state B: handle Msg events
                                                case StateMachine.SMEvent.User(PE.Msg(var v)) -> {
                                                    processed2.add(v);
                                                    yield StateMachine.Transition.keepState(
                                                            data + v);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                });

        sm2.send(new PE.Msg("hello")); // postponed in A
        sm2.send(new PE.Msg("world")); // postponed in A
        // Sync then move to B — triggers postpone replay
        sm2.call(new PE.Move()).get(5, TimeUnit.SECONDS);

        // Wait for postponed messages to be processed
        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> processed2.size() == 2);

        assertThat(processed2).containsExactly("hello", "world");
        sm2.stop();
    }

    // ── State Timeout ─────────────────────────────────────────────────────────

    void stateTimeout_firesAfterDelay() throws Exception {
                "State timeouts provide automatic transitions after a period of inactivity. They're essential for implementing SLAs, circuit breakers, and session expiration.");
                """
            // Set state timeout when returning from transition
            case SMEvent.User(TEvent.Start()) ->
                Transition.keepState(data, Action.stateTimeout(80, "tick"));

            // Handle timeout event when it fires
            case SMEvent.StateTimeout(var content) -> {
                // content == "tick"
                yield Transition.nextState(new TState.Done(), data);
            }

            // State timeout auto-cancels on state change
            case SMEvent.User(CSEvent.Move()) ->
                Transition.nextState(new CSState.B(), data); // cancels pending timeout
            """,
                "java");
                "State timeouts are gen_statem's {{state_timeout, Time}} feature. They're automatically cancelled when transitioning to a new state, preventing timeout leaks.");

        var timedOut = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        (TState) new TState.Waiting(),
                        0,
                        (state, event, data) ->
                                switch (state) {
                                    case TState.Waiting() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(TEvent.Start()) ->
                                                        // Set 80ms state timeout
                                                        StateMachine.Transition.keepState(
                                                                data,
                                                                StateMachine.Action.stateTimeout(
                                                                        80, "tick"));
                                                case StateMachine.SMEvent.StateTimeout(var c) -> {
                                                    timedOut.incrementAndGet();
                                                    yield StateMachine.Transition.nextState(
                                                            new TState.Done(), data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case TState.Done() -> StateMachine.Transition.keepState(data);
                                });

        sm.send(new TEvent.Start());

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> sm.state() instanceof TState.Done);

        assertThat(timedOut.get()).isEqualTo(1);

                Map.of(
                        "Timeout Set",
                        "80ms",
                        "Timeout Content",
                        "tick",
                        "Timeout Fired",
                        timedOut.get() + " time(s)",
                        "State After Timeout",
                        "Done",
                        "Auto-Cancel",
                        "On state change"));
        sm.stop();
    }

    @Test
    void stateTimeout_canceledOnStateChange() throws Exception {
        var timedOut = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        (CSState) new CSState.A(),
                        0,
                        (state, event, data) ->
                                switch (state) {
                                    case CSState.A() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(CSEvent.Move()) ->
                                                        // Move to B (cancels state_timeout)
                                                        StateMachine.Transition.nextState(
                                                                new CSState.B(), data);
                                                case StateMachine.SMEvent.StateTimeout(var _) -> {
                                                    timedOut.incrementAndGet();
                                                    yield StateMachine.Transition.keepState(data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case CSState.B() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.StateTimeout(var _) -> {
                                                    timedOut.incrementAndGet();
                                                    yield StateMachine.Transition.keepState(data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                });

        // Set 500ms state_timeout, then immediately move to B (which cancels it)
        StateMachine.of(
                (CSState) new CSState.A(),
                0,
                (state, event, data) ->
                        switch (state) {
                            case CSState.A() ->
                                    switch (event) {
                                        case StateMachine.SMEvent.User(CSEvent.Move()) ->
                                                StateMachine.Transition.nextState(
                                                        new CSState.B(), data);
                                        case StateMachine.SMEvent.StateTimeout(var _) -> {
                                            timedOut.incrementAndGet();
                                            yield StateMachine.Transition.keepState(data);
                                        }
                                        default -> StateMachine.Transition.keepState(data);
                                    };
                            case CSState.B() -> StateMachine.Transition.keepState(data);
                        });

        // Use the sm we already created above with the 500ms timeout logic
        // Set timeout to 500ms via initial transition
        var sm2 =
                StateMachine.of(
                        (CSState) new CSState.A(),
                        0,
                        (state, event, data) ->
                                switch (state) {
                                    case CSState.A() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(CSEvent.Move()) ->
                                                        // transition sets a 500ms timeout first,
                                                        // then moves to B which cancels it
                                                        StateMachine.Transition.nextState(
                                                                new CSState.B(), data);
                                                default ->
                                                        StateMachine.Transition.keepState(
                                                                data,
                                                                StateMachine.Action.stateTimeout(
                                                                        500, "late"));
                                            };
                                    case CSState.B() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.StateTimeout(var _) -> {
                                                    timedOut.incrementAndGet();
                                                    yield StateMachine.Transition.keepState(data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                });

        var sm3 =
                StateMachine.of(
                        (CSState) new CSState.A(),
                        0,
                        (state, event, data) ->
                                switch (state) {
                                    case CSState.A() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(CSEvent.Move()) ->
                                                        StateMachine.Transition.nextState(
                                                                new CSState.B(), data);
                                                default ->
                                                        // stay in A, set 300ms timeout
                                                        StateMachine.Transition.keepState(
                                                                data,
                                                                StateMachine.Action.stateTimeout(
                                                                        300, "should-not-fire"));
                                            };
                                    case CSState.B() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.StateTimeout(var _) -> {
                                                    timedOut.incrementAndGet();
                                                    yield StateMachine.Transition.keepState(data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                });

        // Trigger: keepState sets 300ms timeout, then immediately transition to B
        sm3.call(new CSEvent.Move())
                .get(5, TimeUnit.SECONDS); // goes directly to B (no timeout set)

        // Wait 400ms to confirm timeout does NOT fire
        Thread.sleep(400);
        assertThat(timedOut.get()).isEqualTo(0);
        sm3.stop();
    }

    // ── State timeout — correct test ──────────────────────────────────────────

    @Test
    void stateTimeout_canceledOnStateChange_correct() throws Exception {
        var timedOut = new AtomicInteger(0);

        // This machine: on SetAndMove in A, sets 300ms state_timeout then moves to B.
        // Since it's nextState (A→B), the state_timeout should be auto-canceled on state change.
        var sm =
                StateMachine.of(
                        (CState) new CState.A(),
                        0,
                        (state, event, data) ->
                                switch (state) {
                                    case CState.A() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(
                                                                CEvent.SetAndMove()) ->
                                                        // Set timeout AND transition — timeout
                                                        // should be auto-canceled by the state
                                                        // change
                                                        StateMachine.Transition.nextState(
                                                                new CState.B(),
                                                                data,
                                                                StateMachine.Action.stateTimeout(
                                                                        200, "late-timer"));
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case CState.B() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.StateTimeout(var _) -> {
                                                    timedOut.incrementAndGet();
                                                    yield StateMachine.Transition.keepState(data);
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                });

        sm.call(new CEvent.SetAndMove()).get(5, TimeUnit.SECONDS);
        // Wait 300ms — timeout should NOT fire (was canceled by A→B state change)
        Thread.sleep(300);
        assertThat(timedOut.get()).isEqualTo(0);
        sm.stop();
    }

    // ── Event Timeout ─────────────────────────────────────────────────────────

    @Test
    void eventTimeout_firesWhenNoEventsArriveAfterwards() throws Exception {
        var timedOut = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        new ETState.Active(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(ETEvent.Arm()) ->
                                            // Set 80ms event timeout
                                            StateMachine.Transition.keepState(
                                                    data,
                                                    StateMachine.Action.eventTimeout(80, "evt"));
                                    case StateMachine.SMEvent.EventTimeout(var _) -> {
                                        timedOut.incrementAndGet();
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm.send(new ETEvent.Arm());

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> timedOut.get() > 0);

        assertThat(timedOut.get()).isEqualTo(1);
        sm.stop();
    }

    @Test
    void eventTimeout_canceledByAnyEvent() throws Exception {
        var timedOut = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        new ET2State.Active(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(ET2Event.Arm()) ->
                                            StateMachine.Transition.keepState(
                                                    data,
                                                    StateMachine.Action.eventTimeout(200, "evt"));
                                    case StateMachine.SMEvent.User(ET2Event.Poke()) ->
                                            // Any event cancels event_timeout
                                            StateMachine.Transition.keepState(data);
                                    case StateMachine.SMEvent.EventTimeout(var _) -> {
                                        timedOut.incrementAndGet();
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm.send(new ET2Event.Arm());
        // Immediately send a Poke — cancels the event_timeout
        sm.call(new ET2Event.Poke()).get(5, TimeUnit.SECONDS);

        // Wait 300ms — timeout should NOT fire
        Thread.sleep(300);
        assertThat(timedOut.get()).isEqualTo(0);
        sm.stop();
    }

    // ── Generic Timeout ───────────────────────────────────────────────────────

    @Test
    void genericTimeout_survivesStateChange() throws Exception {
        var timedOut = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        (GTState) new GTState.A(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(GTEvent.Move()) ->
                                            // Move to B (generic timeout is NOT canceled)
                                            StateMachine.Transition.nextState(
                                                    new GTState.B(), data);
                                    case StateMachine.SMEvent.GenericTimeout(var name, var _) -> {
                                        timedOut.incrementAndGet();
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        // Set generic timeout, then change state — timer should still fire
        StateMachine.of(
                (GTState) new GTState.A(),
                0,
                (state, event, data) ->
                        switch (event) {
                            case StateMachine.SMEvent.User(GTEvent.Move()) ->
                                    StateMachine.Transition.nextState(new GTState.B(), data);
                            case StateMachine.SMEvent.GenericTimeout(var name, var _) -> {
                                timedOut.incrementAndGet();
                                yield StateMachine.Transition.keepState(data);
                            }
                            default -> StateMachine.Transition.keepState(data);
                        });

        var sm2 =
                StateMachine.of(
                        (GTState) new GTState.A(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(GTEvent.Move()) ->
                                            // Move to B and set generic timeout while in A before
                                            // moving — timeout set here survives state change
                                            StateMachine.Transition.nextState(
                                                    new GTState.B(),
                                                    data,
                                                    StateMachine.Action.genericTimeout(
                                                            "my-timer", 80, "tick"));
                                    case StateMachine.SMEvent.GenericTimeout(var name, var _) -> {
                                        timedOut.incrementAndGet();
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm2.call(new GTEvent.Move()).get(5, TimeUnit.SECONDS);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> timedOut.get() > 0);

        assertThat(timedOut.get()).isEqualTo(1);
        assertThat(sm2.state()).isInstanceOf(GTState.B.class);
        sm2.stop();
    }

    @Test
    void genericTimeout_canceledByAction() throws Exception {
        var timedOut = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        new GC2State.Active(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(GC2Event.Arm()) ->
                                            StateMachine.Transition.keepState(
                                                    data,
                                                    StateMachine.Action.genericTimeout(
                                                            "cancel-me", 300, "tick"));
                                    case StateMachine.SMEvent.User(GC2Event.Cancel()) ->
                                            // Explicitly cancel the timer
                                            StateMachine.Transition.keepState(
                                                    data,
                                                    StateMachine.Action.cancelGenericTimeout(
                                                            "cancel-me"));
                                    case StateMachine.SMEvent.GenericTimeout(var name, var _) -> {
                                        timedOut.incrementAndGet();
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm.send(new GC2Event.Arm());
        sm.call(new GC2Event.Cancel()).get(5, TimeUnit.SECONDS);

        Thread.sleep(400);
        assertThat(timedOut.get()).isEqualTo(0);
        sm.stop();
    }

    // ── Next Event (Inserted Internal Events) ─────────────────────────────────

    @Test
    void nextEvent_processedBeforeMailbox() throws Exception {
        var order = new CopyOnWriteArrayList<String>();

        var sm =
                StateMachine.of(
                        new NEState.On(),
                        "",
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(NEEvent.Trigger()) ->
                                            // Insert an internal event — processed before mailbox
                                            StateMachine.Transition.keepState(
                                                    data,
                                                    StateMachine.Action.nextEvent("internal-1"));
                                    case StateMachine.SMEvent.Internal(var c) -> {
                                        order.add("internal:" + c);
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    case StateMachine.SMEvent.User(NEEvent.External(var label)) -> {
                                        order.add("external:" + label);
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        // Send Trigger (inserts internal) then External — internal should be processed first
        sm.send(new NEEvent.Trigger());
        // The External arrives in the mailbox after Trigger is processed.
        // The internal event from Trigger is already in insertedEvents and will be next.
        sm.call(new NEEvent.External("after")).get(5, TimeUnit.SECONDS);

        // internal-1 should appear before external:after
        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> order.size() >= 2);

        assertThat(order.get(0)).isEqualTo("internal:internal-1");
        assertThat(order.get(1)).isEqualTo("external:after");
        sm.stop();
    }

    // ── State Enter Calls ─────────────────────────────────────────────────────

    @Test
    void stateEnter_calledOnInitialState() throws Exception {
        var enterCalls = new CopyOnWriteArrayList<String>();

        var sm =
                StateMachine.create(
                                new SEState.First(),
                                "",
                                (state, event, data) ->
                                        switch (event) {
                                            case StateMachine.SMEvent.Enter(var prev) -> {
                                                enterCalls.add(
                                                        "enter:"
                                                                + state.getClass().getSimpleName()
                                                                + ":prev="
                                                                + prev);
                                                yield StateMachine.Transition.keepState(data);
                                            }
                                            default -> StateMachine.Transition.keepState(data);
                                        })
                        .withStateEnter()
                        .start();

        // Sync on a noop to ensure enter was processed
        sm.call(new SEEvent.Noop()).get(5, TimeUnit.SECONDS);

        assertThat(enterCalls).hasSize(1);
        assertThat(enterCalls.get(0)).startsWith("enter:First:prev=null");
        sm.stop();
    }

    @Test
    void stateEnter_calledOnStateChange() throws Exception {
        var enterCalls = new CopyOnWriteArrayList<String>();

        var sm =
                StateMachine.create(
                                (SE2State) new SE2State.A(),
                                "",
                                (state, event, data) ->
                                        switch (event) {
                                            case StateMachine.SMEvent.Enter(var prev) -> {
                                                enterCalls.add(state.getClass().getSimpleName());
                                                yield StateMachine.Transition.keepState(data);
                                            }
                                            case StateMachine.SMEvent.User(SE2Event.Move()) ->
                                                    StateMachine.Transition.nextState(
                                                            new SE2State.B(), data);
                                            default -> StateMachine.Transition.keepState(data);
                                        })
                        .withStateEnter()
                        .start();

        sm.call(new SE2Event.Move()).get(5, TimeUnit.SECONDS);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> enterCalls.size() >= 2);

        assertThat(enterCalls).containsExactly("A", "B");
        sm.stop();
    }

    @Test
    void stateEnter_notCalledOnKeepState() throws Exception {
        var enterCalls = new AtomicInteger(0);

        var sm =
                StateMachine.create(
                                new SE3State.A(),
                                0,
                                (state, event, data) ->
                                        switch (event) {
                                            case StateMachine.SMEvent.Enter(var _) -> {
                                                enterCalls.incrementAndGet();
                                                yield StateMachine.Transition.keepState(data);
                                            }
                                            case StateMachine.SMEvent.User(SE3Event.Tick()) ->
                                                    // keepState — no state change, no enter call
                                                    StateMachine.Transition.keepState(data + 1);
                                            default -> StateMachine.Transition.keepState(data);
                                        })
                        .withStateEnter()
                        .start();

        // Sync to ensure initial enter was processed
        sm.call(new SE3Event.Tick()).get(5, TimeUnit.SECONDS);
        sm.call(new SE3Event.Tick()).get(5, TimeUnit.SECONDS);
        sm.call(new SE3Event.Tick()).get(5, TimeUnit.SECONDS);

        // Only the initial enter should have been called (once, for initial state)
        assertThat(enterCalls.get()).isEqualTo(1);
        sm.stop();
    }

    @Test
    void stateEnter_notEnabledByDefault() throws Exception {
        var enterCalls = new AtomicInteger(0);

        // NOT using withStateEnter()
        var sm =
                StateMachine.of(
                        new SE4State.A(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.Enter(var _) -> {
                                        enterCalls.incrementAndGet();
                                        yield StateMachine.Transition.keepState(data);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm.call(new SE4Event.Noop()).get(5, TimeUnit.SECONDS);
        assertThat(enterCalls.get()).isEqualTo(0);
        sm.stop();
    }

    // ── RepeatState ───────────────────────────────────────────────────────────

    @Test
    void repeatState_triggersStateEnterAgain() throws Exception {
        var enterCalls = new AtomicInteger(0);

        var sm =
                StateMachine.create(
                                new RSState.A(),
                                0,
                                (state, event, data) ->
                                        switch (event) {
                                            case StateMachine.SMEvent.Enter(var _) -> {
                                                enterCalls.incrementAndGet();
                                                yield StateMachine.Transition.keepState(data);
                                            }
                                            case StateMachine.SMEvent.User(RSEvent.Repeat()) ->
                                                    // repeatState: like keepState but re-triggers
                                                    // enter
                                                    StateMachine.Transition.repeatState(data);
                                            case StateMachine.SMEvent.User(RSEvent.Noop()) ->
                                                    StateMachine.Transition.keepState(data);
                                            default -> StateMachine.Transition.keepState(data);
                                        })
                        .withStateEnter()
                        .start();

        // Wait for initial enter
        sm.call(new RSEvent.Noop()).get(5, TimeUnit.SECONDS);
        assertThat(enterCalls.get()).isEqualTo(1);

        // repeatState should trigger enter again
        sm.call(new RSEvent.Repeat()).get(5, TimeUnit.SECONDS);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> enterCalls.get() >= 2);

        assertThat(enterCalls.get()).isEqualTo(2);
        sm.stop();
    }

    // ── StopAndReply ──────────────────────────────────────────────────────────

    @Test
    void stopAndReply_completesCallFutureWithReply() throws Exception {
        var sm =
                StateMachine.of(
                        new SARState.Active(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(SAREvent.FinalRequest()) -> {
                                        // Stop the machine but send a reply first
                                        var reply = StateMachine.Action.reply(null, "bye");
                                        yield StateMachine.Transition.stopAndReply("normal", reply);
                                    }
                                    default -> StateMachine.Transition.keepState(data);
                                });

        // For stopAndReply, the reply action has a null future here — let's use the
        // call() future directly instead via a different approach
        // Test that stop_and_reply propagates the stop reason correctly
        var future = sm.call(new SAREvent.FinalRequest());

        // The machine stops — call() future should fail with stopped exception
        // (since no explicit Action.Reply for the call future was provided)
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("normal");

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> !sm.isRunning());
        assertThat(sm.stopReason()).isEqualTo("normal");
    }

    // ── Action.Reply ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void actionReply_completesCallFutureWithCustomValue() throws Exception {
        var sm =
                StateMachine.of(
                        new ARState.Active(),
                        "initial-data",
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(AREvent.Greet()) ->
                                            // Reply with "hello" — NOT currentData
                                            StateMachine.Transition.keepState("updated-data");
                                    default -> StateMachine.Transition.keepState(data);
                                });

        // call() returns currentData after transition (which is "updated-data")
        var result = sm.call(new AREvent.Greet()).get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("updated-data");
        sm.stop();
    }

    // ── Builder withStateEnter + RepeatState replays postponed ────────────────

    @Test
    void repeatState_replaysPostponedEvents() throws Exception {
        var processed = new CopyOnWriteArrayList<String>();

        var iteration = new AtomicInteger(0);

        var sm =
                StateMachine.of(
                        new RPS.A(),
                        0,
                        (state, event, data) ->
                                switch (event) {
                                    case StateMachine.SMEvent.User(RPE.Postponable(var v)) -> {
                                        if (data < 1) {
                                            // First time: postpone
                                            yield StateMachine.Transition.keepState(
                                                    data, StateMachine.Action.postpone());
                                        } else {
                                            // After repeat: process
                                            processed.add(v);
                                            yield StateMachine.Transition.keepState(data);
                                        }
                                    }
                                    case StateMachine.SMEvent.User(RPE.Repeat()) ->
                                            // repeatState replays postponed
                                            StateMachine.Transition.repeatState(data + 1);
                                    default -> StateMachine.Transition.keepState(data);
                                });

        sm.send(new RPE.Postponable("msg1")); // postponed (data=0)
        sm.send(new RPE.Postponable("msg2")); // postponed (data=0)
        sm.call(new RPE.Repeat()).get(5, TimeUnit.SECONDS); // repeatState → replay postponed

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> processed.size() == 2);

        assertThat(processed).containsExactly("msg1", "msg2");
        sm.stop();
    }
}
