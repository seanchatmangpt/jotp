# How to Test JOTP Processes

## Joe Armstrong's Premise

Joe Armstrong said it plainly: "A pure function is trivially testable. You give it input, you check output. Done."

That observation is the entire foundation of how you test JOTP. Every JOTP process is built from a handler — a pure function `(S state, M message) -> S newState`. That handler has no side effects, no hidden state, no threads. It is a mathematical function. You can call it in a unit test with zero framework overhead and assert the result.

The process infrastructure — the mailbox, the virtual thread, the supervisor restart — is a separate concern. JOTP's test strategy mirrors that separation: test handler purity in unit tests, test async behavior with Awaitility, test fault tolerance with a real `Supervisor`. You never need to mock virtual threads or stub the scheduler. The process model itself is the test library.

This is Fortune 500-grade reliability testing: cover correctness at the pure-function level, async contract at the integration level, and invariants at the property level.

---

## Pattern 1: Testing Handler Logic (Pure Function)

No `Proc` required. Extract the handler as a method reference or lambda, call it directly, assert the result. The test runs synchronously, with no concurrency.

```java
import net.jqwik.api.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class CounterHandlerTest implements WithAssertions {

    // --- domain model ---
    sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.Reset {
        record Increment(int amount) implements CounterMsg {}
        record Reset()               implements CounterMsg {}
    }

    record CounterState(int count) {}

    // --- the handler under test (pure function) ---
    static CounterState handle(CounterState state, CounterMsg msg) {
        return switch (msg) {
            case CounterMsg.Increment(var n) -> new CounterState(state.count() + n);
            case CounterMsg.Reset()          -> new CounterState(0);
        };
    }

    @Test
    void increment_adds_to_count() {
        var before = new CounterState(10);
        var after  = handle(before, new CounterMsg.Increment(5));
        assertThat(after.count()).isEqualTo(15);
    }

    @Test
    void reset_returns_zero_regardless_of_prior_state() {
        var before = new CounterState(999);
        var after  = handle(before, new CounterMsg.Reset());
        assertThat(after.count()).isZero();
    }

    @Test
    void handler_is_referentially_transparent() {
        var state = new CounterState(7);
        var msg   = new CounterMsg.Increment(3);
        assertThat(handle(state, msg)).isEqualTo(handle(state, msg));
    }
}
```

Handler tests run in microseconds. Every edge case in the pure function should live here.

---

## Pattern 2: Testing `tell()` Fire-and-Forget

`tell()` is asynchronous: the message is enqueued and the calling thread returns immediately. Use Awaitility to poll until the state reflects the expected outcome.

```java
import io.github.seanchatmangpt.jotp.Proc;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class ProcTellTest implements WithAssertions {

    @Test
    void tell_increments_state_asynchronously() {
        var proc = new Proc<>(new CounterState(0), CounterHandlerTest::handle);

        proc.tell(new CounterMsg.Increment(42));

        Awaitility.await()
            .atMost(Duration.ofSeconds(1))
            .until(() -> proc.ask(new CounterMsg.Increment(0), Duration.ofMillis(200))
                             .get()
                             .count() >= 42);
    }
}
```

Never replace `await()` with `Thread.sleep()`. Awaitility retries efficiently and produces a clear failure message when the condition is not met.

---

## Pattern 3: Testing `ask()` Request-Reply

`ask()` returns a `CompletableFuture<S>`. Call `.get(timeout, unit)` in the test to block until the handler has processed the message and the new state is returned.

```java
import io.github.seanchatmangpt.jotp.Proc;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

class ProcAskTest implements WithAssertions {

    @Test
    void ask_returns_state_after_handler_runs() throws Exception {
        var proc = new Proc<>(new CounterState(100), CounterHandlerTest::handle);

        var future = proc.ask(new CounterMsg.Increment(1), Duration.ofSeconds(2));
        var result = future.get(2, TimeUnit.SECONDS);

        assertThat(result.count()).isEqualTo(101);
    }

    @Test
    void ask_after_reset_returns_zero() throws Exception {
        var proc = new Proc<>(new CounterState(500), CounterHandlerTest::handle);

        var result = proc.ask(new CounterMsg.Reset(), Duration.ofSeconds(2))
                         .get(2, TimeUnit.SECONDS);

        assertThat(result.count()).isZero();
    }
}
```

`ask()` is the preferred assertion mechanism when you need the exact post-message state. It serializes naturally with the message queue.

---

## Pattern 4: Testing Crash Behavior

To test that a crash is captured, send a message that causes the handler to throw. Then assert `proc.lastError` is non-null. Register a crash callback with `addCrashCallback` to receive the exception on the test thread without polling.

