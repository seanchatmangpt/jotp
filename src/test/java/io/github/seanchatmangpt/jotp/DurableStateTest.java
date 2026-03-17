package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.persistence.JsonSnapshotCodec;
import io.github.seanchatmangpt.jotp.persistence.TestAtomicStateWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DurableState}.
 *
 * <p>Verifies durable state management with automatic snapshots, recovery, and persistence.
 */
@DisplayName("DurableState Tests")
class DurableStateTest {
    @TempDir Path tempDir;

    private Path snapshotFile;

    @BeforeEach
    void setUp() {
        snapshotFile = tempDir.resolve("test-snapshot.json");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(snapshotFile)) {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("Should create durable state with initial value")
    void create_initializesWithInitialState() {
        DurableState<String> durable =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        assertThat(durable.state()).isEqualTo("initial");
    }

    @Test
    @DisplayName("Should update state and trigger snapshot")
    void update_modifiesStateAndTriggersSnapshot() throws IOException {
        DurableState<String> durable =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        durable.update("updated");

        assertThat(durable.state()).isEqualTo("updated");
        assertThat(Files.exists(snapshotFile)).isTrue();
        assertThat(Files.readString(snapshotFile)).contains("\"value\":\"updated\"");
    }

    @Test
    @DisplayName("Should recover state from snapshot on creation")
    void create_recoversFromSnapshot() throws IOException {
        // Create initial durable state and update it
        DurableState<String> durable1 =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));
        durable1.update("persisted-state");

        // Verify snapshot was written
        assertThat(Files.exists(snapshotFile)).isTrue();

        // Create new durable state instance that should recover from snapshot
        DurableState<String> durable2 =
                DurableState.create(
                        "ignored-initial", // This should be ignored
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        assertThat(durable2.state()).isEqualTo("persisted-state");
    }

    @Test
    @DisplayName("Should handle complex record state")
    void update_handlesComplexRecordState() throws IOException {
        record Person(String name, int age) {}

        DurableState<Person> durable =
                DurableState.create(
                        new Person("Alice", 30),
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        durable.update(new Person("Bob", 35));

        assertThat(durable.state().name()).isEqualTo("Bob");
        assertThat(durable.state().age()).isEqualTo(35);
        assertThat(Files.readString(snapshotFile))
                .contains("\"name\":\"Bob\"")
                .contains("\"age\":35");
    }

    @Test
    @DisplayName("Should support snapshot interval")
    void create_respectsSnapshotInterval() throws IOException, InterruptedException {
        DurableState<Integer> durable =
                DurableState.create(
                        0,
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile),
                        Duration.ofMillis(100) // Snapshot every 100ms
                        );

        durable.update(1);
        // Wait for snapshot interval
        Thread.sleep(200);

        assertThat(Files.exists(snapshotFile)).isTrue();
    }

    @Test
    @DisplayName("Should handle multiple rapid updates")
    void update_handlesMultipleRapidUpdates() throws IOException {
        DurableState<Integer> durable =
                DurableState.create(
                        0, new JsonSnapshotCodec(), new TestAtomicStateWriter(snapshotFile));

        for (int i = 1; i <= 100; i++) {
            durable.update(i);
        }

        assertThat(durable.state()).isEqualTo(100);
        assertThat(Files.exists(snapshotFile)).isTrue();
    }

    @Test
    @DisplayName("Should handle null state recovery when no snapshot exists")
    void create_handlesNoSnapshot() {
        // Create without existing snapshot
        DurableState<String> durable =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        assertThat(durable.state()).isEqualTo("initial");
        assertThat(Files.exists(snapshotFile)).isFalse();
    }

    @Test
    @DisplayName("Should handle corrupted snapshot gracefully")
    void create_handlesCorruptedSnapshot() throws IOException {
        // Create corrupted snapshot file
        Files.writeString(snapshotFile, "corrupted-json-data");

        DurableState<String> durable =
                DurableState.create(
                        "fallback",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        // Should fall back to initial state
        assertThat(durable.state()).isEqualTo("fallback");
    }

    @Test
    @DisplayName("Should support custom state types")
    void update_supportsCustomStateTypes() throws IOException {
        enum Status {
            PENDING,
            PROCESSING,
            COMPLETE
        }

        DurableState<Status> durable =
                DurableState.create(
                        Status.PENDING,
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        durable.update(Status.PROCESSING);
        assertThat(durable.state()).isEqualTo(Status.PROCESSING);

        durable.update(Status.COMPLETE);
        assertThat(durable.state()).isEqualTo(Status.COMPLETE);
    }

    @Test
    @DisplayName("Should handle concurrent state updates")
    void update_handlesConcurrentUpdates() throws InterruptedException, IOException {
        DurableState<Integer> durable =
                DurableState.create(
                        0, new JsonSnapshotCodec(), new TestAtomicStateWriter(snapshotFile));

        var threads = new java.util.ArrayList<Thread>();
        var updatesPerThread = 10;

        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            var thread =
                    new Thread(
                            () -> {
                                for (int j = 0; j < updatesPerThread; j++) {
                                    durable.update(durable.state() + 1);
                                }
                            });
            threads.add(thread);
            thread.start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertThat(durable.state()).isEqualTo(5 * updatesPerThread);
    }

    @Test
    @DisplayName("Should provide thread-safe state access")
    void state_isThreadSafe() throws InterruptedException {
        DurableState<String> durable =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        var latch = new java.util.concurrent.CountDownLatch(10);
        var results = new CopyOnWriteArrayList<String>();

        for (int i = 0; i < 10; i++) {
            new Thread(
                            () -> {
                                try {
                                    results.add(durable.state());
                                } finally {
                                    latch.countDown();
                                }
                            })
                    .start();
        }

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(results).containsOnly("initial");
    }

    @Test
    @DisplayName("Should handle empty string state")
    void update_handlesEmptyString() throws IOException {
        DurableState<String> durable =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        durable.update("");

        assertThat(durable.state()).isEqualTo("");
    }

    @Test
    @DisplayName("Should handle state with special characters")
    void update_handlesSpecialCharacters() throws IOException {
        String specialState = "State with \"quotes\", \n newlines, and \t tabs";

        DurableState<String> durable =
                DurableState.create(
                        "initial",
                        new JsonSnapshotCodec(),
                        new TestAtomicStateWriter(snapshotFile));

        durable.update(specialState);

        assertThat(durable.state()).isEqualTo(specialState);
    }
}
