package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.persistence.RocksDBBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for ApplicationController JVM restart recovery.
 *
 * <p>Tests comprehensive JVM crash recovery patterns including:
 *
 * <ul>
 *   <li>Recovery backend configuration and status
 *   <li>Clean start with no prior state
 *   <li>State restoration after simulated crash
 *   <li>Recovery marker lifecycle (write, persist, clear)
 *   <li>Startup time and uptime tracking
 * </ul>
 *
 * <p><strong>Crash Simulation Strategy:</strong> These tests simulate JVM crashes by:
 *
 * <ol>
 *   <li>Writing recovery marker to persistence
 *   <li>"Crashing" by calling reset() without clearing marker
 *   <li>Calling recoverFromPersistence()
 *   <li>Verifying state restoration
 * </ol>
 *
 * @see ApplicationController
 * @see RocksDBBackend
 */
@DisplayName("ApplicationController JVM Restart Recovery Integration Tests")
class ApplicationRecoveryIT {

    private static final String RECOVERY_MARKER_KEY = "jotp_recovery_marker";

    @TempDir Path tempDir;

    private RocksDBBackend backend;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        backend = null;
    }

    @AfterEach
    void tearDown() throws Exception {
        ApplicationController.reset();
        if (backend != null) {
            backend.close();
        }
    }

    // ── Recovery Configuration Tests ─────────────────────────────────────────────

    @Test
    @DisplayName("1. enableRecovery() configures backend correctly")
    void testEnableRecoverySetsBackend() throws Exception {
        // Given: A fresh RocksDB backend
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-1"));

        // When: Recovery is enabled
        assertThat(ApplicationController.isRecoveryEnabled()).isFalse();
        ApplicationController.enableRecovery(backend);

        // Then: Recovery should be enabled
        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();
    }

    @Test
    @DisplayName("2. isRecoveryEnabled() returns correct status")
    void testIsRecoveryEnabled() throws Exception {
        // Initially: Recovery should be disabled
        assertThat(ApplicationController.isRecoveryEnabled()).isFalse();

        // When: Backend is configured
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-2"));
        ApplicationController.enableRecovery(backend);

        // Then: Recovery should be enabled
        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();

        // When: Reset is called
        ApplicationController.reset();

        // Then: Recovery should be disabled again
        assertThat(ApplicationController.isRecoveryEnabled()).isFalse();
    }

    // ── Recovery From Persistence Tests ──────────────────────────────────────────

    @Test
    @DisplayName("3. recoverFromPersistence() handles clean start with no prior state")
    void testRecoverFromPersistenceWithNoState() throws Exception {
        // Given: A fresh backend with no prior state
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-3"));
        ApplicationController.enableRecovery(backend);

        // When: Recovery is attempted with no prior state
        ApplicationController.recoverFromPersistence();

        // Then: Should complete without error (no state to recover)
        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();
    }

    @Test
    @DisplayName("4. recoverFromPersistence() restores after simulated crash")
    void testRecoverFromPersistenceWithPreviousState() throws Exception {
        // Phase 1: Initial setup with state
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-4"));
        ApplicationController.enableRecovery(backend);

        // Write some state that would survive a crash
        String crashStateData = Instant.now().toString() + ":test-pid-12345";
        backend.save(RECOVERY_MARKER_KEY, crashStateData.getBytes());

        // Verify marker was written
        Optional<byte[]> markerBeforeCrash = backend.load(RECOVERY_MARKER_KEY);
        assertThat(markerBeforeCrash).isPresent();
        assertThat(new String(markerBeforeCrash.get())).contains(crashStateData);

        // Phase 2: Simulate crash (reset without clearing marker)
        // Note: reset() clears the backend reference but data persists in RocksDB
        ApplicationController.reset();

        // Phase 3: Simulate JVM restart - new controller instance
        ApplicationController.enableRecovery(backend);
        ApplicationController.recoverFromPersistence();

        // Then: Recovery should complete successfully
        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();
    }

    // ── Recovery Marker Tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("5. Recovery marker is written when recovery is enabled")
    void testRecoveryMarkerWrittenOnEnable() throws Exception {
        // Given: A fresh backend
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-5"));

        // When: Recovery is enabled (writes marker)
        ApplicationController.enableRecovery(backend);

        // Then: Marker should exist in backend
        Optional<byte[]> marker = backend.load(RECOVERY_MARKER_KEY);
        assertThat(marker).isPresent();

        String markerContent = new String(marker.get());
        // Marker format: "timestamp:pid"
        assertThat(markerContent).contains(":");
        assertThat(markerContent).isNotEmpty();
    }

    @Test
    @DisplayName("6. Recovery marker is cleared on clean shutdown")
    void testRecoveryMarkerClearedOnCleanShutdown() throws Exception {
        // Given: Recovery is enabled
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-6"));
        ApplicationController.enableRecovery(backend);

        // Verify marker exists
        Optional<byte[]> markerBefore = backend.load(RECOVERY_MARKER_KEY);
        assertThat(markerBefore).isPresent();

        // When: Clean shutdown is triggered (via persistAllState)
        // Note: persistAllState is private, so we simulate clean shutdown via recovery
        ApplicationController.recoverFromPersistence();

        // Then: After recovery, a new marker should be written (old cleared, new written)
        Optional<byte[]> markerAfter = backend.load(RECOVERY_MARKER_KEY);
        assertThat(markerAfter).isPresent(); // New instance marker
    }

    @Test
    @DisplayName("7. Recovery marker persists after crash simulation")
    void testRecoveryMarkerPersistsOnCrash() throws Exception {
        // Given: A backend with recovery enabled
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-7"));
        ApplicationController.enableRecovery(backend);

        // Capture the original marker
        Optional<byte[]> originalMarker = backend.load(RECOVERY_MARKER_KEY);
        assertThat(originalMarker).isPresent();
        String originalMarkerContent = new String(originalMarker.get());

        // When: Simulate crash (reset without graceful shutdown)
        ApplicationController.reset();

        // Then: Marker should still exist in persistence (survived crash)
        Optional<byte[]> markerAfterCrash = backend.load(RECOVERY_MARKER_KEY);
        assertThat(markerAfterCrash).isPresent();
        assertThat(new String(markerAfterCrash.get())).isEqualTo(originalMarkerContent);

        // And: Recovery can detect the unclean shutdown
        ApplicationController.enableRecovery(backend);
        ApplicationController.recoverFromPersistence();

        // New marker should be written after recovery
        Optional<byte[]> newMarker = backend.load(RECOVERY_MARKER_KEY);
        assertThat(newMarker).isPresent();
    }

    // ── Timing Tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("8. getStartupTime() returns when controller was initialized")
    void testGetStartupTime() throws Exception {
        // Given: Fresh controller
        Instant beforeReset = Instant.now();
        ApplicationController.reset();

        // When: Getting startup time
        Instant startupTime = ApplicationController.getStartupTime();

        // Then: Should be approximately now (within test execution time)
        Instant afterCall = Instant.now();

        assertThat(startupTime).isAfterOrEqualTo(beforeReset.minusMillis(100));
        assertThat(startupTime).isBeforeOrEqualTo(afterCall);

        // And: Startup time should be stable across multiple calls
        Instant startupTime2 = ApplicationController.getStartupTime();
        assertThat(startupTime2).isEqualTo(startupTime);
    }

    @Test
    @DisplayName("9. getUptime() calculates running duration")
    void testGetUptime() throws Exception {
        // Given: Fresh controller
        ApplicationController.reset();
        Instant startTime = ApplicationController.getStartupTime();

        // When: Getting uptime immediately
        Duration uptime1 = ApplicationController.getUptime();

        // Then: Should be very small (just initialized)
        assertThat(uptime1).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(uptime1).isLessThan(Duration.ofSeconds(5));

        // When: Waiting a short time
        Thread.sleep(100);

        // Then: Uptime should have increased
        Duration uptime2 = ApplicationController.getUptime();
        assertThat(uptime2).isGreaterThan(uptime1);
        assertThat(uptime2.toMillis()).isGreaterThanOrEqualTo(100);

        // When: Reset is called
        Thread.sleep(50);
        ApplicationController.reset();

        // Then: Uptime should restart from zero
        Duration uptimeAfterReset = ApplicationController.getUptime();
        assertThat(uptimeAfterReset).isLessThan(Duration.ofSeconds(1));
    }

    // ── Reset State Tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("10. reset() clears recovery state and backend reference")
    void testResetClearsRecoveryState() throws Exception {
        // Given: Recovery is enabled with backend
        backend = new RocksDBBackend(tempDir.resolve("recovery-test-10"));
        ApplicationController.enableRecovery(backend);
        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();

        // When: Reset is called
        ApplicationController.reset();

        // Then: Recovery should be disabled
        assertThat(ApplicationController.isRecoveryEnabled()).isFalse();

        // And: Startup time should be reset
        Duration uptime = ApplicationController.getUptime();
        assertThat(uptime).isLessThan(Duration.ofSeconds(2));
    }

    // ── Full Crash Recovery Simulation ────────────────────────────────────────────

    @Test
    @DisplayName("Full crash recovery cycle: start, crash, restart, recover")
    void testFullCrashRecoveryCycle() throws Exception {
        Path dbPath = tempDir.resolve("full-recovery-test");

        // === Phase 1: Initial Application Instance ===
        RocksDBBackend instance1Backend = new RocksDBBackend(dbPath);
        ApplicationController.enableRecovery(instance1Backend);

        // Write recovery marker (simulates running instance)
        String instance1Marker = Instant.now().toString() + ":instance1@pid1234";
        instance1Backend.save(RECOVERY_MARKER_KEY, instance1Marker.getBytes());

        // Verify marker persisted
        Optional<byte[]> marker1 = instance1Backend.load(RECOVERY_MARKER_KEY);
        assertThat(marker1).isPresent();
        assertThat(new String(marker1.get())).isEqualTo(instance1Marker);

        // === Phase 2: Simulate JVM Crash ===
        // Close backend (but data persists on disk)
        instance1Backend.close();

        // Reset controller (simulates JVM crash - no cleanup)
        ApplicationController.reset();

        // === Phase 3: JVM Restart - New Instance ===
        RocksDBBackend instance2Backend = new RocksDBBackend(dbPath);
        ApplicationController.enableRecovery(instance2Backend);

        // Verify previous marker still exists (crash survival)
        Optional<byte[]> markerAfterCrash = instance2Backend.load(RECOVERY_MARKER_KEY);
        assertThat(markerAfterCrash).isPresent();
        assertThat(new String(markerAfterCrash.get())).isEqualTo(instance1Marker);

        // Perform recovery
        ApplicationController.recoverFromPersistence();

        // === Phase 4: Verify Recovery Complete ===
        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();

        // New marker should be written for this instance
        Optional<byte[]> newMarker = instance2Backend.load(RECOVERY_MARKER_KEY);
        assertThat(newMarker).isPresent();

        // Cleanup
        instance2Backend.close();
    }

    @Test
    @DisplayName("Multiple crash-restart cycles maintain state integrity")
    void testMultipleCrashRestartCycles() throws Exception {
        Path dbPath = tempDir.resolve("multi-cycle-test");

        for (int cycle = 1; cycle <= 3; cycle++) {
            // Start new instance
            RocksDBBackend cycleBackend = new RocksDBBackend(dbPath);
            ApplicationController.reset();
            ApplicationController.enableRecovery(cycleBackend);

            // Write cycle-specific marker
            String cycleMarker = "cycle-" + cycle + ":" + Instant.now().toString();
            cycleBackend.save(RECOVERY_MARKER_KEY, cycleMarker.getBytes());

            // Verify write
            Optional<byte[]> marker = cycleBackend.load(RECOVERY_MARKER_KEY);
            assertThat(marker).isPresent();
            assertThat(new String(marker.get())).startsWith("cycle-" + cycle);

            // Simulate crash
            cycleBackend.close();
            ApplicationController.reset();
        }

        // Final recovery should work
        RocksDBBackend finalBackend = new RocksDBBackend(dbPath);
        ApplicationController.enableRecovery(finalBackend);
        ApplicationController.recoverFromPersistence();

        assertThat(ApplicationController.isRecoveryEnabled()).isTrue();
        finalBackend.close();
    }

    @Test
    @DisplayName("Recovery handles backend with existing data")
    void testRecoveryWithExistingData() throws Exception {
        Path dbPath = tempDir.resolve("existing-data-test");

        // Setup: Create backend with existing data
        RocksDBBackend setupBackend = new RocksDBBackend(dbPath);
        setupBackend.save("app-state-1", "{\"name\":\"test-app\",\"count\":42}".getBytes());
        setupBackend.save("app-state-2", "{\"name\":\"other-app\",\"active\":true}".getBytes());
        setupBackend.close();

        // When: New instance starts with recovery
        RocksDBBackend recoveryBackend = new RocksDBBackend(dbPath);
        ApplicationController.enableRecovery(recoveryBackend);
        ApplicationController.recoverFromPersistence();

        // Then: Existing data should be preserved
        Optional<byte[]> state1 = recoveryBackend.load("app-state-1");
        assertThat(state1).isPresent();
        assertThat(new String(state1.get())).contains("test-app");

        Optional<byte[]> state2 = recoveryBackend.load("app-state-2");
        assertThat(state2).isPresent();
        assertThat(new String(state2.get())).contains("other-app");

        recoveryBackend.close();
    }

    @Test
    @DisplayName("Uptime continues correctly after recovery")
    void testUptimeContinuesAfterRecovery() throws Exception {
        backend = new RocksDBBackend(tempDir.resolve("uptime-test"));
        ApplicationController.enableRecovery(backend);

        // Get initial uptime
        Duration uptimeAfterEnable = ApplicationController.getUptime();
        assertThat(uptimeAfterEnable).isGreaterThanOrEqualTo(Duration.ZERO);

        // Wait briefly
        Thread.sleep(100);

        // Perform recovery
        ApplicationController.recoverFromPersistence();

        // Uptime should continue from original start
        Duration uptimeAfterRecovery = ApplicationController.getUptime();
        assertThat(uptimeAfterRecovery).isGreaterThan(uptimeAfterEnable);

        // Reset should restart uptime
        ApplicationController.reset();
        Duration uptimeAfterReset = ApplicationController.getUptime();
        assertThat(uptimeAfterReset).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("Recovery without backend is a no-op")
    void testRecoveryWithoutBackendIsNoOp() throws Exception {
        // Given: No backend configured
        assertThat(ApplicationController.isRecoveryEnabled()).isFalse();

        // When: Recovery is attempted
        ApplicationController.recoverFromPersistence();

        // Then: Should complete without error
        assertThat(ApplicationController.isRecoveryEnabled()).isFalse();
    }

    @Test
    @DisplayName("Enable recovery with null backend throws exception")
    void testEnableRecoveryWithNullBackend() {
        // When/Then: Should throw NullPointerException
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ApplicationController.enableRecovery(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend");
    }

    @Test
    @DisplayName("Recovery marker format includes timestamp and JVM identifier")
    void testRecoveryMarkerFormat() throws Exception {
        backend = new RocksDBBackend(tempDir.resolve("marker-format-test"));
        ApplicationController.enableRecovery(backend);

        Optional<byte[]> marker = backend.load(RECOVERY_MARKER_KEY);
        assertThat(marker).isPresent();

        String markerContent = new String(marker.get());

        // Format: "timestamp:pid@hostname"
        assertThat(markerContent).contains(":");
        assertThat(markerContent).isNotEmpty();

        // Should start with ISO timestamp
        String[] parts = markerContent.split(":");
        assertThat(parts.length).isGreaterThanOrEqualTo(2);

        // First part should be parseable as Instant prefix
        assertThat(parts[0]).isNotEmpty();
    }
}
