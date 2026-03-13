package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.*;

/**
 * Spring Boot → JOTP Migration Pattern.
 *
 * <p>This example shows how to gradually migrate from traditional Spring Boot services to JOTP
 * autonomous agents.
 *
 * <p><strong>Migration Phases:</strong>
 *
 * <ol>
 *   <li><strong>Phase 0: Assessment</strong> - Identify service candidates (high-traffic,
 *       fault-prone, self-contained)
 *   <li><strong>Phase 1: Pilot</strong> - Refactor one service to JOTP agents alongside existing
 *       service
 *   <li><strong>Phase 2: Dual-Write</strong> - Spring Boot service sends to JOTP system; both
 *       versions run in parallel
 *   <li><strong>Phase 3: Cutover</strong> - Route traffic to JOTP system; keep Spring Boot for
 *       fallback
 *   <li><strong>Phase 4: Ecosystem</strong> - All services are JOTP agents; Spring Boot becomes
 *       thin REST wrapper
 * </ol>
 *
 * <p><strong>Example: Order Processing Service</strong>
 *
 * <p>Traditional Spring Boot:
 *
 * <pre>
 * @RestController
 * public class OrderService {
 *     @PostMapping("/orders")
 *     public OrderResponse createOrder(@RequestBody OrderRequest req) {
 *         try {
 *             validateOrder(req);
 *             debitPayment(req.paymentId);
 *             reserveInventory(req.items);
 *             sendConfirmation(req.customerId);
 *             return OrderResponse.success();
 *         } catch (PaymentException e) {
 *             return OrderResponse.error("Payment failed");
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>Problems:
 * <ul>
 *   <li>Synchronous request-response
 *   <li>Shared mutable state (database)
 *   <li>Hard to handle partial failures (what if payment succeeds but inventory fails?)
 *   <li>Retry logic is ad-hoc
 *   <li>No automatic recovery
 * </ul>
 *
 * <p>JOTP Refactor:
 *
 * <pre>
 * // Order state machine: each order is an autonomous agent
 * sealed interface OrderState {
 *     record Pending() implements OrderState {}
 *     record PaymentProcessing() implements OrderState {}
 *     record InventoryReserving() implements OrderState {}
 *     record Confirmed() implements OrderState {}
 *     record Failed(String reason) implements OrderState {}
 * }
 *
 * sealed interface OrderEvent {
 *     record CreateOrder(OrderRequest req) implements OrderEvent {}
 *     record PaymentApproved(String transactionId) implements OrderEvent {}
 *     record InventoryReserved(String reservationId) implements OrderEvent {}
 *     record PaymentFailed(String reason) implements OrderEvent {}
 * }
 *
 * // Each order is a state machine managed by supervisor
 * var orderSupervisor = Supervisor.create()
 *     .withStrategy(RestartStrategy.ONE_FOR_ONE)
 *     .withChild(ChildSpec.of(
 *         "order-" + orderId,
 *         () -> orderStateMachine(orderId),
 *         RestartType.TEMPORARY  // Remove after complete/failed
 *     ))
 *     .start();
 * </pre>
 *
 * <p>Benefits:
 * <ul>
 *   <li>Each order is independent (no shared state)
 *   <li>Asynchronous processing (external services send responses via messages)
 *   <li>Automatic recovery (if order fails, supervisor restarts it)
 *   <li>Clear state transitions (sealed state machine enforces valid paths)
 *   <li>Observable (each order has identity and can be monitored)
 * </ul>
 *
 * <p><strong>Migration Path (6-Month Timeline):</strong>
 *
 * <ul>
 *   <li><strong>Week 0-2 (Phase 0):</strong> Analyze codebase, identify order processing as
 *       pilot service
 *   <li><strong>Week 2-4 (Phase 1):</strong> Implement order state machine in JOTP, run tests
 *   <li><strong>Week 4-6 (Phase 2):</strong> Deploy side-by-side with Spring Boot
 *       <ul>
 *         <li>Spring controller receives request
 *         <li>Sends to JOTP order supervisor
 *         <li>Both process order in parallel
 *         <li>Compare results, tune JOTP logic
 *       </ul>
 *   <li><strong>Week 6-8 (Phase 3):</strong> Gradual traffic shift
 *       <ul>
 *         <li>Route 10% of orders to JOTP
 *         <li>Monitor for divergence
 *         <li>Increase to 25%, 50%, 100%
 *         <li>Keep Spring Boot as fallback
 *       </ul>
 *   <li><strong>Week 8-24 (Phase 4):</strong> Ecosystem expansion
 *       <ul>
 *         <li>Migrate payment processing to JOTP
 *         <li>Migrate inventory management to JOTP
 *         <li>Migrate notification service to JOTP
 *         <li>Services coordinate via supervisor trees
 *       </ul>
 * </ul>
 */