```java
import io.github.seanchatmangpt.jotp.Proc;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

class ProcCrashTest implements WithAssertions {

    sealed interface BombMsg permits BombMsg.Safe, BombMsg.Explode {
        record Safe()    implements BombMsg {}
        record Explode() implements BombMsg {}
    }

    record BombState(int ticks) {}

    static BombState bombHandler(BombState state, BombMsg msg) {
        return switch (msg) {
            case BombMsg.Safe()    -> new BombState(state.ticks() + 1);
            case BombMsg.Explode() -> throw new RuntimeException("BOOM");
        };
    }

    @Test
    void lastError_is_set_after_handler_throws() {
        var proc = new Proc<>(new BombState(0), ProcCrashTest::bombHandler);

        proc.tell(new BombMsg.Explode());

        Awaitility.await()
            .atMost(Duration.ofSeconds(1))
            .until(() -> proc.lastError != null);

        assertThat(proc.lastError).isInstanceOf(RuntimeException.class)
                                  .hasMessage("BOOM");
    }

    @Test
    void crash_callback_fires_on_exception() throws InterruptedException {
        var captured = new AtomicReference<Throwable>();
        var proc     = new Proc<>(new BombState(0), ProcCrashTest::bombHandler);
        proc.addCrashCallback(captured::set);

        proc.tell(new BombMsg.Explode());

        Awaitility.await()
            .atMost(Duration.ofSeconds(1))
            .until(() -> captured.get() != null);

        assertThat(captured.get()).hasMessage("BOOM");
    }
}
```

---

## Pattern 5: Testing Supervisor Restart

A `Supervisor` wraps processes in a `ProcRef`. After a crash, the supervisor restarts the child and the `ProcRef` transparently routes to the new process instance. Assert that the `ProcRef` is still responsive after a crash.

```java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

class SupervisorRestartTest implements WithAssertions {

    @Test
    void supervised_process_restarts_after_crash() throws Exception {
        var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(10));
        ProcRef<BombState, BombMsg> ref = supervisor.supervise(
            "bomb", new BombState(0), ProcCrashTest::bombHandler
        );

        // crash the child
        ref.tell(new BombMsg.Explode());

        // wait for restart
        Awaitility.await()
            .atMost(Duration.ofSeconds(2))
            .until(() -> {
                try {
                    ref.ask(new BombMsg.Safe(), Duration.ofMillis(200)).get(200, TimeUnit.MILLISECONDS);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });

        // ProcRef is alive again; state is reset to initial
        var state = ref.ask(new BombMsg.Safe(), Duration.ofSeconds(1)).get(1, TimeUnit.SECONDS);
        assertThat(state.ticks()).isEqualTo(1);
    }
}
```

The restart resets state to the initial value passed to `supervisor.supervise(...)`. Verify this invariant explicitly if your domain requires warm-state recovery.

---

## Pattern 6: Testing StateMachine Transitions

`StateMachine<S,E,D>` is tested by sending a sequence of events and asserting the current state after each step. Use `sm.isRunning()` to verify the machine has not reached a terminal state.

```java
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.Transition;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class TrafficLightTest implements WithAssertions {

    enum Light { RED, GREEN, YELLOW }
    sealed interface Signal permits Signal.Go, Signal.Slow, Signal.Stop {
        record Go()   implements Signal {}
        record Slow() implements Signal {}
        record Stop() implements Signal {}
    }
    record LightData(int cycles) {}

    static Transition<Light, LightData> transition(Light state, Signal event, LightData data) {
        return switch (state) {
            case RED    -> event instanceof Signal.Go()
                           ? Transition.next(Light.GREEN, new LightData(data.cycles()))
                           : Transition.keep();
            case GREEN  -> event instanceof Signal.Slow()
                           ? Transition.next(Light.YELLOW, data)
                           : Transition.keep();
            case YELLOW -> event instanceof Signal.Stop()
                           ? Transition.next(Light.RED, new LightData(data.cycles() + 1))
                           : Transition.keep();
        };
    }

    @Test
    void traffic_light_cycles_red_green_yellow_red() {
        var sm = new StateMachine<>(Light.RED, new LightData(0), TrafficLightTest::transition);

        assertThat(sm.isRunning()).isTrue();

        sm.send(new Signal.Go());
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> sm.state() == Light.GREEN);

        sm.send(new Signal.Slow());
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> sm.state() == Light.YELLOW);

        sm.send(new Signal.Stop());
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> sm.state() == Light.RED);

        assertThat(sm.data().cycles()).isEqualTo(1);
        assertThat(sm.isRunning()).isTrue();
    }
}
```

---

## Pattern 7: Property-Based Testing with jqwik

Property-based tests verify handler invariants across arbitrary message sequences — invaluable for catching edge cases that handwritten tests miss.

