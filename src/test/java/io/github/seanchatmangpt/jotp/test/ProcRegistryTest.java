package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import java.time.Duration;
import java.util.Map;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP process-registry semantics in Java 26.
 *
 * <p>Armstrong: "The process registry provides name-based process discovery. It's Erlang's
 * whereis/1 - find a process by its registered name."
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining OTP registry semantics, name-based lookup, auto-deregistration, and lifecycle
 * management. Run with DTR to see examples with actual output values.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>{@code register/whereis} — basic registration and lookup
 *   <li>Duplicate registration throws
 *   <li>Auto-deregistration on process death (any reason)
 *   <li>Explicit {@code unregister} removes name without stopping process
 *   <li>{@code registered()} returns snapshot of current names
 * </ol>
 */
@Timeout(10)
class ProcRegistryTest implements WithAssertions {


    sealed interface Msg permits Msg.Ping, Msg.Crash {
        record Ping() implements Msg {}

        record Crash() implements Msg {}
    }

    static Proc<Integer, Msg> counter() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Ping() -> state + 1;
                            case Msg.Crash() -> throw new RuntimeException("BOOM");
                        });
    }

    @AfterEach
    void cleanup() {
        ProcRegistry.reset();
    }

    // -------------------------------------------------------------------------
    // 1. register + whereis basic round-trip
    // -------------------------------------------------------------------------

    @Test
    void register_whereisReturnsProc() throws InterruptedException {
                "ProcRegistry provides name-based process discovery, equivalent to Erlang's whereis/1. Register a process with a name, then lookup by that name.");
                """
            var proc = counter();
            ProcRegistry.register("my-counter", proc);

            // Find the process by name
            var found = ProcRegistry.<Integer, Msg>whereis("my-counter");

            // found.isPresent() == true
            // found.get() == proc
            """,
                "java");
                """
            sequenceDiagram
                participant C as Client
                participant R as Registry
                participant P as Process

                C->>R: register("my-counter", proc)
                R->>R: Map name -> proc
                R-->>C: OK

                C->>R: whereis("my-counter")
                R->>R: Lookup name
                R-->>C: Optional[proc]

                style R fill:#51cf66
            """);
                "Registered names are global within the JVM. Use descriptive names like 'user-session-service' or 'order-processor'. Names must be unique.");

        var proc = counter();
        ProcRegistry.register("my-counter", proc);

        var found = ProcRegistry.<Integer, Msg>whereis("my-counter");

        assertThat(found).isPresent().contains(proc);

                Map.of(
                        "Registered Name",
                        "my-counter",
                        "Process Found",
                        String.valueOf(found.isPresent()),
                        "Same Instance",
                        String.valueOf(found.get() == proc)));
        proc.stop();
    }

    @Test
    void whereis_unknownName_returnsEmpty() {
                "Looking up an unregistered name returns Optional.empty(). This is the safe API - no null checks needed.");
                """
            var result = ProcRegistry.whereis("no-such-process");

            // result.isEmpty() == true
            // No NullPointerException risk
            """,
                "java");
                "Optional return type forces explicit handling of missing processes. This is safer than returning null.");

        var result = ProcRegistry.whereis("no-such-process");

        assertThat(result).isEmpty();

                Map.of(
                        "Queried Name",
                        "no-such-process",
                        "Result",
                        "Optional.empty()",
                        "Safe",
                        "Yes (no null)"));
    }

    // -------------------------------------------------------------------------
    // 2. Duplicate registration throws
    // -------------------------------------------------------------------------

    @Test
    void register_duplicate_throws() throws InterruptedException {
                "Registering a duplicate name throws IllegalStateException. This prevents accidental name collisions and ensures name uniqueness.");
                """
            var a = counter();
            var b = counter();

            ProcRegistry.register("shared-name", a);

            // Second registration with same name throws
            assertThatThrownBy(() -> ProcRegistry.register("shared-name", b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared-name");
            """,
                "java");
                "Name conflicts indicate a configuration error. Two processes are trying to use the same name. Use unique names per process instance.");

        var a = counter();
        var b = counter();
        ProcRegistry.register("x", a);

        assertThatThrownBy(() -> ProcRegistry.register("x", b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x");

                Map.of(
                        "First Process",
                        "Registered as 'x'",
                        "Second Process",
                        "Rejected",
                        "Exception Type",
                        "IllegalStateException",
                        "Protection",
                        "Name uniqueness enforced"));

        a.stop();
        b.stop();
    }

    // -------------------------------------------------------------------------
    // 3. Auto-deregistration on abnormal exit
    // -------------------------------------------------------------------------

    @Test
    void autoDeregister_onCrash() {
                "When a registered process crashes, it's automatically removed from the registry. No manual cleanup needed.");
                """
            var proc = counter();
            ProcRegistry.register("crasher", proc);

            // Verify it's registered
            assertThat(ProcRegistry.whereis("crasher").isPresent()).isTrue();

            // Crash the process
            proc.tell(new Msg.Crash());

            // Auto-deregistered - name no longer exists
            await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcRegistry.whereis("crasher").isEmpty());
            """,
                "java");
                """
            stateDiagram-v2
                [*] --> Registered: register(name, proc)
                Registered --> Crashing: proc.crash()
                Crashing --> AutoDeregister: Registry detects death
                AutoDeregister --> [*]: Name removed
            """);
                "Auto-deregistration prevents stale references. A crashed process can't be looked up - the name is immediately available for reuse.");

        var proc = counter();
        ProcRegistry.register("crasher", proc);

        proc.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcRegistry.whereis("crasher").isEmpty());

                Map.of(
                        "Initial State",
                        "Registered",
                        "Process Action",
                        "Crashed",
                        "Registry Action",
                        "Auto-deregistered",
                        "Name Status",
                        "Available for reuse"));
    }

    // -------------------------------------------------------------------------
    // 4. Auto-deregistration on normal stop
    // -------------------------------------------------------------------------

    @Test
    void autoDeregister_onNormalStop() throws InterruptedException {
                "Graceful shutdown (stop()) also triggers auto-deregistration. The name is removed when the process terminates normally.");
                """
            var proc = counter();
            ProcRegistry.register("stopper", proc);

            // Graceful shutdown
            proc.stop();

            // Auto-deregistered
            await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcRegistry.whereis("stopper").isEmpty());
            """,
                "java");
                "Auto-deregistration works for any process termination: crash, stop, or system exit. The registry monitors process lifecycle automatically.");

        var proc = counter();
        ProcRegistry.register("stopper", proc);

        proc.stop();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcRegistry.whereis("stopper").isEmpty());

                Map.of(
                        "Process Exit",
                        "Normal (stop())",
                        "Registry Action",
                        "Auto-deregistered",
                        "Manual Cleanup",
                        "Not required"));
    }

    // -------------------------------------------------------------------------
    // 5. Explicit unregister removes name; process keeps running
    // -------------------------------------------------------------------------

    @Test
    void unregister_removesName_processStillAlive() throws InterruptedException {
                "unregister() removes the name from the registry but keeps the process running. Useful for dynamic name changes or temporary registration.");
                """
            var proc = counter();
            ProcRegistry.register("temp-name", proc);

            // Remove the name - process continues
            ProcRegistry.unregister("temp-name");

            // Name is gone
            assertThat(ProcRegistry.whereis("temp-name")).isEmpty();

            // Process still works
            var count = proc.ask(new Msg.Ping()).join();
            // count == 1
            """,
                "java");
                """
            stateDiagram-v2
                [*] --> Registered: register()
                Registered --> Unregistered: unregister()
                Unregistered --> [*]: Process keeps running

                note right of Unregistered
                    Name removed
                    Process still alive
                    Can accept messages
                end note
            """);
                "unregister is for name management, not process control. Use stop() to terminate the process, unregister() just removes the name.");

        var proc = counter();
        ProcRegistry.register("alive", proc);

        ProcRegistry.unregister("alive");

        assertThat(ProcRegistry.whereis("alive")).isEmpty();

        var count = proc.ask(new Msg.Ping()).join();
        assertThat(count).isEqualTo(1);

                Map.of(
                        "Name Status",
                        "Removed from registry",
                        "Process Status",
                        "Still running",
                        "Can Accept Messages",
                        "Yes",
                        "Message Processed",
                        String.valueOf(count)));
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 6. registered() returns snapshot of all current names
    // -------------------------------------------------------------------------

    @Test
    void registered_returnsAllNames() throws InterruptedException {
                "registered() returns a snapshot of all currently registered names. Useful for introspection and debugging.");
                """
            var a = counter();
            var b = counter();

            ProcRegistry.register("alpha", a);
            ProcRegistry.register("beta", b);

            // Get all registered names
            var names = ProcRegistry.registered();

            // names.contains("alpha") == true
            // names.contains("beta") == true
            """,
                "java");
                "The returned set is a snapshot - it won't change if processes are registered later. Call registered() again for the current state.");

        var a = counter();
        var b = counter();
        ProcRegistry.register("alpha", a);
        ProcRegistry.register("beta", b);

        var names = ProcRegistry.registered();

        assertThat(names).contains("alpha", "beta");

                Map.of(
                        "Registered Names",
                        names.toString(),
                        "Count",
                        String.valueOf(names.size()),
                        "Snapshot",
                        "Yes (immutable view)"));

        a.stop();
        b.stop();
    }

    // -------------------------------------------------------------------------
    // 7. Name can be reused after process dies
    // -------------------------------------------------------------------------

    @Test
    void register_nameReusableAfterDeath() throws InterruptedException {
                "Once a process dies and its name is auto-deregistered, the name becomes available for reuse. New processes can register under the same name.");
                """
            var first = counter();
            ProcRegistry.register("reusable", first);

            // First process dies
            first.stop();

            // Wait for auto-deregistration
            await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcRegistry.whereis("reusable").isEmpty());

            // Register a new process with the same name
            var second = counter();
            ProcRegistry.register("reusable", second);

            // Name now points to the new process
            var found = ProcRegistry.<Integer, Msg>whereis("reusable");
            // found.get() == second
            """,
                "java");
                "Name reuse is intentional for service restarts. A new process instance can take over the same name after the previous one dies.");

        var first = counter();
        ProcRegistry.register("reusable", first);
        first.stop();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcRegistry.whereis("reusable").isEmpty());

        var second = counter();
        ProcRegistry.register("reusable", second);
        assertThat(ProcRegistry.<Integer, Msg>whereis("reusable")).isPresent().contains(second);

                Map.of(
                        "First Process",
                        "Stopped and auto-deregistered",
                        "Name Status",
                        "Available",
                        "Second Process",
                        "Registered successfully",
                        "Name Points To",
                        "New process instance"));

        second.stop();
    }
}
