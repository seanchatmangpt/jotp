package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for idempotence framework components.
 *
 * <p>Verifies message deduplication, crash recovery, and idempotent processing patterns.
 */
@DisplayName("Idempotence Framework Tests")
class IdempotenceTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Test Messages ────────────────────────────────────────────────────────

    sealed interface TestMsg permits TestMsg.IdempotentMsg, TestMsg.PlainMsg {

        record IdempotentMsg(String key, String payload)
                implements TestMsg, IdempotentProc.Idempotent {
            @Override
            public String idempotencyKey() {
                return key;
            }
        }

        record PlainMsg(String payload) implements TestMsg {}
    }

    // ── IdempotentProc Tests ────────────────────────────────────────────────

    @Test
    @DisplayName("IdempotentProc should deduplicate messages with same key")
    void idempotentProc_deduplicatesMessages() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        received,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add(payload);
                            } else if (msg instanceof TestMsg.PlainMsg(var payload)) {
                                state.add(payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        try {
            // Send same key twice
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "first"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "second"));

            // Wait for processing
            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !received.isEmpty());

            // Only first should be processed
            assertThat(received).containsExactly("first");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("IdempotentProc should process different keys")
    void idempotentProc_processesDifferentKeys() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        received,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add(payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        try {
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "payload-1"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-2", "payload-2"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-3", "payload-3"));

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 3);

            assertThat(received).containsExactlyInAnyOrder("payload-1", "payload-2", "payload-3");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("IdempotentProc should always forward non-idempotent messages")
    void idempotentProc_forwardsNonIdempotentMessages() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        received,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.PlainMsg(var payload)) {
                                state.add(payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        try {
            idempotentProc.tell(new TestMsg.PlainMsg("event-1"));
            idempotentProc.tell(new TestMsg.PlainMsg("event-2"));
            idempotentProc.tell(new TestMsg.PlainMsg("event-3"));

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 3);

            assertThat(received).containsExactlyInAnyOrder("event-1", "event-2", "event-3");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("IdempotentProc should evict old keys from cache")
    void idempotentProc_evictsOldKeys() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        received,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add(payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 3); // Small cache

        try {
            // Fill cache
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "payload-1"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-2", "payload-2"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-3", "payload-3"));

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 3);

            // Add more keys to evict first
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-4", "payload-4"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-5", "payload-5"));

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 5);

            // key-1 should be evicted, so this should be processed
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "payload-1-new"));

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 6);

            assertThat(received).contains("payload-1-new");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("IdempotentProc should handle concurrent duplicate detection")
    void idempotentProc_handlesConcurrentDuplicates() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        received,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add(payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        try {
            // Send same key from multiple threads
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < 10; i++) {
                final int index = i;
                var thread =
                        new Thread(
                                () -> {
                                    idempotentProc.tell(
                                            new TestMsg.IdempotentMsg(
                                                    "shared-key", "payload-" + index));
                                });
                threads.add(thread);
                thread.start();
            }

            for (var thread : threads) {
                thread.join();
            }

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !received.isEmpty());

            // Only one should be processed
            assertThat(received).hasSize(1);
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("IdempotentProc should throw on null proc")
    void idempotentProc_throwsOnNullProc() {
        assertThatThrownBy(() -> IdempotentProc.wrap(null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proc must not be null");
    }

    @Test
    @DisplayName("IdempotentProc should throw on invalid cache size")
    void idempotentProc_throwsOnInvalidCacheSize() {
        var proc = new Proc<>(new CopyOnWriteArrayList<>(), (state, msg) -> state);

        assertThatThrownBy(() -> IdempotentProc.wrap(proc, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedupCacheSize must be positive");

        assertThatThrownBy(() -> IdempotentProc.wrap(proc, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedupCacheSize must be positive");
    }

    @Test
    @DisplayName("IdempotentProc should delegate ask calls")
    void idempotentProc_delegatesAskCalls() throws Exception {
        var proc =
                new Proc<>(
                        "initial-state",
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg) {
                                return "updated-state";
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        try {
            String result =
                    idempotentProc.ask(
                            new TestMsg.IdempotentMsg("key", "payload"), Duration.ofSeconds(2));

            assertThat(result).isEqualTo("updated-state");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("IdempotentProc should provide access to delegate")
    void idempotentProc_providesDelegate() {
        var proc = new Proc<>("state", (state, msg) -> state);

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        assertThat(idempotentProc.delegate()).isEqualTo(proc);
    }

    // ── Idempotence Framework Integration Tests ────────────────────────────────

    @Test
    @DisplayName("Should support idempotent message processing")
    void framework_supportsIdempotentProcessing() throws InterruptedException {
        var processed = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        processed,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add("processed:" + payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 50);

        try {
            // Simulate retry scenario
            idempotentProc.tell(new TestMsg.IdempotentMsg("order-123", "create-order"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("order-123", "create-order")); // Retry
            idempotentProc.tell(
                    new TestMsg.IdempotentMsg("order-123", "create-order")); // Another retry

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !processed.isEmpty());

            // Order should be created only once
            assertThat(processed).containsExactly("processed:create-order");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("Should support mixed idempotent and non-idempotent messages")
    void framework_supportsMixedMessages() throws InterruptedException {
        var processed = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        processed,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add("idempotent:" + payload);
                            } else if (msg instanceof TestMsg.PlainMsg(var payload)) {
                                state.add("plain:" + payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 100);

        try {
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "cmd-1"));
            idempotentProc.tell(new TestMsg.PlainMsg("event-1"));
            idempotentProc.tell(new TestMsg.IdempotentMsg("key-1", "cmd-1")); // Duplicate
            idempotentProc.tell(new TestMsg.PlainMsg("event-2"));

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> processed.size() == 3);

            assertThat(processed)
                    .containsExactlyInAnyOrder(
                            "idempotent:cmd-1", "plain:event-1", "plain:event-2");
        } finally {
            proc.stop();
        }
    }

    @Test
    @DisplayName("Should handle high-throughput deduplication")
    void framework_handlesHighThroughput() throws InterruptedException {
        var processed = new CopyOnWriteArrayList<String>();
        var proc =
                new Proc<>(
                        processed,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.IdempotentMsg(var key, var payload)) {
                                state.add(payload);
                            }
                            return state;
                        });

        var idempotentProc = IdempotentProc.wrap(proc, 1000);

        try {
            // Send many messages with some duplicates
            for (int i = 0; i < 100; i++) {
                String key = "key-" + (i % 10); // 10 unique keys
                idempotentProc.tell(new TestMsg.IdempotentMsg(key, "payload-" + i));
            }

            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> processed.size() == 10);

            // Only unique keys should be processed
            assertThat(processed).hasSize(10);
        } finally {
            proc.stop();
        }
    }
}
