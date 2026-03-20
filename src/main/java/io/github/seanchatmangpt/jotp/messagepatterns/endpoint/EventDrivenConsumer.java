package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Consumer;

/**
 * Event-Driven Consumer pattern: automatically consumes messages as they arrive on a channel.
 *
 * <p>Enterprise Integration Pattern: <em>Event-Driven Consumer</em> (EIP §10.1). The consumer is
 * always listening for messages on an input channel and processes them as soon as they arrive,
 * without explicit polling.
 *
 * <p>Erlang analog: a gen_server process that receives messages in its mailbox and immediately
 * calls the callback function for each message. Messages are pushed to the handler as they arrive,
 * not pulled by the handler.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * OrderMessageConsumer} receives order events and processes them with an event handler callback.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var consumer = EventDrivenConsumer.of(
 *     msg -> System.out.println("Processing order: " + msg.orderId())
 * );
 *
 * consumer.send(new OrderEvent("order-123", "Ship"));
 * consumer.send(new OrderEvent("order-456", "Cancel"));
 *
 * // Consumer processes events asynchronously as they arrive
 * Thread.sleep(100); // allow time for async processing
 * consumer.stop();
 * }</pre>
 *
 * @param <T> message type
 */
public final class EventDrivenConsumer<T> {

  private final Proc<Void, T> proc;
  private final LinkedTransferQueue<T> queue;

  /**
   * Creates an event-driven consumer.
   *
   * @param handler the message processing callback function
   * @param <T> message type
   * @return a new EventDrivenConsumer
   */
  public static <T> EventDrivenConsumer<T> of(Consumer<T> handler) {
    var queue = new LinkedTransferQueue<T>();
    var proc =
        new Proc<Void, T>(
            null,
            (state, msg) -> {
              try {
                handler.accept(msg);
              } catch (Exception e) {
                // Log exception but continue processing
                System.err.println("Error handling message: " + e.getMessage());
              }
              return state;
            });
    return new EventDrivenConsumer<>(proc, queue);
  }

  private EventDrivenConsumer(Proc<Void, T> proc, LinkedTransferQueue<T> queue) {
    this.proc = proc;
    this.queue = queue;
  }

  /**
   * Send a message to the event-driven consumer.
   *
   * <p>The message is delivered immediately to the handler asynchronously. This method returns
   * before the handler has processed the message.
   *
   * @param message the message to process
   */
  public void send(T message) {
    proc.tell(message);
  }

  /**
   * Send multiple messages in bulk.
   *
   * @param messages the messages to process
   */
  public void sendBatch(Iterable<T> messages) {
    for (T message : messages) {
      send(message);
    }
  }

  /** Stop the consumer and clean up resources. */
  public void stop() throws InterruptedException {
    proc.stop();
  }

  /** Returns the underlying Proc for monitoring. */
  public Proc<Void, T> proc() {
    return proc;
  }
}
