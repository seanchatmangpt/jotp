package io.github.seanchatmangpt.jotp.serialization;

/**
 * Exception thrown when message serialization or deserialization fails.
 *
 * <p>This is an unchecked exception, following JOTP's pattern of surfacing errors through the
 * supervision tree rather than forcing checked exception handling.
 *
 * <p><strong>Causes of SerializationException:</strong>
 *
 * <ul>
 *   <li><strong>Unknown type:</strong> Attempted to serialize/deserialize a message type that is
 *       not registered with the serializer.
 *   <li><strong>Codec error:</strong> The underlying serialization codec (Jackson, protobuf, etc.)
 *       encountered a malformed message or I/O error.
 *   <li><strong>Type mismatch:</strong> Deserialization was requested for type A, but the bytes
 *       contain type B.
 *   <li><strong>Missing dependency:</strong> The serialization format requires a library that is
 *       not available on the classpath.
 * </ul>
 *
 * <p><strong>Recovery Strategy:</strong>
 *
 * <p>When a SerializationException is thrown, the corresponding process typically crashes,
 * triggering the supervisor's restart strategy. This follows the "let it crash" philosophy: don't
 * try to recover from a serialization error locally; let the supervisor decide the recovery
 * strategy.
 *
 * <p>Example:
 *
 * <pre>{@code
 * var handler = (state, message) -> {
 *     try {
 *         var bytes = serializer.serialize(message);
 *         return sendToRemote(bytes);
 *     } catch (SerializationException e) {
 *         // Process will crash here, supervisor will restart
 *         throw e;
 *     }
 * };
 * }</pre>
 *
 * @since 1.0
 * @author JOTP Contributors
 */
public class SerializationException extends RuntimeException {
  /**
   * Create a serialization exception with a message and cause.
   *
   * @param message the error description
   * @param cause the underlying exception (if any)
   */
  public SerializationException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Create a serialization exception with just a message.
   *
   * @param message the error description
   */
  public SerializationException(String message) {
    super(message);
  }

  /**
   * Create a serialization exception wrapping a cause.
   *
   * @param cause the underlying exception
   */
  public SerializationException(Throwable cause) {
    super(cause);
  }
}
