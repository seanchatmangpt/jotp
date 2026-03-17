package io.github.seanchatmangpt.jotp.persistence;

/**
 * Exception thrown when a persistence operation fails.
 *
 * <p>Wraps underlying storage exceptions (IO, database, etc.) in a unified exception type for
 * consistent error handling across different backend implementations.
 *
 * @see PersistenceBackend
 */
public class PersistenceException extends RuntimeException {

    /**
     * Create a persistence exception with a message.
     *
     * @param message the error message
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Create a persistence exception with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create a persistence exception from a cause.
     *
     * @param cause the underlying cause
     */
    public PersistenceException(Throwable cause) {
        super(cause);
    }
}
