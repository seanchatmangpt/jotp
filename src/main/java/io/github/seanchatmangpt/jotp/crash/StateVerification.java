package io.github.seanchatmangpt.jotp.crash;

import java.util.Optional;

/**
 * Result of verifying a single process's state consistency.
 *
 * <p>Encapsulates the results of verifying a process's state against the persisted copy in the
 * backend storage.
 *
 * @param processName the process name
 * @param isValid whether the state is valid
 * @param errorMessage error message if validation failed
 * @param lastProcessedSeq the last processed sequence number from the dump
 * @param expectedSeq the expected sequence number from persistence
 * @param sequenceMatches whether the sequence numbers match
 */
public record StateVerification(
        String processName,
        boolean isValid,
        Optional<String> errorMessage,
        long lastProcessedSeq,
        long expectedSeq,
        boolean sequenceMatches) {

    /**
     * Create a successful verification.
     *
     * @param name the process name
     * @param seq the sequence number
     * @return a valid state verification
     */
    public static StateVerification valid(String name, long seq) {
        return new StateVerification(name, true, Optional.empty(), seq, seq, true);
    }

    /**
     * Create a failed verification.
     *
     * @param name the process name
     * @param error the error message
     * @param actual the actual sequence number
     * @param expected the expected sequence number
     * @return an invalid state verification
     */
    public static StateVerification invalid(String name, String error, long actual, long expected) {
        return new StateVerification(
                name, false, Optional.of(error), actual, expected, actual == expected);
    }

    /**
     * Get the sequence number mismatch.
     *
     * @return the difference between expected and actual sequence numbers
     */
    public long sequenceMismatch() {
        return Math.abs(expectedSeq - lastProcessedSeq);
    }

    /**
     * Check if the mismatch is within acceptable tolerance.
     *
     * @param tolerance the acceptable tolerance
     * @return true if the mismatch is within tolerance
     */
    public boolean isWithinTolerance(long tolerance) {
        return sequenceMatches() || sequenceMismatch() <= tolerance;
    }
}
