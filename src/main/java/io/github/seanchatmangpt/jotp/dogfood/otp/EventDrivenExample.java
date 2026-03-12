package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.EventManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dogfood: EventManager pub-sub with handler isolation.
 *
 * <p>Demonstrates OTP {@code gen_event} — a decoupled event manager where handlers are isolated
 * from one another. When a handler crashes, it is removed silently and other handlers continue
 * processing. This is a core pattern in Erlang for building loosely-coupled event-driven systems.
 *
 * <p><strong>Key properties demonstrated:</strong>
 *
 * <ul>
 *   <li><strong>Decoupling:</strong> Event producers (emit events) don't know about consumers
 *       (handlers)
 *   <li><strong>Isolation:</strong> A crashing handler is removed but doesn't kill the manager or
 *       affect other handlers
 *   <li><strong>Async & sync:</strong> {@code notify()} is async (fire-and-forget); {@code
 *       syncNotify()} waits for all handlers to complete
 *   <li><strong>Sealed types:</strong> Structured event hierarchy using sealed records
 * </ul>
 */
public final class EventDrivenExample {

    private EventDrivenExample() {}

    /**
     * Sealed event hierarchy for an order management system.
     *
     * <p>Each event is a complete, immutable record capturing a domain event. Handlers subscribe to
     * this event type and process independently.
     */
    sealed interface OrderEvent permits OrderCreated, OrderShipped, OrderCancelled {
        String orderId();
    }

    /** Event: order has been created with an amount. */
    record OrderCreated(String orderId, double amount, Instant timestamp) implements OrderEvent {}

    /** Event: order has been shipped to customer. */
    record OrderShipped(String orderId, String trackingId, Instant timestamp)
            implements OrderEvent {}

    /** Event: order has been cancelled by customer. */
    record OrderCancelled(String orderId, String reason, Instant timestamp) implements OrderEvent {}

    /**
     * Log handler: prints all events to stdout.
     *
     * <p>A simple handler that logs events as they arrive. This handler is stable (never crashes).
     */
    static final class LogHandler implements EventManager.Handler<OrderEvent> {
        private final List<String> logs = new ArrayList<>();

        @Override
        public void handleEvent(OrderEvent event) {
            String msg =
                    switch (event) {
                        case OrderCreated oc ->
                                String.format(
                                        "[LOG] OrderCreated: orderId=%s, amount=%.2f",
                                        oc.orderId(), oc.amount());
                        case OrderShipped os ->
                                String.format(
                                        "[LOG] OrderShipped: orderId=%s, trackingId=%s",
                                        os.orderId(), os.trackingId());
                        case OrderCancelled oc2 ->
                                String.format(
                                        "[LOG] OrderCancelled: orderId=%s, reason=%s",
                                        oc2.orderId(), oc2.reason());
                    };
            logs.add(msg);
            System.out.println(msg);
        }

        @Override
        public void terminate(Throwable reason) {
            if (reason == null) {
                System.out.println("[LOG] LogHandler terminated normally");
            } else {
                System.out.println("[LOG] LogHandler crashed: " + reason.getMessage());
            }
        }

        /** Returns all logged messages (for testing). */
        public List<String> getMessages() {
            return new ArrayList<>(logs);
        }
    }

    /**
     * Analytics handler: collects statistics and INTENTIONALLY CRASHES on OrderCancelled.
     *
     * <p>This handler demonstrates handler isolation: when it crashes, the EventManager removes it
     * and continues serving other handlers. The crash doesn't propagate to the manager or sibling
     * handlers.
     */
    static final class AnalyticsHandler implements EventManager.Handler<OrderEvent> {
        private int createdCount = 0;
        private double totalRevenue = 0;
        private int shippedCount = 0;
        private boolean terminated = false;

        @Override
        public void handleEvent(OrderEvent event) {
            if (terminated) return;

            switch (event) {
                case OrderCreated oc -> {
                    createdCount++;
                    totalRevenue += oc.amount();
                    System.out.printf(
                            "[ANALYTICS] Orders created: %d, Total revenue: %.2f%n",
                            createdCount, totalRevenue);
                }
                case OrderShipped os -> {
                    shippedCount++;
                    System.out.printf("[ANALYTICS] Orders shipped: %d%n", shippedCount);
                }
                case OrderCancelled oc2 -> {
                    // Intentionally crash to demonstrate handler isolation
                    throw new RuntimeException(
                            "Simulated analytics failure for orderId: " + oc2.orderId());
                }
            }
        }

        @Override
        public void terminate(Throwable reason) {
            terminated = true;
            if (reason == null) {
                System.out.println(
                        "[ANALYTICS] AnalyticsHandler terminated normally. "
                                + "Final stats: created="
                                + createdCount
                                + ", shipped="
                                + shippedCount);
            } else {
                System.out.println("[ANALYTICS] AnalyticsHandler crashed: " + reason.getMessage());
            }
        }

        public int getCreatedCount() {
            return createdCount;
        }

        public int getShippedCount() {
            return shippedCount;
        }

        public double getTotalRevenue() {
            return totalRevenue;
        }
    }

    /**
     * Notification handler: sends notifications to customers (email/SMS).
     *
     * <p>A stable handler that should continue working even if other handlers crash. Demonstrates
     * that handler isolation protects sibling handlers.
     */
    static final class NotificationHandler implements EventManager.Handler<OrderEvent> {
        private final List<String> sentNotifications = new ArrayList<>();

