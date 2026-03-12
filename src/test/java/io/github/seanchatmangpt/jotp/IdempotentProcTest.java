package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link IdempotentProc} — Joe Armstrong's idempotent message deduplication pattern.
 *
 * <p>"Design your protocols to survive retries." — Joe Armstrong
 *
 * <p>Verifies that duplicate idempotency keys are silently discarded while distinct keys and
 * non-idempotent messages are always forwarded to the delegate process.
 */
@Timeout(10)
class IdempotentProcTest implements WithAssertions {

    // ── Domain messages ────────────────────────────────────────────────────────

    sealed interface Cmd permits Cmd.IdempotentCmd, Cmd.PlainCmd {

        /** A command that carries an idempotency key — duplicates are deduplicated. */
        record IdempotentCmd(String key, String payload) implements Cmd, IdempotentProc.Idempotent {
            @Override
            public String idempotencyKey() {
                return key;
            }
        }

        /** A plain command with no idempotency key — always delivered. */
        record PlainCmd(String payload) implements Cmd {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Build a proc that accumulates every received payload into a list, and return both the list
     * and the wrapped {@link IdempotentProc}.
     */
    private record Fixture(
            IdempotentProc<CopyOnWriteArrayList<String>, Cmd> idempotentProc,
            CopyOnWriteArrayList<String> received,
            Proc<CopyOnWriteArrayList<String>, Cmd> delegate) {}

    private Fixture buildFixture(int dedupCacheSize) {
        var received = new CopyOnWriteArrayList<String>();
        var delegate =
                new Proc<CopyOnWriteArrayList<String>, Cmd>(
                        received,
                        (state, msg) -> {
                            switch (msg) {
                                case Cmd.IdempotentCmd(var key, var payload) -> state.add(payload);
                                case Cmd.PlainCmd(var payload) -> state.add(payload);
                            }
                            return state;
                        });
        var idempotentProc = IdempotentProc.wrap(delegate, dedupCacheSize);
        return new Fixture(idempotentProc, received, delegate);
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void tell_deduplicatesIdempotentMessages() throws InterruptedException {
        var fixture = buildFixture(100);
        var proc = fixture.idempotentProc();
        var received = fixture.received();
        var delegate = fixture.delegate();

        try {
            // Send the same key twice — handler should only be invoked once.
            proc.tell(new Cmd.IdempotentCmd("key-A", "first-delivery"));
            proc.tell(new Cmd.IdempotentCmd("key-A", "duplicate-delivery"));

            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !received.isEmpty());

            // Give the proc a moment to process any potential second message
            Thread.sleep(100);

            assertThat(received).hasSize(1);
            assertThat(received).containsExactly("first-delivery");
        } finally {
            delegate.stop();
        }
    }

    @Test
    void tell_processesDistinctKeys() throws InterruptedException {
        var fixture = buildFixture(100);
        var proc = fixture.idempotentProc();
        var received = fixture.received();
        var delegate = fixture.delegate();

        try {
            proc.tell(new Cmd.IdempotentCmd("key-1", "payload-one"));
            proc.tell(new Cmd.IdempotentCmd("key-2", "payload-two"));

            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);

            assertThat(received).containsExactlyInAnyOrder("payload-one", "payload-two");
        } finally {
            delegate.stop();
        }
    }

    @Test
    void tell_nonIdempotentMessages_alwaysDelivered() throws InterruptedException {
        var fixture = buildFixture(100);
        var proc = fixture.idempotentProc();
        var received = fixture.received();
        var delegate = fixture.delegate();

        try {
            // Send the same plain message three times — all three should be delivered.
            proc.tell(new Cmd.PlainCmd("event"));
            proc.tell(new Cmd.PlainCmd("event"));
            proc.tell(new Cmd.PlainCmd("event"));

            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 3);

            assertThat(received).hasSize(3);
            assertThat(received).containsExactly("event", "event", "event");
        } finally {
            delegate.stop();
        }
    }
}
