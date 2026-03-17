package io.github.seanchatmangpt.jotp.distributed;

/**
 * Error types for registry operations.
 *
 * <p>Sealed hierarchy enables exhaustive pattern matching in error handling.
 */
public enum RegistryError {
    /** The name is already registered to another process. */
    ALREADY_EXISTS,

    /** The requested name was not found in the registry. */
    NOT_FOUND,

    /** The owning node is unreachable or has failed. */
    NODE_UNREACHABLE,

    /** The operation timed out before completing. */
    TIMEOUT
}
