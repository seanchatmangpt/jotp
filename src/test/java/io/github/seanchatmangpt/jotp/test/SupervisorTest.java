package io.github.seanchatmangpt.jotp.test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Demonstrates Joe Armstrong's OTP supervision tree in Java 26.
 *
 * <p>Armstrong: <em>"A supervisor process that is responsible for starting, stopping and monitoring
 * child processes is the key to reliable systems."</em>
 *
 * <p>Each test shows a different facet of supervision: crash-and-restart, isolation between
 * children (ONE_FOR_ONE), cascaded restart (ONE_FOR_ALL), and max-restart threshold propagation.
 */
@Execution(ExecutionMode.SAME_THREAD) // Isolate from parallel tests due to timing sensitivity
class SupervisorTest implements WithAssertions {

    // ── Shared message vocabulary ──────────────────────────────────────────

    sealed interface CounterMsg permits CounterMsg.Inc, CounterMsg.Boom, CounterMsg.Get {
        record Inc(int by) implements CounterMsg {}

        record Boom(String reason) implements CounterMsg {}

        record Get() implements CounterMsg {}
    }

    private static int counterHandler(int state, CounterMsg msg) {
        return switch (msg) {
            case CounterMsg.Inc(var by) -> state + by;
            case CounterMsg.Boom(var reason) -> throw new RuntimeException(reason);
            case CounterMsg.Get() -> state;
        };
    }

    // ── Helper: ask with a short timeout (returns -1 on failure) ──────────

    private static int tryGet(ProcRef<Integer, CounterMsg> ref) {
        try {
            return ref.ask(new CounterMsg.Get()).get(200, MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return -1;
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void crashAndRestartResumesService() throws Exception {
                "When a supervised process crashes, the supervisor automatically restarts it. The process resets to its initial state.");

        // CROSS-REFERENCE: Link to basic process creation

                """
            var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
            var ref = sup.supervise("counter", 0, SupervisorTest::counterHandler);

            ref.tell(new CounterMsg.Inc(10));
            assertThat(ref.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(10);

            ref.tell(new CounterMsg.Boom("injected fault")); // Crash it!

            // Supervisor restarts; process resets to initial state (0)
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(0));
            """,
                "java");
                """
            graph TD
                A[Supervisor] --> B[Counter Process: 10]
                B -->|Crash| C[Detector: RuntimeException]
                C --> D[Restart Strategy: ONE_FOR_ONE]
                D --> E[New Counter Process: 0]
                E --> F[Resume Operation]

                style B fill:#ff6b6b
                style E fill:#51cf66
                """);

        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
        var ref = sup.supervise("counter", 0, SupervisorTest::counterHandler);

        // Normal operation
        ref.tell(new CounterMsg.Inc(10));
        assertThat(ref.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(10);

        // Crash it
        ref.tell(new CounterMsg.Boom("injected fault"));

        // Supervisor restarts; process resets to initial state (0)
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(0));

        // Back to full operation after restart
        ref.tell(new CounterMsg.Inc(5));
        await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(5));

