package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcLink;
import java.time.Duration;
import java.util.Map;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP process-link semantics in Java 26.
 *
 * <p>Armstrong: "A link is a connection between two processes. If one process dies, the other is
 * notified. This is the fundamental building block of fault-tolerant systems."
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining OTP link semantics, bidirectional crash propagation, spawn_link atomicity, and
 * transitive failure chains. Run with DTR to see examples with actual output values.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>Abnormal exit propagates: A crashes → B is interrupted
 *   <li>Normal exit does NOT propagate: A.stop() → B keeps running
 *   <li>{@code spawn_link} is atomic: no window between spawn and link
 *   <li>Link chains propagate transitively (A→B→C all die when A crashes)
 * </ol>
 */
@Timeout(10)
class ProcLinkTest implements WithAssertions {


    /** A process that counts increments and crashes on "BOOM". */
    sealed interface Msg permits Msg.Inc, Msg.Boom, Msg.Ping {
        record Inc() implements Msg {}

        record Boom() implements Msg {}

        record Ping() implements Msg {}
    }

    private static int handle(int state, Msg msg) {
        return switch (msg) {
            case Msg.Inc() -> state + 1;
            case Msg.Ping() -> state;
            case Msg.Boom() -> throw new RuntimeException("process crashed");
        };
    }

    // ── Test 1: A crashes → B is interrupted ──────────────────────────────

