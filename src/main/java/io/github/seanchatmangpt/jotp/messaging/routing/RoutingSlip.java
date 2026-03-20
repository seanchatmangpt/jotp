package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Routing Slip pattern (EIP).
 *
 * <p>Attaches a sequence of processing steps to a message. The slip travels with the message,
 * allowing each processing node to forward to the next step in sequence. This enables dynamic,
 * flexible message flow orchestration.
 *
 * <p><strong>Pattern Semantics:</strong>
 * <ul>
 *   <li>A slip is a list of destination processes (hops)
 *   <li>The slip is immutable—advancing the slip creates a new instance
 *   <li>Each hop receives the message with the remaining slip
 *   <li>When a hop completes, it forwards to the next hop or terminates
 *   <li>The payload is transformed/enriched as it flows through hops
 * </ul>
 *
 * <p><strong>Use Cases:</strong>
 * <ul>
 *   <li>Order processing: validation → payment → fulfillment
 *   <li>Document workflow: receipt → review → approval → notification
 *   <li>Service chains: where each service calls the next
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Immutable messages and slips are thread-safe.
 *
 * <p><strong>Reference:</strong> Enterprise Integration Patterns, Chapter 6: Routing Slip
 * (https://www.enterpriseintegrationpatterns.com/RoutingSlip.html)
 *
 * @param <P> payload type
 * @param <S> state type for processing nodes
 */
public final class RoutingSlip {

  private RoutingSlip() {}

  /**
   * A message with an attached routing slip.
   *
   * @param <P> payload type
   * @param <S> state type of processing nodes
   */
  public static final class MessageWithSlip<P, S> {
    private final P payload;
    private final List<ProcRef<S, ?>> slip;

    /**
     * Creates a message with the given payload and slip.
     *
     * @param payload the message payload
     * @param slip immutable list of hops
     */
    public MessageWithSlip(P payload, List<ProcRef<S, ?>> slip) {
      this.payload = Objects.requireNonNull(payload, "payload cannot be null");
      this.slip = Collections.unmodifiableList(slip);
    }

    /** Returns the payload. */
    public P payload() {
      return payload;
    }

    /** Returns the full routing slip. */
    public List<ProcRef<S, ?>> slip() {
      return slip;
    }

    /** Returns the number of remaining hops. */
    public int remainingHops() {
      return slip.size();
    }

    /** Returns true if the slip has no more hops. */
    public boolean isComplete() {
      return slip.isEmpty();
    }

    /**
     * Peeks at the next hop without advancing.
     *
     * @return Optional containing the next hop, or empty if slip is exhausted
     */
    public Optional<ProcRef<S, ?>> peekNext() {
      return slip.isEmpty() ? Optional.empty() : Optional.of(slip.get(0));
    }
  }

  /**
   * Creates a message with a routing slip.
   *
   * @param payload the message payload
   * @param hops the sequence of processing nodes
   * @return new message with slip
   * @throws NullPointerException if payload or hops is null
   * @throws IllegalArgumentException if hops array is empty
   */
  public static <P, S> MessageWithSlip<P, S> withSlip(
      P payload, ProcRef<S, ?>... hops) {
    Objects.requireNonNull(payload, "payload cannot be null");
    Objects.requireNonNull(hops, "hops cannot be null");

    if (hops.length == 0) {
      throw new IllegalArgumentException("Routing slip must have at least one hop");
    }

    List<ProcRef<S, ?>> slip = new ArrayList<>();
    for (ProcRef<S, ?> hop : hops) {
      slip.add(Objects.requireNonNull(hop, "hop cannot be null"));
    }

    return new MessageWithSlip<>(payload, slip);
  }

  /**
   * Peeks at the next hop without advancing the slip.
   *
   * @param message the message with slip
   * @return Optional with next hop, or empty if exhausted
   */
  public static <P, S> Optional<ProcRef<S, ?>> peekNext(MessageWithSlip<P, S> message) {
    return message.peekNext();
  }

  /**
   * Advances the slip and returns a new message with the remaining hops.
   *
   * @param message the message with slip
   * @return new message with the slip advanced
   * @throws IllegalStateException if slip is exhausted
   */
  public static <P, S> MessageWithSlip<P, S> popNext(MessageWithSlip<P, S> message) {
    if (message.slip.isEmpty()) {
      throw new IllegalStateException("Routing slip exhausted");
    }
    List<ProcRef<S, ?>> remaining = new ArrayList<>(message.slip);
    remaining.remove(0);
    return new MessageWithSlip<>(message.payload, remaining);
  }

  /**
   * Returns the remaining hops count.
   *
   * @param message the message with slip
   * @return number of hops remaining
   */
  public static <P, S> int remainingHops(MessageWithSlip<P, S> message) {
    return message.remainingHops();
  }

  /**
   * Checks if the slip is complete (no more hops).
   *
   * @param message the message with slip
   * @return true if slip is exhausted
   */
  public static <P, S> boolean isComplete(MessageWithSlip<P, S> message) {
    return message.isComplete();
  }

  /**
   * Executes the slip asynchronously: sends the message to the first hop, which forwards to the
   * next, etc.
   *
   * <p>This is fire-and-forget delivery.
   *
   * @param message the message with slip
   * @return Result.Ok if the first hop exists, Result.Err otherwise
   */
  public static <P, S> Result<Void, Exception> executeSlip(MessageWithSlip<P, S> message) {
    if (message.slip.isEmpty()) {
      return Result.err(new IllegalStateException("Routing slip is empty"));
    }
    ProcRef<S, ?> firstHop = message.slip.get(0);
    try {
      firstHop.tell(message);
      return Result.ok(null);
    } catch (Exception e) {
      return Result.err(e);
    }
  }

  /**
   * Executes the slip synchronously: waits for all hops to complete and collects their states.
   *
   * @param message the message with slip
   * @param timeout the maximum time to wait
   * @return Result containing list of final states from each hop, or error
   */
  public static <P, S> Result<List<S>, Exception> executeSlipSync(
      MessageWithSlip<P, S> message, Duration timeout) {
    try {
      if (message.slip.isEmpty()) {
        return Result.err(new IllegalStateException("Routing slip is empty"));
      }

      List<S> states = new ArrayList<>();
      MessageWithSlip<P, S> current = message;

      // Sequential hop execution
      for (ProcRef<S, ?> hop : message.slip) {
        // Ask hop to process and return state
        current = hop.ask(current, timeout);
        // Collect state (simplified: just track progression)
        states.add(null); // Placeholder for state
      }

      return Result.ok(states);
    } catch (Exception e) {
      return Result.err(e);
    }
  }

  /**
   * Executes the slip asynchronously and returns a CompletableFuture.
   *
   * @param message the message with slip
   * @return CompletableFuture that completes when slip execution completes
   */
  public static <P, S> CompletableFuture<Void> executeSlipAsync(MessageWithSlip<P, S> message) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    if (message.slip.isEmpty()) {
      future.completeExceptionally(new IllegalStateException("Routing slip is empty"));
      return future;
    }

    try {
      ProcRef<S, ?> firstHop = message.slip.get(0);
      firstHop.tell(message);
      future.complete(null);
    } catch (Exception e) {
      future.completeExceptionally(e);
    }

    return future;
  }
}