        @Override
        public void handleEvent(OrderEvent event) {
            String notification =
                    switch (event) {
                        case OrderCreated oc ->
                                "Email: Order "
                                        + oc.orderId()
                                        + " created (amount: $"
                                        + oc.amount()
                                        + ")";
                        case OrderShipped os ->
                                "Email: Order "
                                        + os.orderId()
                                        + " shipped (tracking: "
                                        + os.trackingId()
                                        + ")";
                        case OrderCancelled oc2 ->
                                "Email: Order "
                                        + oc2.orderId()
                                        + " cancelled (reason: "
                                        + oc2.reason()
                                        + ")";
                    };
            sentNotifications.add(notification);
            System.out.println("[NOTIFICATION] " + notification);
        }

        @Override
        public void terminate(Throwable reason) {
            if (reason == null) {
                System.out.println("[NOTIFICATION] NotificationHandler terminated normally");
            } else {
                System.out.println(
                        "[NOTIFICATION] NotificationHandler crashed: " + reason.getMessage());
            }
        }

        public List<String> getSentNotifications() {
            return new ArrayList<>(sentNotifications);
        }
    }

    /**
     * Main example: demonstrates pub-sub event manager with handler isolation.
     *
     * <p><strong>Scenario:</strong> Order service emits events; three handlers listen:
     *
     * <ol>
     *   <li>LogHandler: logs all events (stable)
     *   <li>AnalyticsHandler: collects stats, crashes on OrderCancelled (unstable)
     *   <li>NotificationHandler: sends notifications (stable)
     * </ol>
     *
     * <p><strong>Expected behavior:</strong>
     *
     * <ol>
     *   <li>When OrderCreated and OrderShipped arrive, all 3 handlers process them
     *   <li>When OrderCancelled arrives, AnalyticsHandler crashes and is removed
     *   <li>The manager continues; LogHandler and NotificationHandler keep processing
     *   <li>{@code notify()} is async; {@code syncNotify()} waits for all handlers
     * </ol>
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== JOTP EventManager Example: Pub-Sub with Handler Isolation ===\n");

        // Create the event manager
        EventManager<OrderEvent> manager = EventManager.start(Duration.ofSeconds(5));

        // Create handlers
        LogHandler logHandler = new LogHandler();
        AnalyticsHandler analyticsHandler = new AnalyticsHandler();
        NotificationHandler notificationHandler = new NotificationHandler();

        // Register all handlers
        System.out.println("Registering handlers...");
        manager.addHandler(logHandler);
        manager.addHandler(analyticsHandler);
        manager.addHandler(notificationHandler);
        System.out.println("✓ All handlers registered\n");

        // === Phase 1: Emit OrderCreated (all handlers will process) ===
        System.out.println("--- PHASE 1: OrderCreated event ---");
        OrderCreated event1 = new OrderCreated("ORD-001", 149.99, Instant.now());
        manager.syncNotify(event1);
        System.out.println("✓ OrderCreated processed by all handlers\n");

        // === Phase 2: Emit OrderShipped (all handlers will process) ===
        System.out.println("--- PHASE 2: OrderShipped event ---");
        OrderShipped event2 = new OrderShipped("ORD-001", "TRK-12345", Instant.now());
        manager.syncNotify(event2);
        System.out.println("✓ OrderShipped processed by all handlers\n");

        // === Phase 3: Emit OrderCancelled (AnalyticsHandler will crash) ===
        System.out.println("--- PHASE 3: OrderCancelled event (AnalyticsHandler will crash) ---");
        OrderCancelled event3 = new OrderCancelled("ORD-001", "Customer request", Instant.now());
        try {
            manager.syncNotify(event3);
        } catch (RuntimeException e) {
            // Note: syncNotify may raise RuntimeException if the handler crashes during broadcast.
            // In this case, we catch it because it's part of the demonstration.
            System.out.println("⚠ Expected: syncNotify raised exception due to handler crash");
        }
        System.out.println("✓ OrderCancelled broadcast complete; AnalyticsHandler removed\n");

        // === Phase 4: Emit second OrderCreated (only LogHandler & NotificationHandler remain) ===
        System.out.println(
                "--- PHASE 4: Another OrderCreated (AnalyticsHandler already removed) ---");
        OrderCreated event4 = new OrderCreated("ORD-002", 299.99, Instant.now());
        manager.syncNotify(event4);
        System.out.println("✓ Second OrderCreated processed by remaining handlers\n");

        // === Phase 5: Clean shutdown ===
        System.out.println("--- PHASE 5: Graceful shutdown ---");
        manager.stop();
        System.out.println("✓ Event manager stopped\n");

        // === Summary ===
        System.out.println("=== SUMMARY ===");
        System.out.println("LogHandler messages: " + logHandler.getMessages().size());
        System.out.println(
                "AnalyticsHandler stats (before crash): "
                        + "created="
                        + analyticsHandler.getCreatedCount()
                        + ", shipped="
                        + analyticsHandler.getShippedCount()
                        + ", revenue=$"
                        + analyticsHandler.getTotalRevenue());
        System.out.println(
                "NotificationHandler sent: "
                        + notificationHandler.getSentNotifications().size()
                        + " notifications");

        System.out.println("\n=== KEY POINTS ===");
        System.out.println("1. Handlers are isolated: one crashing doesn't affect others");
        System.out.println("2. notify() = async (fire-and-forget); syncNotify() = blocking");
        System.out.println("3. Failed handlers are auto-removed by the manager");
        System.out.println("4. The event manager itself never crashes (fault isolation)");
    }
}
