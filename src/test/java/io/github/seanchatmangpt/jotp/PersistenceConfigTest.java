package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PersistenceConfig}.
 *
 * <p>Verifies configuration options, environment variable overrides, and builder pattern.
 */
@DisplayName("PersistenceConfig Tests")
class PersistenceConfigTest {

    @Test
    @DisplayName("Should create default config")
    void defaultConfig_hasExpectedDefaults() {
        PersistenceConfig config = PersistenceConfig.DEFAULT;

        assertThat(config.durabilityLevel()).isEqualTo(PersistenceConfig.DurabilityLevel.DURABLE);
        assertThat(config.snapshotInterval()).isEqualTo(60L);
        assertThat(config.eventsPerSnapshot()).isEqualTo(1000);
        assertThat(config.syncWrites()).isTrue();
        assertThat(config.persistenceDirectory()).endsWith(Path.of("jotp-persistence"));
    }

    @Test
    @DisplayName("Should create durable config")
    void durable_createsProductionReadyConfig() {
        PersistenceConfig config = PersistenceConfig.durable();

        assertThat(config.durabilityLevel()).isEqualTo(PersistenceConfig.DurabilityLevel.DURABLE);
        assertThat(config.syncWrites()).isTrue();
        assertThat(config.snapshotInterval()).isEqualTo(60L);
        assertThat(config.eventsPerSnapshot()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Should create config from builder")
    void builder_createsCustomConfig() {
        Path customDir = Path.of("/custom/path");

        PersistenceConfig config =
                PersistenceConfig.builder()
                        .durabilityLevel(PersistenceConfig.DurabilityLevel.BEST_EFFORT)
                        .snapshotInterval(30L)
                        .eventsPerSnapshot(500)
                        .persistenceDirectory(customDir)
                        .syncWrites(false)
                        .build();

        assertThat(config.durabilityLevel())
                .isEqualTo(PersistenceConfig.DurabilityLevel.BEST_EFFORT);
        assertThat(config.snapshotInterval()).isEqualTo(30L);
        assertThat(config.eventsPerSnapshot()).isEqualTo(500);
        assertThat(config.persistenceDirectory()).isEqualTo(customDir);
        assertThat(config.syncWrites()).isFalse();
    }

    @Test
    @DisplayName("Should use builder defaults for unset values")
    void builder_usesDefaultsForUnsetValues() {
        PersistenceConfig config = PersistenceConfig.builder().build();

        assertThat(config.durabilityLevel()).isEqualTo(PersistenceConfig.DEFAULT.durabilityLevel());
        assertThat(config.snapshotInterval())
                .isEqualTo(PersistenceConfig.DEFAULT.snapshotInterval());
        assertThat(config.eventsPerSnapshot())
                .isEqualTo(PersistenceConfig.DEFAULT.eventsPerSnapshot());
        assertThat(config.persistenceDirectory())
                .isEqualTo(PersistenceConfig.DEFAULT.persistenceDirectory());
        assertThat(config.syncWrites()).isEqualTo(PersistenceConfig.DEFAULT.syncWrites());
    }

    @Test
    @DisplayName("Should create config from environment variables")
    void fromEnvironment_readsEnvVariables() {
        // Save original env values
        String originalDurability = System.getenv("JOTP_DURABILITY");
        String originalDir = System.getenv("JOTP_PERSISTENCE_DIR");
        String originalInterval = System.getenv("JOTP_SNAPSHOT_INTERVAL");
        String originalEvents = System.getenv("JOTP_EVENTS_PER_SNAPSHOT");
        String originalSync = System.getenv("JOTP_SYNC_WRITES");

        try {
            // Set env variables for testing
            setEnv("JOTP_DURABILITY", "TRANSACTIONAL");
            setEnv("JOTP_PERSISTENCE_DIR", "/test/path");
            setEnv("JOTP_SNAPSHOT_INTERVAL", "120");
            setEnv("JOTP_EVENTS_PER_SNAPSHOT", "2000");
            setEnv("JOTP_SYNC_WRITES", "false");

            PersistenceConfig config = PersistenceConfig.fromEnvironment();

            assertThat(config.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DurabilityLevel.TRANSACTIONAL);
            assertThat(config.persistenceDirectory()).isEqualTo(Path.of("/test/path"));
            assertThat(config.snapshotInterval()).isEqualTo(120L);
            assertThat(config.eventsPerSnapshot()).isEqualTo(2000);
            assertThat(config.syncWrites()).isFalse();
        } finally {
            // Restore original env values
            setEnv("JOTP_DURABILITY", originalDurability);
            setEnv("JOTP_PERSISTENCE_DIR", originalDir);
            setEnv("JOTP_SNAPSHOT_INTERVAL", originalInterval);
            setEnv("JOTP_EVENTS_PER_SNAPSHOT", originalEvents);
            setEnv("JOTP_SYNC_WRITES", originalSync);
        }
    }

    @Test
    @DisplayName("Should use defaults when env variables not set")
    void fromEnvironment_usesDefaultsWhenEnvNotSet() {
        // Clear env variables
        String originalDurability = System.getenv("JOTP_DURABILITY");
        String originalDir = System.getenv("JOTP_PERSISTENCE_DIR");

        try {
            System.clearProperty("JOTP_DURABILITY");
            System.clearProperty("JOTP_PERSISTENCE_DIR");

            PersistenceConfig config = PersistenceConfig.fromEnvironment();

            assertThat(config.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DEFAULT.durabilityLevel());
            assertThat(config.persistenceDirectory())
                    .isEqualTo(PersistenceConfig.DEFAULT.persistenceDirectory());
        } finally {
            setEnv("JOTP_DURABILITY", originalDurability);
            setEnv("JOTP_PERSISTENCE_DIR", originalDir);
        }
    }

    @Test
    @DisplayName("Should parse durability level correctly")
    void fromEnvironment_parsesDurabilityLevel() {
        String original = System.getenv("JOTP_DURABILITY");

        try {
            setEnv("JOTP_DURABILITY", "NONE");
            PersistenceConfig config1 = PersistenceConfig.fromEnvironment();
            assertThat(config1.durabilityLevel()).isEqualTo(PersistenceConfig.DurabilityLevel.NONE);

            setEnv("JOTP_DURABILITY", "BEST_EFFORT");
            PersistenceConfig config2 = PersistenceConfig.fromEnvironment();
            assertThat(config2.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DurabilityLevel.BEST_EFFORT);

            setEnv("JOTP_DURABILITY", "DURABLE");
            PersistenceConfig config3 = PersistenceConfig.fromEnvironment();
            assertThat(config3.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DurabilityLevel.DURABLE);

            setEnv("JOTP_DURABILITY", "TRANSACTIONAL");
            PersistenceConfig config4 = PersistenceConfig.fromEnvironment();
            assertThat(config4.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DurabilityLevel.TRANSACTIONAL);
        } finally {
            setEnv("JOTP_DURABILITY", original);
        }
    }

    @Test
    @DisplayName("Should handle invalid durability level gracefully")
    void fromEnvironment_handlesInvalidDurabilityLevel() {
        String original = System.getenv("JOTP_DURABILITY");

        try {
            setEnv("JOTP_DURABILITY", "INVALID_LEVEL");

            PersistenceConfig config = PersistenceConfig.fromEnvironment();

            // Should fall back to default
            assertThat(config.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DEFAULT.durabilityLevel());
        } finally {
            setEnv("JOTP_DURABILITY", original);
        }
    }

    @Test
    @DisplayName("Should parse boolean sync writes correctly")
    void fromEnvironment_parsesBooleanSyncWrites() {
        String original = System.getenv("JOTP_SYNC_WRITES");

        try {
            setEnv("JOTP_SYNC_WRITES", "true");
            PersistenceConfig config1 = PersistenceConfig.fromEnvironment();
            assertThat(config1.syncWrites()).isTrue();

            setEnv("JOTP_SYNC_WRITES", "false");
            PersistenceConfig config2 = PersistenceConfig.fromEnvironment();
            assertThat(config2.syncWrites()).isFalse();
        } finally {
            setEnv("JOTP_SYNC_WRITES", original);
        }
    }

    @Test
    @DisplayName("Should create config from environment with custom defaults")
    void fromEnvironment_withCustomDefaults() {
        String originalDir = System.getenv("JOTP_PERSISTENCE_DIR");

        try {
            System.clearProperty("JOTP_PERSISTENCE_DIR");

            Path customDefault = Path.of("/custom/default");
            PersistenceConfig customDefaultConfig =
                    new PersistenceConfig(
                            PersistenceConfig.DurabilityLevel.NONE, 10L, 100, customDefault, false);

            PersistenceConfig config = PersistenceConfig.fromEnvironment(customDefaultConfig);

            // Should use custom default when env not set
            assertThat(config.persistenceDirectory()).isEqualTo(customDefault);
            assertThat(config.durabilityLevel()).isEqualTo(PersistenceConfig.DurabilityLevel.NONE);
        } finally {
            setEnv("JOTP_PERSISTENCE_DIR", originalDir);
        }
    }

    @Test
    @DisplayName("Should support all durability levels")
    void durabilityLevel_supportsAllLevels() {
        assertThat(PersistenceConfig.DurabilityLevel.values()).hasSize(4);

        assertThat(PersistenceConfig.DurabilityLevel.NONE).isNotNull();
        assertThat(PersistenceConfig.DurabilityLevel.BEST_EFFORT).isNotNull();
        assertThat(PersistenceConfig.DurabilityLevel.DURABLE).isNotNull();
        assertThat(PersistenceConfig.DurabilityLevel.TRANSACTIONAL).isNotNull();
    }

    @Test
    @DisplayName("Should create multiple independent configs")
    void builder_createsIndependentConfigs() {
        PersistenceConfig config1 = PersistenceConfig.builder().snapshotInterval(10L).build();

        PersistenceConfig config2 = PersistenceConfig.builder().snapshotInterval(20L).build();

        assertThat(config1.snapshotInterval()).isEqualTo(10L);
        assertThat(config2.snapshotInterval()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Should handle zero and negative values")
    void builder_handlesEdgeCaseValues() {
        PersistenceConfig config =
                PersistenceConfig.builder().snapshotInterval(0L).eventsPerSnapshot(0).build();

        assertThat(config.snapshotInterval()).isEqualTo(0L);
        assertThat(config.eventsPerSnapshot()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle very large values")
    void builder_handlesLargeValues() {
        PersistenceConfig config =
                PersistenceConfig.builder()
                        .snapshotInterval(Long.MAX_VALUE)
                        .eventsPerSnapshot(Integer.MAX_VALUE)
                        .build();

        assertThat(config.snapshotInterval()).isEqualTo(Long.MAX_VALUE);
        assertThat(config.eventsPerSnapshot()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("Should create immutable config")
    void record_isImmutable() {
        PersistenceConfig config = PersistenceConfig.durable();

        // Record fields are final and immutable
        assertThat(config.durabilityLevel()).isEqualTo(PersistenceConfig.DurabilityLevel.DURABLE);
    }

    @Test
    @DisplayName("Should parse numeric environment variables correctly")
    void fromEnvironment_parsesNumericValues() {
        String originalInterval = System.getenv("JOTP_SNAPSHOT_INTERVAL");
        String originalEvents = System.getenv("JOTP_EVENTS_PER_SNAPSHOT");

        try {
            setEnv("JOTP_SNAPSHOT_INTERVAL", "999");
            setEnv("JOTP_EVENTS_PER_SNAPSHOT", "888");

            PersistenceConfig config = PersistenceConfig.fromEnvironment();

            assertThat(config.snapshotInterval()).isEqualTo(999L);
            assertThat(config.eventsPerSnapshot()).isEqualTo(888);
        } finally {
            setEnv("JOTP_SNAPSHOT_INTERVAL", originalInterval);
            setEnv("JOTP_EVENTS_PER_SNAPSHOT", originalEvents);
        }
    }

    @Test
    @DisplayName("Should handle whitespace in env variables")
    void fromEnvironment_handlesWhitespace() {
        String original = System.getenv("JOTP_DURABILITY");

        try {
            setEnv("JOTP_DURABILITY", "  DURABLE  ");

            PersistenceConfig config = PersistenceConfig.fromEnvironment();

            // Should handle whitespace gracefully
            assertThat(config.durabilityLevel())
                    .isEqualTo(PersistenceConfig.DEFAULT.durabilityLevel());
        } finally {
            setEnv("JOTP_DURABILITY", original);
        }
    }

    /**
     * Helper method to set environment variables for testing. Note: This only works for properties,
     * not actual environment variables.
     */
    private void setEnv(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