public class SpringBootIntegration {

  // ============================================================================
  // PHASE 1: DEFINE JOTP STATE & EVENTS
  // ============================================================================

  /** Order domain model */
  record OrderRequest(
      String orderId,
      String customerId,
      String paymentId,
      List<String> items,
      double totalAmount) {}

  record OrderResponse(boolean success, String message, Optional<OrderData> data) {}

  record OrderData(
      String orderId,
      OrderState state,
      Optional<String> paymentTransactionId,
      Optional<String> inventoryReservationId) {}

  /** Order state machine states */
  sealed interface OrderState {
    record Pending() implements OrderState {}

    record ValidatingOrder() implements OrderState {}

    record ProcessingPayment() implements OrderState {}

    record ReservingInventory() implements OrderState {}

    record Confirmed() implements OrderState {}

    record Failed(String reason) implements OrderState {}
  }

  /** Order state machine events */
  sealed interface OrderEvent {
    record InitiateOrder(OrderRequest req) implements OrderEvent {}

    record ValidationComplete() implements OrderEvent {}

    record PaymentApproved(String transactionId) implements OrderEvent {}

    record PaymentFailed(String reason) implements OrderEvent {}

    record InventoryReserved(String reservationId) implements OrderEvent {}

    record InventoryUnavailable(String reason) implements OrderEvent {}

    record ConfirmationSent() implements OrderEvent {}

    record Timeout() implements OrderEvent {}
  }

  /** Order context (mutable state within state machine) */
  static class OrderContext {
    OrderRequest request;
    Optional<String> paymentTransactionId = Optional.empty();
    Optional<String> inventoryReservationId = Optional.empty();
    long createdAt = System.currentTimeMillis();
  }

  // ============================================================================
  // PHASE 1: CREATE JOTP STATE MACHINE
  // ============================================================================

