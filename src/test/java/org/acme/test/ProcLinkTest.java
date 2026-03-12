package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.acme.Proc;
import org.acme.ProcLink;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP process-link semantics in Java 26.
 *
 * <p>Armstrong: "A link is a connection between two processes. If one process dies, the other is
 * notified. This is the fundamental building block of fault-tolerant systems."
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
        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);

        a.tell(new Msg.Boom());

        // B should be interrupted (its virtual thread stops)
        await().atMost(Duration.ofSeconds(2))
                .until(() -> !b.thread().isAlive());
    }

    // ── Test 2: Symmetric — B crashes → A is interrupted ──────────────────

    @Test
    void link_crashB_interruptsA() throws Exception {
        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);

        b.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2))
                .until(() -> !a.thread().isAlive());
    }

    // ── Test 3: Normal stop does NOT propagate to linked partner ──────────

    @Test
    void link_normalStopA_doesNotAffectB() throws Exception {
        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);

        // Graceful stop of a — OTP: normal exit does not propagate
        a.stop();

        // b should still be alive and responsive
        Thread.sleep(100); // brief wait to detect any spurious propagation
        assertThat(b.thread().isAlive()).isTrue();

        // b can still process messages
        b.tell(new Msg.Inc());
        var state = b.ask(new Msg.Ping()).get();
        assertThat(state).isEqualTo(1);

        b.stop();
    }

    // ── Test 4: spawnLink — parent crash kills child ───────────────────────

    @Test
    void spawnLink_parentCrash_killsChild() throws Exception {
        var parent = new Proc<>(0, ProcLinkTest::handle);
        var child = ProcLink.spawnLink(parent, 0, ProcLinkTest::handle);

        parent.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2))
                .until(() -> !child.thread().isAlive());
    }

    // ── Test 5: spawnLink — child crash kills parent ───────────────────────

    @Test
    void spawnLink_childCrash_killsParent() throws Exception {
        var parent = new Proc<>(0, ProcLinkTest::handle);
        var child = ProcLink.spawnLink(parent, 0, ProcLinkTest::handle);

        child.tell(new Msg.Boom());

        await().atMost(Duration.ofSeconds(2))
                .until(() -> !parent.thread().isAlive());
    }

    // ── Test 6: Link chain — A→B→C, A crashes → B crashes → C crashes ────

    @Test
    void linkChain_oneCrashPropagatesTransitively() throws Exception {
        var a = new Proc<>(0, ProcLinkTest::handle);
        var b = new Proc<>(0, ProcLinkTest::handle);
        var c = new Proc<>(0, ProcLinkTest::handle);
        ProcLink.link(a, b);
        ProcLink.link(b, c);

        a.tell(new Msg.Boom());

        // A crashes → B interrupted → C interrupted
        await().atMost(Duration.ofSeconds(3))
                .until(() -> !a.thread().isAlive() && !b.thread().isAlive() && !c.thread().isAlive());
    }
}