                Map.of(
                        "Strategy",
                        "ONE_FOR_ONE",
                        "Max Restarts",
                        "3",
                        "Initial State After Crash",
                        "0 (reset)",
                        "State After Recovery",
                        "5"));
        sup.shutdown();
    }

    @Test
    void oneForOneOnlyRestartsTheCrashedChild() throws Exception {
                "ONE_FOR_ONE means only the crashed child is restarted. Siblings are unaffected — fault isolation at the process level.");
                """
            var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
            var ref1 = sup.supervise("c1", 0, SupervisorTest::counterHandler);
            var ref2 = sup.supervise("c2", 100, SupervisorTest::counterHandler);

            ref2.tell(new CounterMsg.Inc(42));
            assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);

            ref1.tell(new CounterMsg.Boom("c1 crash")); // Crash only c1

            // c1 restarts to 0, c2 preserves state at 142
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(tryGet(ref1)).isEqualTo(0));
            assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);
            """,
                "java");
                """
            graph TD
                A[Supervisor] --> B[Child c1: 0]
                A --> C[Child c2: 142]
                B -->|Crash| D[Detector]
                D -->|ONE_FOR_ONE| E[Restart c1 Only]
                C -->|No Change| C

                style B fill:#ff6b6b
                style C fill:#ffd43b
                style E fill:#51cf66
                """);

        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
        var ref1 = sup.supervise("c1", 0, SupervisorTest::counterHandler);
        var ref2 = sup.supervise("c2", 100, SupervisorTest::counterHandler);

        // Accumulate state in c2 so we can tell if it was restarted
        ref2.tell(new CounterMsg.Inc(42));
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);

        // Crash only c1
        ref1.tell(new CounterMsg.Boom("c1 crash"));

        // c1 restarts to 0
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(tryGet(ref1)).isEqualTo(0));

        // c2 was NOT restarted — its state is preserved at 142
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);

                Map.of(
                        "Strategy",
                        "ONE_FOR_ONE",
                        "c1 State After Crash",
                        "0 (restarted)",
                        "c2 State After c1 Crash",
                        "142 (unaffected)"));
        sup.shutdown();
    }

    @Test
    void oneForAllRestartsAllChildren() throws Exception {
                "ONE_FOR_ALL means all children are restarted when any one crashes. Use when children are tightly coupled.");
                """
            var sup = new Supervisor(Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(10));
            var ref1 = sup.supervise("c1", 0, SupervisorTest::counterHandler);
            var ref2 = sup.supervise("c2", 100, SupervisorTest::counterHandler);

            ref1.tell(new CounterMsg.Inc(7));
            ref2.tell(new CounterMsg.Inc(9));

            ref1.tell(new CounterMsg.Boom("trigger ONE_FOR_ALL")); // Crash c1

            // Both reset to their initial states (0 and 100)
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(tryGet(ref1)).isEqualTo(0);
                assertThat(tryGet(ref2)).isEqualTo(100);
            });
            """,
                "java");
                """
            graph TD
                A[Supervisor] --> B[Child c1: 7]
                A --> C[Child c2: 109]
                B -->|Crash| D[Detector]
                D -->|ONE_FOR_ALL| E[Restart ALL Children]
                E --> F[New c1: 0]
                E --> G[New c2: 100]

                style B fill:#ff6b6b
                style C fill:#ff6b6b
                style F fill:#51cf66
                style G fill:#51cf66
                """);
                "ONE_FOR_ALL causes cascading restarts. All children reset to initial state, even those that were healthy. Use only when children have strong dependencies.");

        var sup = new Supervisor(Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(10));
        var ref1 = sup.supervise("c1", 0, SupervisorTest::counterHandler);
        var ref2 = sup.supervise("c2", 100, SupervisorTest::counterHandler);

        // Build up state in both
        ref1.tell(new CounterMsg.Inc(7));
        ref2.tell(new CounterMsg.Inc(9));
        assertThat(ref1.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(7);
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(109);

        // Crash c1 — ONE_FOR_ALL restarts both
        ref1.tell(new CounterMsg.Boom("trigger ONE_FOR_ALL"));

        // Both reset to their initial states (0 and 100)
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            assertThat(tryGet(ref1)).isEqualTo(0);
                            assertThat(tryGet(ref2)).isEqualTo(100);
                        });

                Map.of(
                        "Strategy",
                        "ONE_FOR_ALL",
                        "c1 Before Crash",
                        "7",
                        "c2 Before Crash",
                        "109",
                        "c1 After Cascade",
                        "0",
                        "c2 After Cascade",
                        "100"));
        sup.shutdown();
    }

    @Test
    void restForOneRestartsCrashedAndAllAfterIt() throws Exception {
                "REST_FOR_ONE means the crashed child and ALL children started AFTER IT are restarted. Children before the crash are unaffected. Use for ordered dependencies where later processes depend on earlier ones.");
                """
            var sup = new Supervisor(Strategy.REST_FOR_ONE, 3, Duration.ofSeconds(10));
            var ref1 = sup.supervise("first", 0, SupervisorTest::counterHandler);
            var ref2 = sup.supervise("middle", 100, SupervisorTest::counterHandler);
            var ref3 = sup.supervise("last", 1000, SupervisorTest::counterHandler);

            ref1.tell(new CounterMsg.Inc(5));
            ref2.tell(new CounterMsg.Inc(42));
            ref3.tell(new CounterMsg.Inc(99));

            // Crash the middle child
            ref2.tell(new CounterMsg.Boom("middle crash"));

            // ref1 (before crash) unaffected at 5
            // ref2 and ref3 (at/after crash) restart to 100 and 1000
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(tryGet(ref1)).isEqualTo(5);
                assertThat(tryGet(ref2)).isEqualTo(100);
                assertThat(tryGet(ref3)).isEqualTo(1000);
            });
            """,
                "java");
                """
            graph TD
                A[Supervisor] --> B[first: 5]
                A --> C[middle: 142]
                A --> D[last: 1099]
                C -->|Crash| E[Detector]
                E -->|REST_FOR_ONE| F[Restart middle + last]
                B -->|Unaffected| B
                F --> G[New middle: 100]
                F --> H[New last: 1000]

                style C fill:#ff6b6b
                style D fill:#ff6b6b
                style B fill:#ffd43b
                style G fill:#51cf66
                style H fill:#51cf66
                """);
                "REST_FOR_ONE provides partial isolation. Only the crashed process and later siblings restart. Earlier processes maintain state, making this ideal for ordered pipelines where stage N+1 depends on stage N.");

        var sup = new Supervisor(Strategy.REST_FOR_ONE, 3, Duration.ofSeconds(10));
        var ref1 = sup.supervise("first", 0, SupervisorTest::counterHandler);
        var ref2 = sup.supervise("middle", 100, SupervisorTest::counterHandler);
        var ref3 = sup.supervise("last", 1000, SupervisorTest::counterHandler);

        // Build up state in all three
        ref1.tell(new CounterMsg.Inc(5));
        ref2.tell(new CounterMsg.Inc(42));
        ref3.tell(new CounterMsg.Inc(99));
        assertThat(ref1.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(5);
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);
        assertThat(ref3.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(1099);

        // Crash the middle child — REST_FOR_ONE restarts middle AND last
        ref2.tell(new CounterMsg.Boom("middle crash"));

        // ref1 (before crash) unaffected at 5
        // ref2 and ref3 (at/after crash) restart to 100 and 1000
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            assertThat(tryGet(ref1)).isEqualTo(5);
                            assertThat(tryGet(ref2)).isEqualTo(100);
                            assertThat(tryGet(ref3)).isEqualTo(1000);
                        });

                Map.of(
                        "Strategy",
                        "REST_FOR_ONE",
                        "first (before crash)",
                        "5 (unaffected)",
                        "middle (crashed)",
                        "100 (restarted)",
                        "last (after crash)",
                        "1000 (restarted)"));
        sup.shutdown();
    }

    @Test
    void maxRestartsExceededTerminatesSupervisor() throws Exception {
                "Supervisors track restart frequency. If a child crashes more than maxRestarts times within the time window, the supervisor terminates itself (cascading failure).");
                """
            // Allow only 2 restarts in a 5-second window
            var sup = new Supervisor(Strategy.ONE_FOR_ONE, 2, Duration.ofSeconds(5));
            var ref = sup.supervise("fragile", 0, SupervisorTest::counterHandler);

            // Crash 3 times — 3rd pushes over maxRestarts=2
            for (int i = 0; i < 3; i++) {
                ref.tell(new CounterMsg.Boom("crash " + i));
                Thread.sleep(50);
            }

            await().atMost(Duration.ofSeconds(5)).until(() -> !sup.isRunning());
            assertThat(sup.fatalError()).isNotNull();
            """,
                "java");
                """
            sequenceDiagram
                participant S as Supervisor
                participant C as Child Process
                participant T as Time Window (5s)

                Note over S,T: maxRestarts=2
                C->>S: Crash #1
                S->>C: Restart (1/2)

                C->>S: Crash #2
                S->>C: Restart (2/2)

                C->>S: Crash #3
                Note over S: Threshold exceeded!
                S->>S: TERMINATE (cascade failure)

                style S fill:#ff6b6b
                """);
                "When maxRestarts is exceeded, the supervisor TERMINATES ITSELF to prevent crash loops. This is a fail-fast mechanism — the entire supervision tree shuts down to contain the fault. The supervisor's parent (if any) can then decide whether to restart the entire subtree.");

        // Allow only 2 restarts in a 5-second window
        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 2, Duration.ofSeconds(5));
        var ref = sup.supervise("fragile", 0, SupervisorTest::counterHandler);

        // Crash 3 times — 3rd pushes over maxRestarts=2
        for (int i = 0; i < 3; i++) {
            ref.tell(new CounterMsg.Boom("crash " + i));
            // Wait for restart before next crash to reliably hit the threshold
            Thread.sleep(50);
        }

        // Supervisor should have terminated itself
        await().atMost(Duration.ofSeconds(5)).until(() -> !sup.isRunning());
        assertThat(sup.fatalError()).isNotNull();
        assertThat(sup.fatalError().getMessage()).startsWith("crash");

                Map.of(
                        "Max Restarts",
                        "2",
                        "Actual Crashes",
                        "3",
                        "Supervisor Status",
                        "TERMINATED",
                        "Fatal Error",
                        sup.fatalError().getMessage()));
    }

    @Test
    void strategyComparisonTable() {
                "Choosing the right supervision strategy is critical for fault tolerance. Each strategy offers different trade-offs between fault isolation and consistency.");
                new String[][] {
                    {"Strategy", "What Restarts", "Fault Isolation", "Use Case", "State Impact"},
                    {
                        "ONE_FOR_ONE",
                        "Only crashed child",
                        "High - siblings unaffected",
                        "Independent workers, stateless services",
                        "Crashed child resets; siblings preserve state"
                    },
                    {
                        "ONE_FOR_ALL",
                        "ALL children",
                        "Low - cascade restart",
                        "Tightly coupled components, shared state",
                        "All children reset to initial state"
                    },
                    {
                        "REST_FOR_ONE",
                        "Crashed + later siblings",
                        "Medium - partial cascade",
                        "Ordered pipelines, stage dependencies",
                        "Earlier children preserve; later reset"
                    }
                });
                """
            graph TB
                subgraph "Strategy Decision Tree"
                    A[Choose Strategy] --> B{Children<br/>Independent?}
                    B -->|Yes| C[ONE_FOR_ONE]
                    B -->|No| D{Ordered<br/>Dependencies?}
                    D -->|Yes| E[REST_FOR_ONE]
                    D -->|No| F[ONE_FOR_ALL]

                    C --> G[Max fault isolation]
                    E --> H[Partial isolation<br/>for ordered stages]
                    F --> I[Strong consistency<br/>for tight coupling]
                end

                style C fill:#51cf66
                style E fill:#ffd43b
                style F fill:#ff6b6b
                """);
                "Strategy selection affects availability during failure. ONE_FOR_ONE keeps most services running; ONE_FOR_ALL causes brief but complete service interruption. REST_FOR_ONE offers a middle ground for ordered processing pipelines.");
    }

    @Property
    void supervisedProcessIsAlwaysEventuallyReachable(
            @ForAll @IntRange(min = 1, max = 5) int crashCount) throws Exception {
                "For any crash count within the max restart threshold, the supervised process is always eventually reachable again.");
                """
            graph LR
                A[Property Test] --> B[Random Crash Count: 1-5]
                B --> C[Max Restarts = count + 2]
                C --> D[Assert: Always Reachable]

                D --> E[Pass: Supervisor resilient]
                D --> F[Fail: Supervisor unreliable]

                style E fill:#51cf66
                style F fill:#ff6b6b
                """);
                "This property-based test verifies that supervisors maintain availability under fault pressure. jqwik generates random crash counts (1-5), and for each, the process must recover and respond. The buffer of +2 restarts ensures we test within the threshold, not the boundary condition.");

        var sup = new Supervisor(Strategy.ONE_FOR_ONE, crashCount + 2, Duration.ofSeconds(30));
        var ref = sup.supervise("resilient", 0, SupervisorTest::counterHandler);

        for (int i = 0; i < crashCount; i++) {
            ref.tell(new CounterMsg.Boom("crash " + i));
            // After each crash the process must eventually respond
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> tryGet(ref) >= 0); // -1 means timed-out (restarting)
        }

        // Final state: reachable and reset
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(0));

        assertThat(sup.isRunning()).isTrue();

                Map.of(
                        "Crash Count",
                        String.valueOf(crashCount),
                        "Max Restarts",
                        String.valueOf(crashCount + 2),
                        "Final Reachable",
                        "true",
                        "Supervisor Running",
                        String.valueOf(sup.isRunning())));
        sup.shutdown();
    }
}
