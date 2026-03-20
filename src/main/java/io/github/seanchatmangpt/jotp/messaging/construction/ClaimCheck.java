package io.github.seanchatmangpt.jotp.messaging.construction;

import io.github.seanchatmangpt.jotp.Result;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claim Check pattern: stores a large message payload externally and passes a lightweight claim
 * token through the pipeline.
 *
 * <p>Enterprise Integration Pattern: <em>Claim Check</em> (EIP §7.3). Prevents large messages from
 * consuming channel bandwidth — only a small claim token travels through the pipeline.
 *
 * <p>The store is JVM-global and thread-safe. Call {@link #clearAll()} in tests to reset state.
 */
public final class ClaimCheck {

    private static final ConcurrentHashMap<UUID, Object> STORE = new ConcurrentHashMap<>();

    private ClaimCheck() {}

    /**
     * A checked message carrying the claim token, timestamp, and optional metadata.
     *
     * @param claimId the UUID claim token
     * @param timestamp creation epoch millis
     * @param metadata optional transport metadata
     */
    public record CheckedMessage(UUID claimId, long timestamp, Map<String, String> metadata) {

        /**
         * Retrieves the payload associated with this claim token.
         *
         * @param <T> the expected payload type
         * @return a Result containing the payload or an error message
         */
        @SuppressWarnings("unchecked")
        public <T> Result<T, String> retrieve() {
            return ClaimCheck.retrieve(claimId);
        }
    }

    /**
     * Stores the payload and returns a checked message with a new claim token.
     *
     * @param payload the payload to store
     * @param metadata optional metadata to attach to the checked message
     * @return the checked message with claim token
     * @throws IllegalArgumentException if {@code payload} is null
     */
    public static CheckedMessage claimCheck(Object payload, Map<String, String> metadata) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        UUID id = UUID.randomUUID();
        STORE.put(id, payload);
        return new CheckedMessage(id, System.currentTimeMillis(), metadata);
    }

    /**
     * Stores the payload and returns a checked message with a new claim token and no metadata.
     *
     * @param payload the payload to store
     * @return the checked message with claim token
     * @throws IllegalArgumentException if {@code payload} is null
     */
    public static CheckedMessage claimCheck(Object payload) {
        return claimCheck(payload, Map.of());
    }

    /**
     * Retrieves the stored payload by claim token without removing it.
     *
     * @param claimId the claim token
     * @param <T> the expected payload type
     * @return a Result containing the payload or an error message
     */
    @SuppressWarnings("unchecked")
    public static <T> Result<T, String> retrieve(UUID claimId) {
        if (claimId == null) {
            return Result.err("claimId must not be null");
        }
        Object value = STORE.get(claimId);
        if (value == null) {
            return Result.err("No payload found for claim: " + claimId);
        }
        return Result.ok((T) value);
    }

    /**
     * Retrieves and removes the stored payload by claim token (single-use claim).
     *
     * @param claimId the claim token
     * @param <T> the expected payload type
     * @return a Result containing the payload or an error message
     */
    @SuppressWarnings("unchecked")
    public static <T> Result<T, String> consumeClaim(UUID claimId) {
        if (claimId == null) {
            return Result.err("claimId must not be null");
        }
        Object value = STORE.remove(claimId);
        if (value == null) {
            return Result.err("No payload found for claim: " + claimId);
        }
        return Result.ok((T) value);
    }

    /**
     * Removes the stored payload by claim token.
     *
     * @param claimId the claim token
     * @return {@code true} if the claim was present and removed
     */
    public static boolean release(UUID claimId) {
        if (claimId == null) {
            return false;
        }
        return STORE.remove(claimId) != null;
    }

    /**
     * Returns {@code true} if a payload is stored under the given claim token.
     *
     * @param claimId the claim token to check
     * @return {@code true} if the claim exists
     */
    public static boolean exists(UUID claimId) {
        return claimId != null && STORE.containsKey(claimId);
    }

    /** Returns the number of payloads currently stored. */
    public static int claimCount() {
        return STORE.size();
    }

    /** Removes all stored payloads. Intended for use in test tear-down. */
    public static void clearAll() {
        STORE.clear();
    }
}
