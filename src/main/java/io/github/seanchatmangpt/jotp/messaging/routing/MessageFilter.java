package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Message Filter pattern (EIP).
 *
 * <p>Selectively forwards messages based on a filter criterion. Messages that don't match are
 * silently dropped. This is one of the basic EIP patterns for message flow control.
 *
 * <p><strong>Pattern Semantics:</strong>
 * <ul>
 *   <li>Each message is tested against the filter predicate
 *   <li>Matching messages are forwarded to the next destination
 *   <li>Non-matching messages are silently dropped
 *   <li>The filter can be chained with other filters or processors
 * </ul>
 *
 * <p><strong>Destination Types:</strong> A filter can forward to:
 * <ul>
 *   <li>Another filter or processor (via {@link #create(Predicate, MessageFilter)})
 *   <li>A consumer function (via {@link #create(Predicate, Consumer)})
 *   <li>A {@link Proc} (via {@link #create(Predicate, Proc)})
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Thread-safe; can handle concurrent calls from multiple
 * virtual threads.
 *
 * <p><strong>Reference:</strong> Enterprise Integration Patterns, Chapter 5: Message Filter
 * (https://www.enterpriseintegrationpatterns.com/MessageFilter.html)
 *
 * @param <M> message type
 */
public final class MessageFilter<M> {

  /**
   * Functional interface for message destinations.
   *
   * @param <M> message type
   */
  @FunctionalInterface
  public interface Destination<M> {
    /**
     * Accepts a message and forwards it downstream.
     *
     * @param message the message to forward
     */
    void accept(M message);
  }

  private final Predicate<M> predicate;
  private final Destination<M> next;
  private final ProcRef<Void, Object> processRef;

  /**
   * Creates a message filter with the given predicate and destination.
   *
   * @param predicate the filter criterion
   * @param next the destination for matching messages
   * @throws IllegalArgumentException if predicate or next is null
   */
  private MessageFilter(Predicate<M> predicate, Destination<M> next) {
    this.predicate =
        Objects.requireNonNull(predicate, "predicate cannot be null");
    this.next = Objects.requireNonNull(next, "next cannot be null");

    // Create an internal process for async handling
    var proc =
        new Proc<>(
            null,
            (state, msg) -> {
              return state;
            });
    this.processRef = new ProcRef<>(proc);
  }

  /**
   * Factory: creates a filter that forwards to a consumer.
   *
   * @param predicate the filter criterion
   * @param destination consumer for matching messages
   * @return new MessageFilter
   * @throws IllegalArgumentException if predicate or destination is null
   */
  public static <M> MessageFilter<M> create(
      Predicate<M> predicate, Destination<M> destination) {
    Objects.requireNonNull(predicate, "predicate cannot be null");
    Objects.requireNonNull(destination, "next cannot be null");
    return new MessageFilter<>(predicate, destination);
  }

  /**
   * Factory: creates a filter that forwards to another filter.
   *
   * @param predicate the filter criterion
   * @param next the next filter in the chain
   * @return new MessageFilter
   */
  public static <M> MessageFilter<M> create(
      Predicate<M> predicate, MessageFilter<M> next) {
    Objects.requireNonNull(predicate, "predicate cannot be null");
    Objects.requireNonNull(next, "next cannot be null");
    return new MessageFilter<>(predicate, next::filter);
  }

  /**
   * Factory: creates a filter that forwards to a Proc.
   *
   * @param predicate the filter criterion
   * @param proc the process to receive matching messages
   * @return new MessageFilter
   */
  public static <M> MessageFilter<M> create(Predicate<M> predicate, Proc<?, M> proc) {
    Objects.requireNonNull(predicate, "predicate cannot be null");
    Objects.requireNonNull(proc, "proc cannot be null");
    return new MessageFilter<>(predicate, proc::tell);
  }

  /**
   * Filters a message: forwards it if it matches the predicate, otherwise drops it.
   *
   * @param message the message to filter
   * @throws IllegalArgumentException if message is null
   */
  public void filter(M message) {
    Objects.requireNonNull(message, "message cannot be null");
    if (predicate.test(message)) {
      next.accept(message);
    }
    // If predicate fails, message is silently dropped
  }

  /**
   * Returns the internal process reference.
   *
   * @return process reference for lifecycle management
   */
  public ProcRef<Void, Object> process() {
    return processRef;
  }
}