    @Test
    void link_crashA_interruptsB() throws Exception {
                "ProcLink creates a bidirectional crash relationship between two processes. When either process crashes abnormally, the linked partner is interrupted.");
                """
            var a = new Proc<>(0, ProcLinkTest::handle);
            var b = new Proc<>(0, ProcLinkTest::handle);
            ProcLink.link(a, b);

            // Crash process A
            a.tell(new Msg.Boom());

            // Process B is interrupted (its virtual thread stops)
            await().atMost(Duration.ofSeconds(2)).until(() -> !b.thread().isAlive());
            """,
                "java");
                """
            graph LR
                A[Process A] <-->|ProcLink.link| B[Process B]
                A -->|Crash| C[Exception]
                C -->|Interrupt| B
                style A fill:#ff6b6b
                style B fill:#ff6b6b
                style C fill:#ffd43b
            """);
                "Links are bidirectional: A crash kills B, and B crash kills A. This is fundamental to OTP's 'let it crash' philosophy - failures cascade to supervisors.");

        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);

        a.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2)).until(() -> !b.thread().isAlive());

                Map.of(
                        "Process A Status",
                        "Crashed",
                        "Process B Status",
                        "Interrupted",
                        "Link Type",
                        "Bidirectional"));
    }

    // ── Test 2: Symmetric — B crashes → A is interrupted ──────────────────

    @Test
    void link_crashB_interruptsA() throws Exception {
                "Links are symmetric - crash propagation works in both directions. B crashing interrupts A just as A crashing interrupts B.");
                """
            var a = new Proc<>(0, ProcLinkTest::handle);
            var b = new Proc<>(0, ProcLinkTest::handle);
            ProcLink.link(a, b);

            // Crash process B instead
            b.tell(new Msg.Boom());

            // Process A is interrupted
            await().atMost(Duration.ofSeconds(2)).until(() -> !a.thread().isAlive());
            """,
                "java");
                """
            graph LR
                A[Process A] <-->|ProcLink.link| B[Process B]
                B -->|Crash| C[Exception]
                C -->|Interrupt| A
                style A fill:#ff6b6b
                style B fill:#ff6b6b
                style C fill:#ffd43b
            """);

        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);

        b.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2)).until(() -> !a.thread().isAlive());

                Map.of(
                        "Process B Status",
                        "Crashed",
                        "Process A Status",
                        "Interrupted",
                        "Propagation",
                        "Symmetric"));
    }

    // ── Test 3: Normal stop does NOT propagate to linked partner ──────────

    @Test
    void link_normalStopA_doesNotAffectB() throws Exception {
                "Graceful shutdown (stop()) does NOT propagate through links. Only abnormal exits (crashes) trigger linked process interruption.");
                """
            var a = new Proc<>(0, ProcLinkTest::handle);
            var b = new Proc<>(0, ProcLinkTest::handle);
            ProcLink.link(a, b);

            // Graceful stop of a — normal exit does not propagate
            a.stop();

            // b should still be alive and responsive
            assertThat(b.thread().isAlive()).isTrue();

            // b can still process messages
            b.tell(new Msg.Inc());
            var state = b.ask(new Msg.Ping()).get();
            // state == 1
            """,
                "java");
                """
            stateDiagram-v2
                [*] --> Linked: ProcLink.link(a, b)
                Linked --> A_stops: a.stop() (normal)
                A_stops --> B_running: b continues running
                B_running --> [*]: b.stop()
                note right of A_stops
                    Normal exit: NO propagation
                    Only crashes propagate
                end note
            """);
                "This distinction is critical for graceful shutdown. You can stop a process without killing its linked partners. Only crashes (exceptions) cascade.");

        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);

        a.stop();

        Thread.sleep(100);
        assertThat(b.thread().isAlive()).isTrue();

        b.tell(new Msg.Inc());
        var state = b.ask(new Msg.Ping()).get();
        assertThat(state).isEqualTo(1);

                Map.of(
                        "Process A Exit",
                        "Normal (stop())",
                        "Process B Status",
                        "Still Running",
                        "Propagation",
                        "None",
                        "B State After Message",
                        String.valueOf(state)));
        b.stop();
    }

    // ── Test 4: spawnLink — parent crash kills child ───────────────────────

    @Test
    void spawnLink_parentCrash_killsChild() throws Exception {
                "spawnLink() atomically creates and links a child process. There's no window between spawn and link where crashes could be missed.");
                """
            var parent = new Proc<>(0, ProcLinkTest::handle);
            var child = ProcLink.spawnLink(parent, 0, ProcLinkTest::handle);

            // Parent crash kills child via atomic link
            parent.tell(new Msg.Boom());

            await().atMost(Duration.ofSeconds(2)).until(() -> !child.thread().isAlive());
            """,
                "java");
                """
            sequenceDiagram
                participant P as Parent
                participant SL as spawnLink()
                participant C as Child

                P->>SL: spawnLink(parent, 0, handler)
                Note over SL: ATOMIC operation
                SL->>C: Create child process
                SL->>P: Link parent-child
                Note over P,C: Link established

                P->>P: Crash!
                P--xC: Interrupt via link
                Note over C: Child dies
            """);
                "spawnLink is equivalent to Erlang's spawn_link. It guarantees the child is linked from creation, eliminating race conditions in supervision trees.");

        var parent = new Proc<>(0, ProcLinkTest::handle);
        var child = ProcLink.spawnLink(parent, 0, ProcLinkTest::handle);

        parent.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2)).until(() -> !child.thread().isAlive());

                Map.of(
                        "Operation",
                        "spawnLink (atomic)",
                        "Parent Status",
                        "Crashed",
                        "Child Status",
                        "Interrupted",
                        "Link Established",
                        "At creation time"));
    }

    // ── Test 5: spawnLink — child crash kills parent ───────────────────────

    @Test
    void spawnLink_childCrash_killsParent() throws Exception {
                "spawnLink creates a bidirectional link. Child crashes kill the parent just as parent crashes kill the child.");
                """
            var parent = new Proc<>(0, ProcLinkTest::handle);
            var child = ProcLink.spawnLink(parent, 0, ProcLinkTest::handle);

            // Child crash kills parent
            child.tell(new Msg.Boom());

            await().atMost(Duration.ofSeconds(2)).until(() -> !parent.thread().isAlive());
            """,
                "java");
                """
            graph LR
                P[Parent] <-->|spawnLink| C[Child]
                C -->|Crash| X[Exception]
                X -->|Interrupt| P
                style P fill:#ff6b6b
                style C fill:#ff6b6b
                style X fill:#ffd43b
            """);
                "In supervision trees, you typically use ProcLink.link() not spawnLink. spawnLink is for parent-child pairs where either should kill the other. Supervisors use monitor/monitor relationships instead.");

        var parent = new Proc<>(0, ProcLinkTest::handle);
        var child = ProcLink.spawnLink(parent, 0, ProcLinkTest::handle);

        child.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2)).until(() -> !parent.thread().isAlive());

                Map.of(
                        "Link Direction",
                        "Bidirectional",
                        "Child Status",
                        "Crashed",
                        "Parent Status",
                        "Interrupted",
                        "Propagation",
                        "Child → Parent"));
    }

    // ── Test 6: Link chain — A→B→C, A crashes → B crashes → C crashes ────

    @Test
    void linkChain_oneCrashPropagatesTransitively() throws Exception {
                "Links form chains. When A links to B, and B links to C, a crash in A propagates through the entire chain: A crashes → B interrupted → C interrupted.");
                """
            var a = new Proc<>(0, ProcLinkTest::handle);
            var b = new Proc<>(0, ProcLinkTest::handle);
            var c = new Proc<>(0, ProcLinkTest::handle);
            ProcLink.link(a, b);
            ProcLink.link(b, c);

            // Crash the head of the chain
            a.tell(new Msg.Boom());

            // Entire chain falls: A → B → C
            await().atMost(Duration.ofSeconds(3)).until(() ->
                !a.thread().isAlive() && !b.thread().isAlive() && !c.thread().isAlive());
            """,
                "java");
                """
            graph LR
                A[A] <-->|link| B[B]
                B <-->|link| C[C]
                A -->|Crash| X[Exception]
                X -->|Interrupt| B
                B -->|Interrupt| C
                style A fill:#ff6b6b
                style B fill:#ff6b6b
                style C fill:#ff6b6b
                style X fill:#ffd43b
            """);
                "Transitive propagation is why supervisors must be careful with link topology. A crash can cascade through an entire supervision tree. This is intentional - it allows failure to be contained at appropriate boundaries.");

        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        var c = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);
        ProcLink.link(b, c);

        a.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(3))
                .until(
                        () ->
                                !a.thread().isAlive()
                                        && !b.thread().isAlive()
                                        && !c.thread().isAlive());

                Map.of(
                        "Chain Topology",
                        "A → B → C",
                        "Trigger",
                        "A crashes",
                        "Propagation",
                        "Transitive (A→B→C)",
                        "Final State",
                        "All processes dead"));
    }
}
