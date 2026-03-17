package io.github.seanchatmangpt.jotp.dogfood.messaging;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.channels.DeadLetterChannel;
import java.time.Duration;

/**
 * Runnable example demonstrating Dead Letter Channel pattern with poison pill recovery.
 *
 * <p>Scenario: An order processing system receives orders to validate. Some orders are "poison
 * pills" (malformed) that cause validation to fail. The Dead Letter Channel captures these failed
 * messages for inspection while keeping the main handler alive.
 */
public final class DeadLetterChannelExample {

    /** Represents an order record. */
    sealed interface OrderMsg permits OrderMsg.Order, OrderMsg.PoisonPill {
        record Order(String orderId, double amount) implements OrderMsg {
            public Order {
                if (orderId == null || orderId.isBlank()) {
                    throw new IllegalArgumentException("orderId must not be blank");
                }
                if (amount < 0) {
                    throw new IllegalArgumentException("amount must not be negative");
                }
            }
        }

        record PoisonPill() implements OrderMsg {}
    }

    /** System message for monitoring. */
    sealed interface SystemMsg permits SystemMsg.Report {
        record Report() implements SystemMsg {}
    }

    /** Main entry point demonstrating dead letter channel recovery. */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Dead Letter Channel Example: Poison Pill Recovery ===\n");

        // Create dead letter channel for failed orders
        var dlc = new DeadLetterChannel<OrderMsg>();

        // Create a process that handles orders, routing failures to the dead letter channel
        var supervisor =
                new Supervisor(
                        "order-supervisor",
                        Supervisor.Strategy.ONE_FOR_ONE,
                        5,
                        Duration.ofSeconds(60));

        var orderHandler =
                new Proc<>(
                        0,
                        (Integer state, OrderMsg msg) -> {
                            return switch (msg) {
                                case OrderMsg.Order order -> {
                                    System.out.println(
                                            "✓ Order processed: "
                                                    + order.orderId()
                                                    + " for $"
                                                    + order.amount());
                                    yield state + 1;
                                }
                                case OrderMsg.PoisonPill pill -> {
                                    dlc.send(pill, "poison pill — unprocessable message");
                                    System.out.println(
                                            "→ Poison pill routed to dead letter channel");
                                    yield state;
                                }
                            };
                        });

        // Send valid orders
        orderHandler.tell(new OrderMsg.Order("ORD-001", 99.99));
        orderHandler.tell(new OrderMsg.Order("ORD-002", 149.50));

        // Send poison pill — goes to dead letter channel instead of crashing
        System.out.println("\n→ Sending poison pill...");
        orderHandler.tell(new OrderMsg.PoisonPill());

        // Send more valid orders after the poison pill
        orderHandler.tell(new OrderMsg.Order("ORD-003", 49.99));

        // Allow processing to complete
        Thread.sleep(300);

        // Inspect dead letters
        System.out.println("\n=== Dead Letter Channel Inspection ===");
        var deadLetters = dlc.drain();
        if (deadLetters.isEmpty()) {
            System.out.println("(No dead letters)");
        } else {
            System.out.println("Dead letters captured: " + deadLetters.size());
            for (var dl : deadLetters) {
                System.out.println("  Message: " + dl.message());
                System.out.println("  Reason:  " + dl.reason());
                System.out.println("  Time:    " + dl.timestamp());
                System.out.println();
            }
        }

        System.out.println("=== Example Complete ===");
        orderHandler.stop();
        System.out.println("Order handler stopped gracefully.");
    }
}