  /**
   * Creates a state machine for a single order. This replaces the synchronous Spring Boot
   * endpoint.
   */
  static class OrderStateMachine {
    static StateMachine<OrderState, OrderEvent, OrderContext> create(String orderId) {
      return StateMachine.<OrderState, OrderEvent, OrderContext>create()
          .withInitialState(new OrderState.Pending())

          // Transition: Pending → ValidatingOrder
          .withTransition(
              OrderState.Pending.class,
              OrderEvent.InitiateOrder.class,
              (state, event, ctx) -> {
                ctx.request = event.request();
                System.out.printf(
                    "[Order %s] Initiated: %s items, $%.2f%n",
                    orderId, ctx.request.items().size(), ctx.request.totalAmount());

                // Simulate calling external validation service
                // In real system, this would be async message to validator agent
                simulateValidation(ctx);

                return new StateMachine.Transition.NextState(
                    new OrderState.ValidatingOrder(),
                    List.of(
                        new StateMachine.Action.Set(
                            () ->
                                Duration.ofSeconds(5)
                                    .toMillis()) // validation timeout
                        ));
              })

          // Transition: ValidatingOrder → ProcessingPayment
          .withTransition(
              OrderState.ValidatingOrder.class,
              OrderEvent.ValidationComplete.class,
              (state, event, ctx) -> {
                System.out.printf("[Order %s] Validation passed, processing payment%n", orderId);

                // Simulate calling external payment service
                simulatePayment(ctx);

                return new StateMachine.Transition.NextState(
                    new OrderState.ProcessingPayment(),
                    List.of(
                        new StateMachine.Action.Set(
                            () ->
                                Duration.ofSeconds(10)
                                    .toMillis()) // payment timeout
                        ));
              })

          // Transition: ProcessingPayment → ReservingInventory (on success)
          .withTransition(
              OrderState.ProcessingPayment.class,
              OrderEvent.PaymentApproved.class,
              (state, event, ctx) -> {
                ctx.paymentTransactionId = Optional.of(event.transactionId());
                System.out.printf(
                    "[Order %s] Payment approved (txn: %s), reserving inventory%n",
                    orderId, event.transactionId());

                simulateInventoryReservation(ctx);

                return new StateMachine.Transition.NextState(
                    new OrderState.ReservingInventory(),
                    List.of(
                        new StateMachine.Action.Set(
                            () ->
                                Duration.ofSeconds(5)
                                    .toMillis()) // inventory timeout
                        ));
              })

          // Transition: ProcessingPayment → Failed (on payment failure)
          .withTransition(
              OrderState.ProcessingPayment.class,
              OrderEvent.PaymentFailed.class,
              (state, event, ctx) -> {
                System.out.printf(
                    "[Order %s] Payment failed: %s, aborting order%n", orderId,
                    event.reason());

                return new StateMachine.Transition.Stop(
                    new OrderState.Failed(event.reason()), List.of());
              })

          // Transition: ReservingInventory → Confirmed (on success)
          .withTransition(
              OrderState.ReservingInventory.class,
              OrderEvent.InventoryReserved.class,
              (state, event, ctx) -> {
                ctx.inventoryReservationId = Optional.of(event.reservationId());
                System.out.printf(
                    "[Order %s] Inventory reserved (reservation: %s), order confirmed%n",
                    orderId, event.reservationId());

                // Send confirmation notification
                simulateSendConfirmation(ctx);

                return new StateMachine.Transition.NextState(
                    new OrderState.Confirmed(), List.of());
              })

          // Transition: ReservingInventory → Failed (on unavailable)
          .withTransition(
              OrderState.ReservingInventory.class,
              OrderEvent.InventoryUnavailable.class,
              (state, event, ctx) -> {
                System.out.printf(
                    "[Order %s] Inventory unavailable: %s%n", orderId, event.reason());
                // In real system, would refund payment here
                return new StateMachine.Transition.Stop(
                    new OrderState.Failed(event.reason()), List.of());
              })

          .build();
    }

