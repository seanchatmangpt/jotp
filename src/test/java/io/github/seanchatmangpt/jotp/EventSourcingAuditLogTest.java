package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive tests for {@link EventSourcingAuditLog} — event sourcing and audit trails for
 * StateMachine transitions.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Async logging via Proc (fire-and-forget)
 *   <li>Sealed audit entry types (StateChange, ErrorEntry, Replay)
 *   <li>History replay and state reconstruction
 *   <li>Multiple backend implementations (in-memory, file)
 *   <li>Time-range queries and filtering
 *   <li>Concurrent logging with thread safety
 * </ul>
 *
 * @see EventSourcingAuditLog
 * @see StateMachine
 */
@Timeout(10)
@DisplayName("EventSourcingAuditLog: Event Sourcing for State Machines")
class EventSourcingAuditLogTest implements WithAssertions {

    // ─── Domain Model (Code Lock) ─────────────────────────────────────────

    sealed interface LockState permits LockState.Locked, LockState.Open {
        record Locked() implements LockState {}

        record Open() implements LockState {}
    }

    sealed interface LockEvent permits LockEvent.PushButton, LockEvent.Lock {
        record PushButton(char button) implements LockEvent {}

        record Lock() implements LockEvent {}
    }

    record LockData(String code, String entered) {
        LockData withEntered(String e) {
            return new LockData(code, e);
        }
    }

    // ─── Test Fixtures ────────────────────────────────────────────────────

    private static final String ENTITY_ID = "lock-001";
    private static final String CODE = "1234";

