package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Service Activator pattern: invokes business logic in response to a message.
 *
 * <p>Enterprise Integration Pattern: <em>Service Activator</em> (EIP §9.4). The Service Activator
 * connects a message channel to a service, invoking a method on the service when a message arrives.
 * This decouples the messaging system from the service's implementation.
 *
 * <p>Erlang analog: a gen_server callback that pattern-matches on incoming messages and invokes
 * business logic. The result is either sent to an output channel or discarded.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * OrderProcessor} acts as a service activator, receiving order messages and invoking {@code
 * processOrder()}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // With output channel
 * var activator = ServiceActivator.<String, String>of(
 *     msg -> "Processed: " + msg,
 *     System.out::println
 * );
 * activator.send("order-123");
 *
 * // Without output channel
 * var activator = ServiceActivator.of(
 *     msg -> System.out.println("Processing: " + msg)
 * );
 * activator.send("event");
 * }</pre>
 *
 * @param <I> input message type
 * @param <O> output message type (if transformation is applied)
 */
public final class ServiceActivator<I, O> {

  private final Proc<Void, I> proc;

  /**
   * Creates a service activator with input and output transformation.
   *
   * @param handler the business logic function that transforms input to output
   * @param outputChannel receives the result from the handler
   * @param <I> input message type
   * @param <O> output message type
   * @return a new ServiceActivator
   */
  public static <I, O> ServiceActivator<I, O> of(
      Function<I, O> handler, Consumer<O> outputChannel) {
    var proc =
        new Proc<Void, I>(
            null,
            (state, msg) -> {
              O result = handler.apply(msg);
              outputChannel.accept(result);
              return state;
            });
    return new ServiceActivator<>(proc);
  }

  /**
   * Creates a service activator with input only (no output channel).
   *
   * @param handler the business logic function to invoke
   * @param <I> input message type
   * @return a new ServiceActivator
   */
  public static <I> ServiceActivator<I, Void> of(Consumer<I> handler) {
    var proc =
        new Proc<Void, I>(
            null,
            (state, msg) -> {
              handler.accept(msg);
              return state;
            });
    return new ServiceActivator<>(proc);
  }

  private ServiceActivator(Proc<Void, I> proc) {
    this.proc = proc;
  }

  /**
   * Send a message to the service activator for processing.
   *
   * @param message the message to process
   */
  public void send(I message) {
    proc.tell(message);
  }

  /** Stop the service activator. */
  public void stop() throws InterruptedException {
    proc.stop();
  }

  /** Returns the underlying Proc for monitoring. */
  public Proc<Void, I> proc() {
    return proc;
  }
}
