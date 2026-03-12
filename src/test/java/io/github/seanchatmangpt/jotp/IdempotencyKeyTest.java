package io.github.seanchatmangpt.jotp;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest implements WithAssertions {

    @Test
    void generateCreatesNonNullKey() {
        IdempotencyKey key = IdempotencyKey.generate();
        assertThat(key).isNotNull();
        assertThat(key.value()).isNotNull().isNotBlank();
    }

    @Test
    void generateCreatesUniqueKeys() {
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
    void generateProducesUuidBasedKeys() {
        IdempotencyKey key = IdempotencyKey.generate();
        // UUID format: 8-4-4-4-12 hex chars
        assertThat(key.value())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