    private EventSourcingAuditLog<LockState, LockEvent, LockData> log;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        log =
                EventSourcingAuditLog.<LockState, LockEvent, LockData>builder()
                        .entityId(ENTITY_ID)
                        .backend(new EventSourcingAuditLog.InMemoryBackend())
                        .build();
    }

    // ─── Async Logging Tests ──────────────────────────────────────────────

    @Test
    @DisplayName("logTransition: logs state transitions asynchronously")
    void logTransition_logsStateChangeEntry() throws InterruptedException {
        // Act
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, "1234"));

        // Allow async logger to process (small delay)
        Thread.sleep(100);

        // Assert
        var history = log.replayHistory(ENTITY_ID);
        assertThat(history)
                .hasSize(1)
                .allMatch(e -> e instanceof EventSourcingAuditLog.StateChange);

        var change = (EventSourcingAuditLog.StateChange<?, ?, ?>) history.get(0);
        assertThat(change.entityId()).isEqualTo(ENTITY_ID);
        assertThat(change.fromState()).isEqualTo(LockState.Locked.class);
        assertThat(change.toState()).isEqualTo(LockState.Open.class);
    }

    @Test
    @DisplayName("logTransition: multiple transitions are recorded in order")
    void logTransition_multipleTransitionsInOrder() throws InterruptedException {
        // Act
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "12"));

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);

        // Assert
        var history = log.replayHistory(ENTITY_ID);
        assertThat(history).hasSize(3);

        // Verify order by checking entered field progression
        assertThat(history.get(0))
                .isInstanceOf(EventSourcingAuditLog.StateChange.class)
                .extracting(
                        e ->
                                ((EventSourcingAuditLog.StateChange<?, ?, LockData>) e)
                                        .data()
                                        .entered())
                .isEqualTo("1");

        assertThat(history.get(1))
                .isInstanceOf(EventSourcingAuditLog.StateChange.class)
                .extracting(
                        e ->
                                ((EventSourcingAuditLog.StateChange<?, ?, LockData>) e)
                                        .data()
                                        .entered())
                .isEqualTo("12");
    }

    @Test
    @DisplayName("logError: records exceptions during processing")
    void logError_recordsExceptionEntry() throws InterruptedException {
        // Act
        var exception = new IllegalArgumentException("Invalid code");
        log.logError(ENTITY_ID, LockState.Locked.class, exception);

        Thread.sleep(100);

        // Assert
        var history = log.replayHistory(ENTITY_ID);
        assertThat(history).hasSize(1).allMatch(e -> e instanceof EventSourcingAuditLog.ErrorEntry);

        var error = (EventSourcingAuditLog.ErrorEntry<?, ?>) history.get(0);
        assertThat(error.entityId()).isEqualTo(ENTITY_ID);
        assertThat(error.state()).isEqualTo(LockState.Locked.class);
        assertThat(error.exception()).isInstanceOf(IllegalArgumentException.class);
    }

    // ─── History Replay Tests ──────────────────────────────────────────────

    @Test
    @DisplayName("replayHistory: retrieves all entries in chronological order")
    void replayHistory_retrievesAllEntries() throws InterruptedException {
        // Arrange
        var data1 = new LockData(CODE, "1");
        var data2 = new LockData(CODE, "12");

        // Act
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                data1);

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                data2);

        Thread.sleep(100);

        // Assert
        var history = log.replayHistory(ENTITY_ID);
        assertThat(history).hasSize(2);

        assertThat(history.get(0).timestamp()).isBeforeOrEqualTo(history.get(1).timestamp());
    }

    @Test
    @DisplayName("replay: reconstructs state by applying transitions")
    void replay_reconstructsStateFromLog() throws InterruptedException {
        // Arrange: log several transitions
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "12"));

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "123"));

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);

        // Act: replay transitions with a simple transition function
        var finalData =
                log.streamHistory(ENTITY_ID)
                        .filter(e -> e instanceof EventSourcingAuditLog.StateChange)
                        .map(
                                e ->
                                        (EventSourcingAuditLog.StateChange<
                                                        LockState, LockEvent, LockData>)
                                                e)
                        .reduce(
                                new LockData(CODE, ""),
                                (data, change) -> change.data(),
                                (d1, d2) -> d2);

        // Assert: final state should match the last logged entry
        assertThat(finalData.entered()).isEmpty();
    }

    @Test
    @DisplayName("streamHistory: streams entries without loading all into memory")
    void streamHistory_streamsEntriesLazily() throws InterruptedException {
        // Arrange
        for (int i = 0; i < 5; i++) {
            log.logTransition(
                    ENTITY_ID,
                    LockState.Locked.class,
                    LockEvent.PushButton.class,
                    LockState.Locked.class,
                    new LockData(CODE, String.valueOf(i)));
        }

        Thread.sleep(100);

        // Act & Assert: stream should match replay count
        var count = log.streamHistory(ENTITY_ID).count();
        assertThat(count).isEqualTo(5);
    }

    // ─── Time-Range Query Tests ───────────────────────────────────────────

    @Test
    @DisplayName("entriesInRange: filters entries by timestamp")
    void entriesInRange_filtersByTime() throws InterruptedException {
        // Arrange
        var before = Instant.now();

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        Thread.sleep(100);
        var mid = Instant.now();
        Thread.sleep(100);

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "12"));

        var after = Instant.now();

        Thread.sleep(100);

        // Act
        var inRange = log.entriesInRange(ENTITY_ID, before, after);

        // Assert: both entries should be in range
        assertThat(inRange).hasSize(2);

        // Entries before mid should only include the first
        var beforeMid = log.entriesInRange(ENTITY_ID, before, mid);
        assertThat(beforeMid).hasSize(1);
    }

    @Test
    @DisplayName("entriesInRange: returns empty list for non-existent time ranges")
    void entriesInRange_emptyForNoMatches() throws InterruptedException {
        // Arrange
        var now = Instant.now();
        var future = now.plusSeconds(1000);

        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        Thread.sleep(100);

        // Act
        var inRange = log.entriesInRange(ENTITY_ID, future, future.plusSeconds(100));

        // Assert
        assertThat(inRange).isEmpty();
    }

    // ─── Multiple Entity Tests ────────────────────────────────────────────

    @Test
    @DisplayName("logTransition: separate entities have independent logs")
    void logTransition_multiplEntitiesAreIndependent() throws InterruptedException {
        // Arrange
        var lock2 = "lock-002";

        // Act
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        log.logTransition(
                lock2,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);

        // Assert
        var history1 = log.replayHistory(ENTITY_ID);
        var history2 = log.replayHistory(lock2);

        assertThat(history1).hasSize(1);
        assertThat(history2).hasSize(1);

        // Verify they have different data
        var change1 = (EventSourcingAuditLog.StateChange<?, ?, LockData>) history1.get(0);
        var change2 = (EventSourcingAuditLog.StateChange<?, ?, LockData>) history2.get(0);

        assertThat(change1.data().entered()).isEqualTo("1");
        assertThat(change2.toState()).isEqualTo(LockState.Open.class);
    }

    // ─── Sealed Type Pattern Matching ──────────────────────────────────────

    @Test
    @DisplayName("replayHistory: pattern matching on sealed audit entry types")
    void replayHistory_patternMatchingOnSealedTypes() throws InterruptedException {
        // Arrange
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        log.logError(ENTITY_ID, LockState.Locked.class, new RuntimeException("test"));

        Thread.sleep(100);

        // Act & Assert: count entry types using pattern matching
        var entries = log.replayHistory(ENTITY_ID);

        var stateChanges = 0;
        var errors = 0;

        for (var entry : entries) {
            switch (entry) {
                case EventSourcingAuditLog.StateChange<?, ?, ?> sc -> stateChanges++;
                case EventSourcingAuditLog.ErrorEntry<?, ?> ee -> errors++;
                case EventSourcingAuditLog.Replay<?, ?, ?> r -> {
                    // Not used in this test
                }
                case EventSourcingAuditLog.SnapshotEntry<?, ?> se -> {
                    // Not used in this test
                }
            }
        }

        assertThat(stateChanges).isEqualTo(1);
        assertThat(errors).isEqualTo(1);
    }

    // ─── Builder Tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("builder: creates log with custom entity ID and backend")
    void builder_configuresEntityIdAndBackend() throws InterruptedException {
        // Arrange
        var customId = "custom-entity-123";
        var customLog =
                EventSourcingAuditLog.<LockState, LockEvent, LockData>builder()
                        .entityId(customId)
                        .backend(new EventSourcingAuditLog.InMemoryBackend())
                        .build();

        // Act
        customLog.logTransition(
                customId,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);

        // Assert
        var history = customLog.replayHistory(customId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).entityId()).isEqualTo(customId);
    }

    // ─── File Backend Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("FileBackend: persists entries to file")
    void fileBackend_persistsEntriesToFile(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        // Arrange
        var filePath = tempDir.resolve("audit.log");
        var fileLog =
                EventSourcingAuditLog.<LockState, LockEvent, LockData>builder()
                        .entityId(ENTITY_ID)
                        .backend(new EventSourcingAuditLog.FileBackend(filePath))
                        .build();

        // Act
        fileLog.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);

        // Assert: file should exist with content
        assertThat(filePath).exists();
        var lines = Files.readAllLines(filePath);
        assertThat(lines).isNotEmpty();

        // Cleanup
        fileLog.close();
    }

    @Test
    @DisplayName("FileBackend: reads entries from file")
    void fileBackend_readsEntriesFromFile(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        // Arrange
        var filePath = tempDir.resolve("audit.log");

        // Create first log, write entries
        var log1 =
                EventSourcingAuditLog.<LockState, LockEvent, LockData>builder()
                        .entityId(ENTITY_ID)
                        .backend(new EventSourcingAuditLog.FileBackend(filePath))
                        .build();

        log1.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);
        log1.close();

        // Create second log, read same file
        var log2 =
                EventSourcingAuditLog.<LockState, LockEvent, LockData>builder()
                        .entityId(ENTITY_ID)
                        .backend(new EventSourcingAuditLog.FileBackend(filePath))
                        .build();

        // Act
        var history = log2.replayHistory(ENTITY_ID);

        // Assert
        assertThat(history).isNotEmpty();

        // Cleanup
        log2.close();
    }

    // ─── Concurrent Logging Tests ─────────────────────────────────────────

    @Test
    @DisplayName("logTransition: concurrent logging from multiple threads")
    void logTransition_concurrentLogging() throws InterruptedException {
        // Arrange
        var threads = 10;
        var logsPerThread = 5;

        // Act
        var threadObjs = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int threadNum = t;
            threadObjs[t] =
                    new Thread(
                            () -> {
                                try {
                                    for (int i = 0; i < logsPerThread; i++) {
                                        log.logTransition(
                                                ENTITY_ID,
                                                LockState.Locked.class,
                                                LockEvent.PushButton.class,
                                                LockState.Locked.class,
                                                new LockData(
                                                        CODE,
                                                        String.valueOf(
                                                                threadNum * logsPerThread + i)));
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            threadObjs[t].start();
        }

        // Wait for all threads
        for (var t : threadObjs) {
            t.join();
        }

        Thread.sleep(500); // Allow logger process to catch up

        // Assert: all entries should be recorded
        var history = log.replayHistory(ENTITY_ID);
        assertThat(history).hasSize(threads * logsPerThread);
    }

    // ─── Close/Cleanup Tests ──────────────────────────────────────────────

    @Test
    @DisplayName("close: gracefully shuts down audit logger process")
    void close_shutsDownLogger() throws InterruptedException {
        // Arrange
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                new LockData(CODE, ""));

        Thread.sleep(100);

        // Act & Assert: should not throw
        assertThatCode(() -> log.close()).doesNotThrowAnyException();
    }

    // ─── Integration with StateMachine ────────────────────────────────────

    @Test
    @DisplayName("Integration: audit log with live StateMachine")
    void integration_auditLogWithStateMachine() throws InterruptedException {
        // Arrange
        var sm =
                new StateMachine<LockState, LockEvent, LockData>(
                        new LockState.Locked(),
                        new LockData(CODE, ""),
                        (state, event, data) ->
                                switch (state) {
                                    case LockState.Locked() ->
                                            switch (event) {
                                                case LockEvent.PushButton(var b) -> {
                                                    var entered = data.entered() + b;
                                                    yield entered.equals(data.code())
                                                            ? StateMachine.Transition.nextState(
                                                                    new LockState.Open(),
                                                                    data.withEntered(""))
                                                            : StateMachine.Transition.keepState(
                                                                    data.withEntered(entered));
                                                }
                                                default -> StateMachine.Transition.keepState(data);
                                            };
                                    case LockState.Open() ->
                                            switch (event) {
                                                case LockEvent.Lock() ->
                                                        StateMachine.Transition
                                                                .<LockState, LockData>nextState(
                                                                        new LockState.Locked(),
                                                                        data.withEntered(""));
                                                default ->
                                                        StateMachine.Transition
                                                                .<LockState, LockData>keepState(
                                                                        data);
                                            };
                                });

        // Act: send events and log transitions
        sm.send(new LockEvent.PushButton('1'));
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "1"));

        sm.send(new LockEvent.PushButton('2'));
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                new LockData(CODE, "12"));

        var result = sm.call(new LockEvent.PushButton('3')).join();
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Locked.class,
                result);

        result = sm.call(new LockEvent.PushButton('4')).join();
        log.logTransition(
                ENTITY_ID,
                LockState.Locked.class,
                LockEvent.PushButton.class,
                LockState.Open.class,
                result);

        Thread.sleep(200);

        // Assert: audit log should have recorded all transitions
        var history = log.replayHistory(ENTITY_ID);
        assertThat(history).hasSize(4);

        // Verify progression
        assertThat(history)
                .extracting(
                        e ->
                                ((EventSourcingAuditLog.StateChange<?, ?, LockData>) e)
                                        .data()
                                        .entered())
                .containsExactly("1", "12", "123", "");

        // Cleanup
        sm.stop();
    }
}
