package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@DtrTest
class IdempotencyKeyTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    void generateCreatesNonNullKey(DtrContext ctx) {
        ctx.sayNextSection("IdempotencyKey: Unique Message Identifiers");
        ctx.say(
                """
                IdempotencyKey provides unique identifiers for idempotent message processing.
                Each call to generate() creates a new UUID-based key that can be attached to messages.
                These keys enable deduplication: if the same message is processed twice, only the first takes effect.
                """);
        IdempotencyKey key = IdempotencyKey.generate();
        assertThat(key).isNotNull();
        assertThat(key.value()).isNotNull().isNotBlank();
    }

    @Test
    void generateCreatesUniqueKeys(DtrContext ctx) {
        ctx.say(
                """
                Each generated IdempotencyKey is guaranteed to be unique.
                This uniqueness is essential for correct deduplication - two different operations
                must never share the same key, or one would be incorrectly discarded.
                """);
        IdempotencyKey key1 = IdempotencyKey.generate();
        IdempotencyKey key2 = IdempotencyKey.generate();
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1.value()).isNotEqualTo(key2.value());
    }

    @Test
    void ofRoundtrips() {
        String rawKey = "test-idempotency-key-123";
        IdempotencyKey key = IdempotencyKey.of(rawKey);
        assertThat(key.value()).isEqualTo(rawKey);
    }

    @Test
    void ofWithNullThrowsIllegalArgumentException() {
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKey.of(null));
    }

    @Test
    void ofWithBlankThrowsIllegalArgumentException() {
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKey.of("   "));
    }

    @Test
    void ofWithEmptyStringThrowsIllegalArgumentException() {
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKey.of(""));
    }

    @Test
    void generateProducesUuidBasedKeys(DtrContext ctx) {
        ctx.say(
                """
                IdempotencyKey uses UUID v4 format for generation: 8-4-4-4-12 hexadecimal characters.
                UUIDs provide 122 bits of entropy, making collisions astronomically unlikely.
                This format is universally recognized and works well with logging and tracing systems.
                """);
        IdempotencyKey key = IdempotencyKey.generate();
        // UUID format: 8-4-4-4-12 hex chars
        assertThat(key.value())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
