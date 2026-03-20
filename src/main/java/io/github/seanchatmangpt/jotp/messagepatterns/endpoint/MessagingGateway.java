package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Messaging Gateway pattern: encapsulates message sending/receiving from external systems.
 *
 * <p>Enterprise Integration Pattern: <em>Messaging Gateway</em> (EIP §9.1). The gateway is the
 * single contact point between an application and an external system. All interaction with the
 * external system is centralized, decoupling the domain logic from integration concerns.
 *
 * <p>Erlang analog: a dedicated gen_server that manages all communication with external systems,
 * isolating the rest of the application from low-level protocol details.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * ShoppingCart} delegates all Inventory interactions to {@code InventoryGateway}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * interface ExternalSystem {
 *     CompletableFuture<String> query(String request);
 * }
 *
 * var gateway = MessagingGateway.<String, String>create(
 *     (request) -> externalSystem.query(request),
 *     (response) -> log.debug("Received: {}", response)
 * );
 *
 * CompletableFuture<String> response = gateway.send("Hello External System");
 * String result = response.get(5, TimeUnit.SECONDS);
 * }</pre>
 *
 * @param <I> inbound message type (from external system)
 * @param <O> outbound message type (to external system)
 */
public final class MessagingGateway<I, O> {

  private record GatewayMsg<I, O>(
      String type, // "request" or "response"
      O request,
      I response,
      CompletableFuture<?> future) {}

  private final Function<O, CompletableFuture<I>> externalCall;
  private final Proc<Void, GatewayMsg<I, O>> proc;

  /**
   * Creates a messaging gateway.
   *
   * @param externalCall function that sends a request to the external system and returns a
   *     CompletableFuture with the response
   * @param responseHandler optional handler for processing responses asynchronously (can be null)
   */
  private MessagingGateway(
      Function<O, CompletableFuture<I>> externalCall,
      java.util.function.Consumer<I> responseHandler) {
    this.externalCall = externalCall;
    this.proc =
        new Proc<>(
            null,
            (state, msg) -> {
              if ("request".equals(msg.type())) {
                // Send request to external system asynchronously
                externalCall
                    .apply(msg.request())
                    .whenComplete(
                        (response, ex) -> {
                          if (ex != null) {
                            msg.future().completeExceptionally(ex);
                          } else {
                            if (responseHandler != null) {
                              responseHandler.accept(response);
                            }
                            msg.future().complete(response);
                          }
                        });
              }
              return state;
            });
  }

  /**
   * Creates a messaging gateway.
   *
   * @param externalCall function that sends a request and returns a CompletableFuture response
   * @param responseHandler optional handler for asynchronous response processing
   * @return a new MessagingGateway
   * @param <I> inbound message type
   * @param <O> outbound message type
   */
  public static <I, O> MessagingGateway<I, O> create(
      Function<O, CompletableFuture<I>> externalCall,
      java.util.function.Consumer<I> responseHandler) {
    return new MessagingGateway<>(externalCall, responseHandler);
  }

  /**
   * Creates a messaging gateway without a response handler.
   *
   * @param externalCall function that sends a request and returns a CompletableFuture response
   * @return a new MessagingGateway
   * @param <I> inbound message type
   * @param <O> outbound message type
   */
  public static <I, O> MessagingGateway<I, O> create(
      Function<O, CompletableFuture<I>> externalCall) {
    return new MessagingGateway<>(externalCall, null);
  }

  /**
   * Send a request to the external system and receive a response.
   *
   * <p>This method returns immediately with a CompletableFuture that completes when the external
   * system responds.
   *
   * @param request the request to send to the external system
   * @return a CompletableFuture that completes with the external system's response
   */
  @SuppressWarnings("unchecked")
  public CompletableFuture<I> send(O request) {
    var future = new CompletableFuture<I>();
    GatewayMsg<I, O> msg = new GatewayMsg<>("request", request, null, future);
    proc.tell(msg);
    return future;
  }

  /**
   * Send a request with a timeout.
   *
   * @param request the request to send
   * @param timeout maximum time to wait for a response
   * @param unit the timeout unit
   * @return a CompletableFuture that completes within the timeout or fails with TimeoutException
   */
  public CompletableFuture<I> send(O request, long timeout, TimeUnit unit) {
    return send(request).orTimeout(timeout, unit);
  }

  /** Stop the gateway and clean up resources. */
  public void stop() throws InterruptedException {
    proc.stop();
  }

  /** Returns the underlying Proc for monitoring. */
  public Proc<Void, GatewayMsg<I, O>> proc() {
    return proc;
  }
}
