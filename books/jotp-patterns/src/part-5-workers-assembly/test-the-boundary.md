# Pattern 30: Test the Boundary

## The Problem

You have assembled a supervision tree with event buses, timers, and registered processes. The unit tests pass -- each handler function works in isolation. But does the whole thing actually hold together? When a process crashes, does the supervisor actually restart it? Does the restarted process actually receive new messages? Does the event bus actually deliver events to the right handlers?

You need integration tests that exercise the real process infrastructure.

## The Solution

Test with real `Proc` instances. Each one costs about 1 KB of memory -- a virtual thread plus a mailbox. You can spin up hundreds in a test without worrying about resource pressure. Use Awaitility for async assertions: processes are concurrent, so you need to wait for effects to propagate.

## Testing Crash Recovery

The most important integration test: kill a process, verify the supervisor restarts it, send a message to the restarted process, verify it handles it with fresh state.

```java
class SupervisorCrashRecoveryIT implements WithAssertions {

    @Test
    void supervisor_restarts_crashed_child_with_fresh_state() throws Exception {
        // Arrange: supervisor with one child
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofSeconds(10)
        );

        var ref = supervisor.supervise(
            "counter",
            0,
            (Integer count, Object msg) -> switch (msg) {
                case String s when s.equals("crash") ->
                    throw new RuntimeException("intentional crash");
                case String s when s.equals("increment") -> count + 1;
                default -> count;
            }
        );

        // Act: increment, then crash
        ref.tell("increment");
        ref.tell("increment");

        // Verify count reached 2 before crash
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(ref.ask("get-count").join()).isEqualTo(2)
        );

        // Crash the process
        ref.tell("crash");

        // Assert: supervisor restarts with fresh state (0, not 2)
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            // The restarted process should accept messages
            ref.tell("increment");
            assertThat(ref.ask("get-count").join()).isEqualTo(1);
        });

        supervisor.shutdown();
    }
}
```

The key assertion: after the crash, the count is 1, not 3. The supervisor created a fresh process with the initial state of 0. The `ProcRef` transparently redirects to the new process -- the test code never needed to get a new reference.

## Testing Event Broadcasting

Verify that events reach handlers and that handler crashes do not affect other handlers:

```java
@Test
void event_manager_isolates_handler_failures() throws Exception {
    EventManager<String> events = EventManager.start();
    var received = new CopyOnWriteArrayList<String>();

    // Handler that crashes on "boom"
    events.addHandler(new EventManager.Handler<>() {
        public void handleEvent(String event) {
            if (event.equals("boom")) throw new RuntimeException("handler crash");
            received.add("A:" + event);
        }
    });

    // Handler that always succeeds
    events.addHandler(new EventManager.Handler<>() {
        public void handleEvent(String event) {
            received.add("B:" + event);
        }
    });

    // Both handlers see "hello"
    events.syncNotify("hello");
    assertThat(received).containsExactly("A:hello", "B:hello");

    // "boom" crashes handler A, but handler B still gets it
    received.clear();
    events.syncNotify("boom");
    assertThat(received).containsExactly("B:boom");

    // Subsequent events only reach handler B (A was evicted)
    received.clear();
    events.syncNotify("after");
    assertThat(received).containsExactly("B:after");

    events.stop();
}
```

## Testing with Awaitility

The pattern for async assertions with Awaitility:

```java
await()
    .atMost(Duration.ofSeconds(2))
    .pollInterval(Duration.ofMillis(50))
    .untilAsserted(() -> {
        // This block is retried until it passes or times out
        assertThat(someAsyncResult()).isEqualTo(expected);
    });
```

Use `atMost` to set the timeout. Use `pollInterval` to control how often the assertion is retried. The default poll interval is 100 ms, which is fine for most process tests. Keep `atMost` generous -- CI machines are slower than your laptop.

## Testing State Machines

State machines are the easiest to test because the transition function is pure:

```java
@Test
void vehicle_transitions_through_lifecycle() throws Exception {
    var sm = StateMachine.create(
        new Idle(),
        new VehicleData("V-TEST", 0),
        vehicleTransitionFn
    );

    sm.send(new StartTracking());
    VehicleData data = sm.call(new PauseTracking()).join();

    assertThat(sm.state()).isInstanceOf(Idle.class);
    assertThat(data.tripCount()).isEqualTo(1);

    sm.stop();
}
```

## What Makes These Tests Work

Three properties make JOTP integration tests practical:

1. **Lightweight processes.** Each `Proc` is a virtual thread (~1 KB). Spin up 100 in a test without hesitation.

2. **Deterministic restarts.** The supervisor restarts with the exact initial state you specified. No hidden caches, no stale connections. Fresh process, fresh state.

3. **Observable via ProcRef.** The `ask` method gives you a synchronous view into an asynchronous process. Combined with Awaitility, you can assert on process state without polling loops or sleep calls.

Name your integration tests with the `*IT.java` suffix so Maven Failsafe runs them separately from unit tests. They touch real concurrency infrastructure and may take longer than pure unit tests.
