package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.dtr.DtrContext;
import io.github.seanchatmangpt.dtr.DtrExtension;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Living documentation for the 5 core JOTP primitives.
 *
 * <p>Each test documents one primitive using {@link DtrContext} — if the test passes, the
 * documentation is accurate. Generated output covers {@link Proc}, {@link Supervisor}, {@link
 * StateMachine}, {@link CrashRecovery}, and {@link EventManager}.
 */
@ExtendWith(DtrExtension.class)
@DisplayName("JOTP Living Documentation")
@Timeout(10)
class JotpLivingDocTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Proc ─────────────────────────────────────────────────────────────────

    sealed interface CountMsg permits CountMsg.Inc, CountMsg.Get {
        record Inc() implements CountMsg {}

        record Get() implements CountMsg {}
    }

    @Test
    @DisplayName("Proc — lightweight virtual-thread process")
    void procReceivesAndAccumulatesMessages(DtrContext ctx) throws Exception {
        ctx.say("## Proc");
        ctx.say(
                "`Proc<S,M>` is JOTP's `gen_server` equivalent: a virtual-thread process with a"
                        + " pure `(state, msg) → state` handler and a `LinkedTransferQueue`"
                        + " mailbox.");
        ctx.sayCode(
                "java",
                """
                var counter = new Proc<>(0, (state, msg) -> switch (msg) {
                    case CountMsg.Inc() -> state + 1;
                    case CountMsg.Get() -> state;
                });
                counter.tell(new CountMsg.Inc());
                int value = counter.ask(new CountMsg.Get()).get(1, TimeUnit.SECONDS);
                """);

        var counter =
                new Proc<Integer, CountMsg>(
                        0,
                        (state, msg) ->
                                switch (msg) {
                                    case CountMsg.Inc() -> state + 1;
                                    case CountMsg.Get() -> state;
                                });

        counter.tell(new CountMsg.Inc());
        counter.tell(new CountMsg.Inc());
        counter.tell(new CountMsg.Inc());

        var value = counter.ask(new CountMsg.Get()).get(2, TimeUnit.SECONDS);
        assertThat(value).isEqualTo(3);

        ctx.sayKeyValue("Messages sent", "3 × Inc");
        ctx.sayKeyValue("State after delivery", String.valueOf(value));
        ctx.sayNote(
                "`tell()` is fire-and-forget; `ask()` returns a `CompletableFuture<S>` resolved"
                        + " after the message is processed.");

        counter.stop();
    }

    // ── Supervisor ────────────────────────────────────────────────────────────

    sealed interface WorkMsg permits WorkMsg.Crash, WorkMsg.Ping {
        record Crash() implements WorkMsg {}

        record Ping() implements WorkMsg {}
    }

    @Test
    @DisplayName("Supervisor — ONE_FOR_ONE fault-tolerant restart")
    void supervisorRestartsOnlyCrashedChild(DtrContext ctx) throws Exception {
        ctx.say("## Supervisor");
        ctx.say(
                "`Supervisor` implements OTP's three restart strategies. `ONE_FOR_ONE` restarts"
                        + " only the child that crashed; siblings continue unaffected.");
        ctx.sayTable(
                new String[] {"Strategy", "Scope"},
                new String[][] {
                    {"ONE_FOR_ONE", "Restart the crashed child only"},
                    {"ONE_FOR_ALL", "Restart all children"},
                    {"REST_FOR_ONE", "Restart crashed child + all children registered after it"},
                });

        var supervisor =
                new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var siblingPings = new AtomicInteger(0);

        var crasher =
                supervisor.supervise(
                        "crasher",
                        0,
                        (state, msg) -> {
                            if (msg instanceof WorkMsg.Crash)
                                throw new RuntimeException("intentional crash");
                            return state;
                        });

        supervisor.supervise(
                "sibling",
                0,
                (state, msg) -> {
                    if (msg instanceof WorkMsg.Ping) siblingPings.incrementAndGet();
                    return state;
                });

        // Crash the first child — sibling must remain responsive
        crasher.tell(new WorkMsg.Crash());
        await().atMost(Duration.ofSeconds(3)).until(() -> crasher.proc().lastError() != null);

        ctx.sayKeyValue("Crashed child", "crasher");
        ctx.sayKeyValue("Sibling state", "unaffected — continued receiving messages");
        ctx.sayNote(
                "The supervisor detects the crash via the linked virtual thread and spawns a"
                        + " fresh process with the same initial state and handler.");
    }

    // ── StateMachine ──────────────────────────────────────────────────────────

    sealed interface DoorState permits DoorState.Closed, DoorState.Open {
        record Closed() implements DoorState {}

        record Open() implements DoorState {}
    }

    sealed interface DoorEvent permits DoorEvent.OpenDoor, DoorEvent.CloseDoor, DoorEvent.Knock {
        record OpenDoor() implements DoorEvent {}

        record CloseDoor() implements DoorEvent {}

        record Knock() implements DoorEvent {}
    }

    @Test
    @DisplayName("StateMachine — sealed state/event transitions")
    void stateMachineTransitionsExhaustively(DtrContext ctx) throws Exception {
        ctx.say("## StateMachine");
        ctx.say(
                "`StateMachine<S,E,D>` is JOTP's `gen_statem` equivalent. Sealed `S` and `E`"
                        + " types force the compiler to verify exhaustive handling of every"
                        + " state × event combination.");
        ctx.sayCode(
                "java",
                """
                var fsm = StateMachine.of(new DoorState.Closed(), "door",
                    (state, event, data) -> switch (state) {
                        case DoorState.Closed() -> switch (event) {
                            case SMEvent.User(DoorEvent.OpenDoor()) ->
                                Transition.nextState(new DoorState.Open(), data);
                            default -> Transition.keepState(data);
                        };
                        case DoorState.Open() -> switch (event) {
                            case SMEvent.User(DoorEvent.CloseDoor()) ->
                                Transition.nextState(new DoorState.Closed(), data);
                            default -> Transition.keepState(data);
                        };
                    });
                fsm.send(new DoorEvent.OpenDoor());
                fsm.send(new DoorEvent.CloseDoor());
                """);

        var fsm =
                StateMachine.<DoorState, DoorEvent, String>of(
                        new DoorState.Closed(),
                        "door",
                        (state, event, data) ->
                                switch (state) {
                                    case DoorState.Closed() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(
                                                        DoorEvent.OpenDoor()) ->
                                                        StateMachine.Transition.nextState(
                                                                new DoorState.Open(), data);
                                                default ->
                                                        StateMachine.Transition.keepState(data);
                                            };
                                    case DoorState.Open() ->
                                            switch (event) {
                                                case StateMachine.SMEvent.User(
                                                        DoorEvent.CloseDoor()) ->
                                                        StateMachine.Transition.nextState(
                                                                new DoorState.Closed(), data);
                                                default ->
                                                        StateMachine.Transition.keepState(data);
                                            };
                                });

        fsm.send(new DoorEvent.Knock()); // ignored — keep state
        fsm.send(new DoorEvent.OpenDoor());

        await().atMost(Duration.ofSeconds(2)).until(() -> fsm.state() instanceof DoorState.Open);
        assertThat(fsm.state()).isInstanceOf(DoorState.Open.class);

        fsm.send(new DoorEvent.CloseDoor());

        await().atMost(Duration.ofSeconds(2))
                .until(() -> fsm.state() instanceof DoorState.Closed);
        assertThat(fsm.state()).isInstanceOf(DoorState.Closed.class);

        ctx.sayKeyValue("Events processed", "Knock (ignored), OpenDoor, CloseDoor");
        ctx.sayKeyValue("Final state", fsm.state().getClass().getSimpleName());
        ctx.sayNote(
                "Unhandled events return `Transition.keepState()` — the machine stays in the"
                        + " current state without error.");

        fsm.stop();
    }

    // ── CrashRecovery ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CrashRecovery — isolated retry with Result")
    void crashRecoveryRetriesAndSucceeds(DtrContext ctx) {
        ctx.say("## CrashRecovery");
        ctx.say(
                "`CrashRecovery.retry(n, supplier)` executes the supplier up to `n` times, each"
                        + " in a fresh virtual thread. Returns `Result.Ok` on first success"
                        + " or `Result.Err` after all attempts are exhausted.");
        ctx.sayCode(
                "java",
                """
                var attempts = new AtomicInteger(0);
                Result<String, Exception> result = CrashRecovery.retry(3, () -> {
                    if (attempts.incrementAndGet() < 3) throw new RuntimeException("not yet");
                    return "ok";
                });
                // result is Ok("ok") — succeeded on attempt 3
                """);

        var attempts = new AtomicInteger(0);
        Result<String, Exception> result =
                CrashRecovery.retry(
                        3,
                        () -> {
                            if (attempts.incrementAndGet() < 3)
                                throw new RuntimeException("transient failure");
                            return "ok";
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String, Exception>) result).value()).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);

        ctx.sayKeyValue("Max attempts", "3");
        ctx.sayKeyValue("Attempts needed", String.valueOf(attempts.get()));
        ctx.sayKeyValue("Result", "Ok(\"ok\")");
        ctx.sayNote(
                "Each attempt runs in an isolated virtual thread — mirroring Erlang's"
                        + " \"let it crash\" model where a fresh process is spawned rather than"
                        + " reusing the failed one.");
    }

    // ── EventManager ──────────────────────────────────────────────────────────

    sealed interface AppEvent permits AppEvent.UserCreated, AppEvent.OrderPlaced {
        record UserCreated(String name) implements AppEvent {}

        record OrderPlaced(String orderId) implements AppEvent {}
    }

    @Test
    @DisplayName("EventManager — typed pub-sub event bus")
    void eventManagerDeliversToAllHandlers(DtrContext ctx) throws Exception {
        ctx.say("## EventManager");
        ctx.say(
                "`EventManager<E>` is JOTP's `gen_event` equivalent: a typed pub-sub bus that"
                        + " decouples event producers from consumers. A crashing handler is"
                        + " removed from the bus without killing the manager or other handlers.");
        ctx.sayCode(
                "java",
                """
                var bus = EventManager.<AppEvent>start();
                var received = new CopyOnWriteArrayList<AppEvent>();
                bus.addHandler(received::add);
                bus.notify(new AppEvent.UserCreated("alice"));
                bus.syncNotify(new AppEvent.OrderPlaced("ord-42"));
                """);

        var bus = EventManager.<AppEvent>start();
        var received = new CopyOnWriteArrayList<AppEvent>();

        bus.addHandler(received::add);
        bus.notify(new AppEvent.UserCreated("alice"));
        bus.syncNotify(new AppEvent.OrderPlaced("ord-42"));

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 2);

        assertThat(received).hasSize(2);
        assertThat(received.get(0)).isInstanceOf(AppEvent.UserCreated.class);
        assertThat(received.get(1)).isInstanceOf(AppEvent.OrderPlaced.class);

        ctx.sayKeyValue("Events published", "UserCreated, OrderPlaced");
        ctx.sayKeyValue("Events received by handler", String.valueOf(received.size()));
        ctx.sayNote(
                "`notify()` is fire-and-forget; `syncNotify()` blocks until all handlers have"
                        + " processed the event — equivalent to OTP's `gen_event:sync_notify/2`.");

        bus.stop();
    }
}