```java
import net.jqwik.api.*;
import org.assertj.core.api.WithAssertions;

import java.util.List;

class CounterHandlerProperties implements WithAssertions {

    @Property
    void count_never_goes_negative_after_resets(@ForAll List<@IntRange(min = 0, max = 1000) Integer> amounts) {
        var state = new CounterState(0);
        for (int amount : amounts) {
            state = CounterHandlerTest.handle(state, new CounterMsg.Increment(amount));
        }
        assertThat(state.count()).isGreaterThanOrEqualTo(0);
    }

    @Property
    void reset_always_produces_zero_regardless_of_history(
        @ForAll List<@IntRange(min = 1, max = 500) Integer> increments
    ) {
        var state = new CounterState(0);
        for (int n : increments) {
            state = CounterHandlerTest.handle(state, new CounterMsg.Increment(n));
        }
        var reset = CounterHandlerTest.handle(state, new CounterMsg.Reset());
        assertThat(reset.count()).isZero();
    }

    @Property
    void increment_is_associative(@ForAll @IntRange(min = 0, max = 200) int a,
                                   @ForAll @IntRange(min = 0, max = 200) int b) {
        var initial = new CounterState(0);
        var left  = CounterHandlerTest.handle(
                        CounterHandlerTest.handle(initial, new CounterMsg.Increment(a)),
                        new CounterMsg.Increment(b));
        var right = CounterHandlerTest.handle(
                        CounterHandlerTest.handle(initial, new CounterMsg.Increment(b)),
                        new CounterMsg.Increment(a));
        assertThat(left.count()).isEqualTo(right.count());
    }
}
```

jqwik generates hundreds of randomized inputs per `@Property` run. Shrinking automatically minimizes the failing case to its smallest form.

---

## Integration Testing: Full Supervisor Tree

Build a two-level supervision tree inside a single `@Test`. Crash a leaf node and assert that the root supervisor and its sibling subtree remain operational.

```java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

class SupervisorTreeIT implements WithAssertions {

    @Test
    void crashing_leaf_does_not_affect_sibling_subtree() throws Exception {
        // root ONE_FOR_ONE supervisor
        var root = new Supervisor(Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(30));

        // stable worker — never crashes
        ProcRef<CounterState, CounterMsg> stable = root.supervise(
            "stable", new CounterState(0), CounterHandlerTest::handle
        );

        // unreliable worker — will crash
        ProcRef<BombState, BombMsg> unstable = root.supervise(
            "unstable", new BombState(0), ProcCrashTest::bombHandler
        );

        // verify both workers are up before the crash
        var stableBefore = stable.ask(new CounterMsg.Increment(1), Duration.ofSeconds(1))
                                 .get(1, TimeUnit.SECONDS);
        assertThat(stableBefore.count()).isEqualTo(1);

        // crash the unstable child
        unstable.tell(new BombMsg.Explode());

        // wait for the unstable child to restart
        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .until(() -> {
                try {
                    unstable.ask(new BombMsg.Safe(), Duration.ofMillis(200))
                            .get(200, TimeUnit.MILLISECONDS);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });

        // stable child must still be accumulating state correctly
        var stableAfter = stable.ask(new CounterMsg.Increment(1), Duration.ofSeconds(1))
                                .get(1, TimeUnit.SECONDS);
        assertThat(stableAfter.count()).isEqualTo(2); // 1 from before + 1 now

        // unstable child restarted with fresh state
        var unstableAfter = unstable.ask(new BombMsg.Safe(), Duration.ofSeconds(1))
                                    .get(1, TimeUnit.SECONDS);
        assertThat(unstableAfter.ticks()).isEqualTo(1);
    }
}
```

Name integration tests with the `*IT.java` suffix so maven-failsafe runs them during `verify`, not surefire.

---

## Anti-Patterns to Avoid

**Thread.sleep() instead of Awaitility**

```java
// BAD — brittle on slow CI, wastes time on fast machines
proc.tell(msg);
Thread.sleep(500);
assertThat(proc.lastError).isNotNull();

// GOOD — polls efficiently, fails fast, reports elapsed time
proc.tell(msg);
Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> proc.lastError != null);
```

**Testing implementation, not behavior**

Do not assert on internal fields, thread names, or mailbox queue depths. Assert on observable state: the value returned by `ask()`, the error captured in `lastError`, whether `isRunning()` returns true. If you feel the urge to reach into private internals, the behavior you want is missing from the public API — open an issue.

**Mocking virtual threads or the scheduler**

Virtual threads are not a seam you should mock. They are the implementation detail JOTP manages for you. Testing against a fake scheduler produces tests that pass in mock land and fail in production. Use real `Proc` instances with real Awaitility assertions. The per-test overhead of a virtual thread is under 1 ms.

**Sharing Proc instances across tests without isolation**

`Proc` instances carry mutable state. Construct a fresh instance in each `@Test` method. Shared instances cause ordering dependencies between tests that are invisible until parallel execution (enabled by default in JOTP's JUnit configuration) surfaces them as flaky failures.

**Asserting on state before `ask()` confirms the message was processed**

```java
// BAD — proc.tell() is fire-and-forget; state may not be updated yet
proc.tell(new CounterMsg.Increment(1));
assertThat(/* some external side effect */).isTrue(); // race condition

// GOOD — use ask() to establish a happens-before relationship
proc.ask(new CounterMsg.Increment(1), Duration.ofSeconds(1)).get();
assertThat(/* side effect */).isTrue(); // guaranteed to have run
```

The `ask()` future completes only after the handler returns. Everything that happened inside the handler before that return is visible to the test thread once the future is resolved.
