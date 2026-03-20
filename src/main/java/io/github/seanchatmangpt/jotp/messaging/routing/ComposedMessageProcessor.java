package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Composed Message Processor pattern.
 *
 * <p>Chains multiple message routers (transformations) and filters into a pipeline. Messages flow
 * through transformations in order, are filtered by predicates, and pass through side-effect
 * handlers. This enables declarative, fluent DSLs for message processing.
 *
 * <p><strong>Pipeline Semantics:</strong>
 * <ul>
 *   <li>Transformations are applied in composition order
 *   <li>Filters are applied sequentially (short-circuit on first failure)
 *   <li>Peek handlers execute for side effects (logging, metrics)
 *   <li>Each stage can transform the message type
 * </ul>
 *
 * <p><strong>Type Safety:</strong> Uses Java generics to ensure type-safe composition across
 * pipeline stages.
 *
 * <p><strong>Thread Safety:</strong> Immutable pipeline; thread-safe to apply concurrently.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * ComposedMessageProcessor<String> processor =
 *     ComposedMessageProcessor.<String>compose(
 *             msg -> msg.trim(),
 *             msg -> msg.toUpperCase())
 *         .thenFilter(msg -> !msg.isEmpty())
 *         .peek(System.out::println)
 *         .thenRoute(msg -> msg + "!");
 *
 * String result = processor.apply("  hello  ");  // "HELLO!"
 * }</pre>
 *
 * @param <T> the type of messages processed by this stage
 */
public final class ComposedMessageProcessor<T> {

  @FunctionalInterface
  private interface Stage<T> {
    T process(T value);
  }

  private final List<Stage<Object>> stages = new ArrayList<>();
  private final Class<?> inputType;

  /**
   * Creates a processor with the given transformation functions.
   *
   * <p>Null routers are skipped.
   *
   * @param routers transformation functions to apply in order
   * @return new ComposedMessageProcessor
   */
  @SafeVarargs
  public static <T> ComposedMessageProcessor<T> compose(
      Function<T, T>... routers) {
    ComposedMessageProcessor<T> proc = new ComposedMessageProcessor<>(Object.class);
    for (Function<T, T> router : routers) {
      if (router != null) {
        proc.stages.add(value -> (Object) router.apply((T) value));
      }
    }
    return proc;
  }

  private ComposedMessageProcessor(Class<?> inputType) {
    this.inputType = inputType;
  }

  /**
   * Applies a filter predicate. If the predicate fails, returns null (short-circuits).
   *
   * @param predicate the filter condition
   * @return new processor with filter applied
   */
  public ComposedMessageProcessor<T> thenFilter(Predicate<T> predicate) {
    Objects.requireNonNull(predicate, "predicate cannot be null");
    ComposedMessageProcessor<T> next = new ComposedMessageProcessor<>(inputType);
    next.stages.addAll(this.stages);
    next.stages.add(
        value -> {
          T t = (T) value;
          if (predicate.test(t)) {
            return value;
          } else {
            return null; // Signal filter failure
          }
        });
    return next;
  }

  /**
   * Applies a transformation function (router).
   *
   * <p>Null routers are skipped.
   *
   * @param router the transformation function
   * @return new processor with router applied
   */
  public ComposedMessageProcessor<T> thenRoute(Function<T, T> router) {
    if (router == null) {
      return this;
    }
    ComposedMessageProcessor<T> next = new ComposedMessageProcessor<>(inputType);
    next.stages.addAll(this.stages);
    next.stages.add(value -> (Object) router.apply((T) value));
    return next;
  }

  /**
   * Adds a peek handler for side effects (logging, metrics).
   *
   * @param consumer the side-effect handler
   * @return new processor with peek applied
   */
  public ComposedMessageProcessor<T> peek(Consumer<T> consumer) {
    Objects.requireNonNull(consumer, "consumer cannot be null");
    ComposedMessageProcessor<T> next = new ComposedMessageProcessor<>(inputType);
    next.stages.addAll(this.stages);
    next.stages.add(
        value -> {
          consumer.accept((T) value);
          return value;
        });
    return next;
  }

  /**
   * Chains another processor after this one.
   *
   * @param other the next processor
   * @return new processor combining both
   */
  public ComposedMessageProcessor<T> andThen(ComposedMessageProcessor<T> other) {
    Objects.requireNonNull(other, "other cannot be null");
    ComposedMessageProcessor<T> combined = new ComposedMessageProcessor<>(inputType);
    combined.stages.addAll(this.stages);
    combined.stages.addAll(other.stages);
    return combined;
  }

  /**
   * Applies the processor pipeline to a message.
   *
   * <p>Returns null if any filter fails (short-circuits at first filter failure).
   *
   * @param input the message to process
   * @return the processed message, or null if filtered out
   */
  public T apply(T input) {
    if (input == null) {
      return null;
    }
    Object result = (Object) input;
    for (Stage<Object> stage : stages) {
      result = stage.process(result);
      if (result == null) {
        return null; // Short-circuit on filter failure
      }
    }
    return (T) result;
  }

  /**
   * Applies the processor pipeline to a message, wrapping the result in an Optional.
   *
   * @param input the message to process
   * @return Optional with the processed message, or empty if filtered out
   */
  public Optional<T> applyOptional(T input) {
    return Optional.ofNullable(apply(input));
  }
}