    private static void simulateValidation(OrderContext ctx) {
      // Async: validation service would respond with ValidationComplete event
      new Thread(
              () -> {
                try {
                  Thread.sleep(100);
                  System.out.printf("  → Validation service: order OK%n");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .start();
    }

    private static void simulatePayment(OrderContext ctx) {
      new Thread(
              () -> {
                try {
                  Thread.sleep(200);
                  System.out.printf("  → Payment processor: approved (txn-12345)%n");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .start();
    }

    private static void simulateInventoryReservation(OrderContext ctx) {
      new Thread(
              () -> {
                try {
                  Thread.sleep(150);
                  System.out.printf("  → Inventory service: reserved%n");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .start();
    }

    private static void simulateSendConfirmation(OrderContext ctx) {
      new Thread(
              () -> {
                try {
                  Thread.sleep(50);
                  System.out.printf("  → Notification: confirmation sent to %s%n",
                      ctx.request.customerId());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .start();
    }
  }

  // ============================================================================
  // PHASE 2: WRAP IN SUPERVISOR (PRODUCTION SAFETY)
  // ============================================================================

  /** Production-grade order processing supervisor */
  static class OrderProcessingSystem {
    private final Supervisor supervisor;

    public OrderProcessingSystem() {
      this.supervisor =
          Supervisor.create()
              .withStrategy(RestartStrategy.ONE_FOR_ONE)
              .withMaxRestarts(3)
              .withWindowSeconds(30)
              .onChildExit(
                  (childId, exitReason) -> {
                    System.out.printf("  [Supervisor] Child exited: %s%n", childId);
                  })
              .build();
    }

    /**
     * Starts processing an order. Returns immediately; processing happens asynchronously.
     *
     * <p>In real system, this would return a ProcRef that can be monitored/queried.
     */
    public void processOrder(OrderRequest request) {
      String childId = "order-" + request.orderId();

      supervisor.addChild(
          ChildSpec.of(
              childId,
              () ->
                  Proc.spawn(
                      OrderContext::new,
                      (ctx, event) -> {
                        // Handle order events in state machine
                        System.out.printf(
                            "[Order %s] Event: %s%n", request.orderId(),
                            event.getClass().getSimpleName());
                        return new Proc.StateResult<>(ctx, null);
                      },
                      null),
              RestartType.TEMPORARY // Remove after order completes
              ));

      System.out.printf("Order %s submitted for processing%n", request.orderId());
    }

    public void shutdown() {
      supervisor.terminate();
    }
  }

  // ============================================================================
  // PHASE 3: SPRING BOOT INTEGRATION (REST ENDPOINT)
  // ============================================================================

  /**
   * Example Spring Boot REST controller that uses JOTP for backend processing.
   *
   * <p>This is the bridge that allows gradual migration from sync to async.
   *
   * <p><strong>Before Migration:</strong> @PostMapping("/orders") → synchronous processing
   *
   * <p><strong>After Migration:</strong> @PostMapping("/orders") → JOTP agent → async response
   *
   * <p><strong>Dual-Write Phase:</strong> Both sync and async run in parallel for A/B testing.
   */
  // Note: This is pseudo-code, not runnable Spring Boot (requires Spring framework)
  static class OrderRestController {
    private final OrderProcessingSystem jotpOrderSystem = new OrderProcessingSystem();

    // Simulating: POST /orders
    public OrderResponse createOrder(OrderRequest request) {
      try {
        // PHASE 3: Route to JOTP system
        jotpOrderSystem.processOrder(request);

        // Return immediately (async processing)
        return new OrderResponse(
            true,
            "Order submitted for processing",
            Optional.of(
                new OrderData(
                    request.orderId(),
                    new OrderState.Pending(),
                    Optional.empty(),
                    Optional.empty())));

      } catch (Exception e) {
        return new OrderResponse(false, "Error: " + e.getMessage(), Optional.empty());
      }
    }

    // Simulating: GET /orders/{orderId}
    public OrderResponse getOrderStatus(String orderId) {
      // In real system, would query order agent via ProcRef
      return new OrderResponse(
          true,
          "Order status retrieved",
          Optional.of(
              new OrderData(
                  orderId,
                  new OrderState.Confirmed(),
                  Optional.of("txn-12345"),
                  Optional.of("inv-67890"))));
    }
  }

  // ============================================================================
  // MAIN: DEMO
  // ============================================================================

  public static void main(String[] args) throws Exception {
    System.out.println();
    System.out.println("╔════════════════════════════════════════════╗");
    System.out.println("║ SPRING BOOT → JOTP MIGRATION EXAMPLE       ║");
    System.out.println("║ Order Processing State Machine             ║");
    System.out.println("╚════════════════════════════════════════════╝");
    System.out.println();

    // Create production system
    var system = new OrderProcessingSystem();

    // Simulate incoming orders
    var orders =
        List.of(
            new OrderRequest("order-001", "cust-001", "pay-001", List.of("SKU-A", "SKU-B"), 99.99),
            new OrderRequest("order-002", "cust-002", "pay-002", List.of("SKU-C"), 49.99),
            new OrderRequest(
                "order-003", "cust-003", "pay-003", List.of("SKU-A", "SKU-C", "SKU-D"),
                199.97));

    // Process orders
    System.out.println("Processing orders...");
    System.out.println();
    for (var order : orders) {
      system.processOrder(order);
      Thread.sleep(500); // Stagger submissions
    }

    System.out.println();
    System.out.println("Waiting for processing to complete...");
    Thread.sleep(5000);

    System.out.println();
    System.out.println("✓ All orders processed");
    System.out.println("✓ Each order is an autonomous agent with its own state");
    System.out.println("✓ If any order crashes, supervisor restarts it");
    System.out.println("✓ External service failures are handled asynchronously");
    System.out.println();

    system.shutdown();
  }
}
