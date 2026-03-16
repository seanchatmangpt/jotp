# Advanced JOTP Tutorial: State Machines

## Learning Objectives

By the end of this tutorial, you will:
- Master `StateMachine<S,E,D>` for complex workflows
- Define states, events, and guarded transitions
- Implement state timeouts for automatic transitions
- Build event handlers with side effects
- Create production-ready order processing workflows

## Prerequisites

- Complete [Beginner Tutorial: Processes](../beginner/processes.md)
- Complete [Supervision Trees Tutorial](supervision-trees.md)
- Understand sealed interfaces and pattern matching
- Basic knowledge of state machine concepts

## Introduction: Why State Machines?

State machines provide:
- **Explicit state**: Clear, enumerable states (no invalid combinations)
- **Type-safe transitions**: Compiler verifies all state transitions
- **Self-documenting**: Business logic is visible in state structure
- **Testable**: Each state/transition can be tested independently

Perfect for workflows like:
- Order processing (created → paid → shipped → delivered)
- User registration (email_sent → verified → profile_created)
- Document approval (draft → pending → approved → published)

## Basic State Machine Setup

### 1. Define States, Events, and Data

```java
import io.github.seanchatmangpt.jotp.statemachine.*;

// States - sealed interface for type safety
sealed interface OrderState permits
    OrderState.Created,
    OrderState.Paid,
    OrderState.Shipped,
    OrderState.Delivered,
    OrderState.Cancelled {

    record Created() implements OrderState {}
    record Paid(String paymentId) implements OrderState {}
    record Shipped(String trackingNumber) implements OrderState {}
    record Delivered() implements OrderState {}
    record Cancelled(String reason) implements OrderState {}
}

// Events - messages that trigger transitions
sealed interface OrderEvent permits
    OrderEvent.Pay,
    OrderEvent.Ship,
    OrderEvent.Deliver,
    OrderEvent.Cancel {

    record Pay(String paymentId) implements OrderEvent {}
    record Ship(String trackingNumber) implements OrderEvent {}
    record Deliver() implements OrderEvent {}
    record Cancel(String reason) implements OrderEvent {}
}

// Context data - shared across states
record OrderContext(
    String orderId,
    String customerId,
    double amount,
    long createdAt
) {}
```

### 2. Define Transitions

```java
class OrderTransitions implements StateMachine.Transitions<
    OrderState,
    OrderEvent,
    OrderContext
> {
    @Override
    public StateMachine.Transition<OrderState> transition(
        OrderState state,
        OrderEvent event,
        OrderContext context
    ) {
        return switch (state) {
            case OrderState.Created s -> switch (event) {
                case OrderEvent.Pay p -> {
                    System.out.println("Processing payment: " + p.paymentId());
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Paid(p.paymentId())
                    );
                }
                case OrderEvent.Cancel c -> {
                    System.out.println("Cancelling order: " + c.reason());
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Cancelled(c.reason())
                    );
                }
                default -> new StateMachine.Transition.Ignore<>();
            };

            case OrderState.Paid s -> switch (event) {
                case OrderEvent.Ship sh -> {
                    System.out.println("Shipping order: " + sh.trackingNumber());
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Shipped(sh.trackingNumber())
                    );
                }
                default -> new StateMachine.Transition.Ignore<>();
            };

            case OrderState.Shipped s -> switch (event) {
                case OrderEvent.Deliver d -> {
                    System.out.println("Order delivered!");
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Delivered()
                    );
                }
                default -> new StateMachine.Transition.Ignore<>();
            };

            // Terminal states ignore all events
            case OrderState.Delivered s,
                 OrderState.Cancelled s -> new StateMachine.Transition.Ignore<>();
        };
    }
}
```

### 3. Create and Use the State Machine

```java
class OrderProcessingExample {
    public static void main(String[] args) {
        var context = new OrderContext(
            "ORD-123",
            "CUST-456",
            99.99,
            System.currentTimeMillis()
        );

        var machine = StateMachine.create(
            new OrderState.Created(),
            new OrderTransitions(),
            context
        );

        // Process order lifecycle
        machine.handle(new OrderEvent.Pay("PAY-789"));
        machine.handle(new OrderEvent.Ship("TRACK-ABC"));
        machine.handle(new OrderEvent.Deliver());

        System.out.println("Final state: " + machine.currentState());
        // Output: Final state: Delivered()
    }
}
```

## Advanced Features

### 1. State Timeouts

Automatically transition if an event doesn't occur within a timeout.

```java
class OrderTransitionsWithTimeouts implements StateMachine.Transitions<
    OrderState,
    OrderEvent,
    OrderContext
> {
    @Override
    public StateMachine.Transition<OrderState> transition(
        OrderState state,
        OrderEvent event,
        OrderContext context
    ) {
        return switch (state) {
            case OrderState.Created s -> {
                // Add 5-minute timeout for payment
                yield new StateMachine.Transition.Next<>(
                    s,
                    Duration.ofMinutes(5)
                );
            }

            case OrderState.Paid s -> {
                // Add 1-hour timeout for shipping
                yield new StateMachine.Transition.Next<>(
                    s,
                    Duration.ofHours(1)
                );
            }

            default -> new StateMachine.Transition.Ignore<>();
        };
    }

    @Override
    public StateMachine.Transition<OrderState> timeout(
        OrderState state,
        OrderContext context
    ) {
        return switch (state) {
            case OrderState.Created s -> {
                System.out.println("Payment timeout - cancelling order");
                yield new StateMachine.Transition.Next<>(
                    new OrderState.Cancelled("Payment timeout")
                );
            }

            case OrderState.Paid s -> {
                System.out.println("Shipping timeout - escalating");
                // Could trigger notification or escalation
                yield new StateMachine.Transition.Next<>(s);
            }

            default -> new StateMachine.Transition.Ignore<>();
        };
    }
}
```

### 2. Guarded Transitions

Check conditions before allowing transitions.

```java
record OrderContext(
    String orderId,
    String customerId,
    double amount,
    long createdAt,
    double balance  // Customer balance
) {}

class OrderTransitionsWithGuards implements StateMachine.Transitions<
    OrderState,
    OrderEvent,
    OrderContext
> {
    @Override
    public StateMachine.Transition<OrderState> transition(
        OrderState state,
        OrderEvent event,
        OrderContext context
    ) {
        return switch (state) {
            case OrderState.Created s -> switch (event) {
                case OrderEvent.Pay p -> {
                    // Guard: Check sufficient balance
                    if (context.balance() < context.amount()) {
                        System.out.println("Insufficient funds!");
                        yield new StateMachine.Transition.Next<>(
                            new OrderState.Cancelled("Insufficient funds")
                        );
                    }

                    System.out.println("Payment accepted");
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Paid(p.paymentId())
                    );
                }
                default -> new StateMachine.Transition.Ignore<>();
            };

            default -> new StateMachine.Transition.Ignore<>();
        };
    }
}
```

### 3. Event Handlers with Side Effects

Execute actions during state transitions.

```java
class OrderTransitionsWithHandlers implements StateMachine.Transitions<
    OrderState,
    OrderEvent,
    OrderContext
> {
    @Override
    public StateMachine.Transition<OrderState> transition(
        OrderState state,
        OrderEvent event,
        OrderContext context
    ) {
        return switch (state) {
            case OrderState.Created s -> switch (event) {
                case OrderEvent.Pay p -> {
                    // Side effect: Update customer balance
                    // Side effect: Send confirmation email
                    // Side effect: Update inventory
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Paid(p.paymentId())
                    );
                }
                default -> new StateMachine.Transition.Ignore<>();
            };

            default -> new StateMachine.Transition.Ignore<>();
        };
    }

    @Override
    public void onEnter(
        OrderState newState,
        OrderState oldState,
        OrderContext context
    ) {
        // Called after entering a new state
        switch (newState) {
            case OrderState.Paid p -> {
                System.out.println("✓ Order paid - sending confirmation");
                // sendEmail(context.customerId, "Payment received");
            }
            case OrderState.Shipped s -> {
                System.out.println("✓ Order shipped - sending tracking info");
                // sendEmail(context.customerId, "Tracking: " + s.trackingNumber());
            }
            case OrderState.Delivered d -> {
                System.out.println("✓ Order delivered - sending thank you");
                // sendEmail(context.customerId, "Thank you for your order!");
            }
            case OrderState.Cancelled c -> {
                System.out.println("✗ Order cancelled - sending refund");
                // processRefund(context.orderId);
            }
            default -> {}
        }
    }
}
```

### 4. Integrating with Proc

Use state machines within processes for message-based workflows.

```java
sealed interface OrderMessage permits
    OrderMessage.ProcessEvent,
    OrderMessage.GetState {

    record ProcessEvent(OrderEvent event) implements OrderMessage {}
    record GetState(ProcRef<OrderState, OrderResponse> replyTo) implements OrderMessage {}
}

sealed interface OrderResponse permits OrderResponse.CurrentState {
    record CurrentState(OrderState state) implements OrderResponse {}
}

record OrderProcState(
    StateMachine<OrderState, OrderEvent, OrderContext> machine
) {}

class OrderProc {
    static Proc<OrderProcState, OrderMessage> create(
        String orderId,
        String customerId,
        double amount
    ) {
        var context = new OrderContext(
            orderId,
            customerId,
            amount,
            System.currentTimeMillis(),
            1000.0  // Initial balance
        );

        var machine = StateMachine.create(
            new OrderState.Created(),
            new OrderTransitionsWithHandlers(),
            context
        );

        return Proc.spawn(
            new OrderProcState(machine),
            (state, msg) -> {
                if (msg instanceof OrderMessage.ProcessEvent p) {
                    state.machine().handle(p.event());
                    System.out.println("New state: " + state.machine().currentState());
                    return new Proc.Continue<>(state);

                } else if (msg instanceof OrderMessage.GetState g) {
                    g.replyTo().tell(new OrderResponse.CurrentState(
                        state.machine().currentState()
                    ));
                    return new Proc.Continue<>(state);
                }

                return new Proc.Continue<>(state);
            }
        );
    }
}
```

## Exercise: Order Processing Workflow

Build a complete order processing system with state machines.

### Requirements

1. **Order States**: Created → Paid → Processing → Shipped → Delivered/Cancelled
2. **Timeouts**:
   - Created: 30 minutes to pay
   - Paid: 2 hours to start processing
   - Processing: 24 hours to ship
3. **Guards**:
   - Validate payment amount matches order total
   - Check inventory availability before processing
   - Verify shipping address
4. **Side Effects**:
   - Send email notifications on state changes
   - Update inventory
   - Log all transitions

### Solution

```java
// Order states
sealed interface OrderState permits
    OrderState.Created,
    OrderState.Paid,
    OrderState.Processing,
    OrderState.Shipped,
    OrderState.Delivered,
    OrderState.Cancelled {

    record Created() implements OrderState {}
    record Paid(String paymentId, double amount) implements OrderState {}
    record Processing() implements OrderState {}
    record Shipped(String trackingNumber) implements OrderState {}
    record Delivered() implements OrderState {}
    record Cancelled(String reason) implements OrderState {}
}

// Order events
sealed interface OrderEvent permits
    OrderEvent.ConfirmPayment,
    OrderEvent.StartProcessing,
    OrderEvent.ShipOrder,
    OrderEvent.ConfirmDelivery,
    OrderEvent.Cancel {

    record ConfirmPayment(String paymentId, double amount) implements OrderEvent {}
    record StartProcessing() implements OrderEvent {}
    record ShipOrder(String trackingNumber) implements OrderEvent {}
    record ConfirmDelivery() implements OrderEvent {}
    record Cancel(String reason) implements OrderEvent {}
}

// Context with inventory service
record OrderContext(
    String orderId,
    String customerId,
    String shippingAddress,
    double orderTotal,
    InventoryService inventory,
    EmailService email
) {}

interface InventoryService {
    boolean reserve(String orderId, List<String> items);
    void release(String orderId);
    boolean confirmReserved(String orderId);
}

interface EmailService {
    void send(String customerId, String subject, String body);
}

// Order transitions with guards, timeouts, and handlers
class OrderWorkflow implements StateMachine.Transitions<
    OrderState,
    OrderEvent,
    OrderContext
> {
    @Override
    public StateMachine.Transition<OrderState> transition(
        OrderState state,
        OrderEvent event,
        OrderContext ctx
    ) {
        return switch (state) {
            case OrderState.Created() -> switch (event) {
                case OrderEvent.ConfirmPayment p -> {
                    // Guard: Validate payment amount
                    if (p.amount() != ctx.orderTotal()) {
                        yield new StateMachine.Transition.Next<>(
                            new OrderState.Cancelled("Invalid payment amount")
                        );
                    }

                    // Guard: Check inventory
                    var items = List.of("item1", "item2");
                    if (!ctx.inventory().reserve(ctx.orderId(), items)) {
                        yield new StateMachine.Transition.Next<>(
                            new OrderState.Cancelled("Out of stock")
                        );
                    }

                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Paid(p.paymentId(), p.amount())
                    );
                }

                case OrderEvent.Cancel c ->
                    new StateMachine.Transition.Next<>(
                        new OrderState.Cancelled(c.reason())
                    );

                default -> new StateMachine.Transition.Ignore<>();
            };

            case OrderState.Paid() -> switch (event) {
                case OrderEvent.StartProcessing ignored ->
                    new StateMachine.Transition.Next<>(
                        new OrderState.Processing()
                    );

                case OrderEvent.Cancel c -> {
                    // Release inventory
                    ctx.inventory().release(ctx.orderId());
                    yield new StateMachine.Transition.Next<>(
                        new OrderState.Cancelled(c.reason())
                    );
                }

                default -> new StateMachine.Transition.Ignore<>();
            };

            case OrderState.Processing() -> switch (event) {
                case OrderEvent.ShipOrder s ->
                    new StateMachine.Transition.Next<>(
                        new OrderState.Shipped(s.trackingNumber())
                    );

                default -> new StateMachine.Transition.Ignore<>();
            };

            case OrderState.Shipped() -> switch (event) {
                case OrderEvent.ConfirmDelivery ignored ->
                    new StateMachine.Transition.Next<>(
                        new OrderState.Delivered()
                    );

                default -> new StateMachine.Transition.Ignore<>();
            };

            case OrderState.Delivered(),
                 OrderState.Cancelled() -> new StateMachine.Transition.Ignore<>();
        };
    }

    @Override
    public StateMachine.Transition<OrderState> timeout(
        OrderState state,
        OrderContext ctx
    ) {
        return switch (state) {
            case OrderState.Created() -> {
                ctx.email().send(
                    ctx.customerId(),
                    "Order expired",
                    "Your order was cancelled due to payment timeout"
                );
                yield new StateMachine.Transition.Next<>(
                    new OrderState.Cancelled("Payment timeout")
                );
            }

            case OrderState.Paid() -> {
                ctx.email().send(
                    ctx.customerId(),
                    "Order delayed",
                    "Your order is taking longer than expected"
                );
                yield new StateMachine.Transition.Next<>(state);
            }

            default -> new StateMachine.Transition.Ignore<>();
        };
    }

    @Override
    public void onEnter(
        OrderState newState,
        OrderState oldState,
        OrderContext ctx
    ) {
        switch (newState) {
            case OrderState.Paid() -> {
                ctx.email().send(
                    ctx.customerId(),
                    "Payment confirmed",
                    "Your order has been paid"
                );
            }

            case OrderState.Processing() -> {
                ctx.email().send(
                    ctx.customerId(),
                    "Order processing",
                    "Your order is being prepared"
                );
            }

            case OrderState.Shipped s -> {
                ctx.email().send(
                    ctx.customerId(),
                    "Order shipped",
                    "Tracking: " + s.trackingNumber()
                );
            }

            case OrderState.Delivered() -> {
                ctx.email().send(
                    ctx.customerId(),
                    "Order delivered",
                    "Thank you for your purchase!"
                );
            }

            case OrderState.Cancelled c -> {
                ctx.inventory().release(ctx.orderId());
                ctx.email().send(
                    ctx.customerId(),
                    "Order cancelled",
                    "Reason: " + c.reason()
                );
            }

            default -> {}
        }
    }
}
```

## What You Learned

- **State machine fundamentals**: States, events, and transitions
- **State timeouts**: Automatic transitions on delays
- **Guarded transitions**: Conditional logic before state changes
- **Event handlers**: Side effects on state transitions
- **Proc integration**: Message-based state machine workflows
- **Real-world patterns**: Complete order processing system

## Next Steps

- [Fault Tolerance Tutorial](fault-tolerance.md) - Make your state machines crash-resilient
- [Event Management Tutorial](event-management.md) - Broadcast state changes to multiple listeners
- [Distributed Systems Tutorial](distributed-systems.md) - Multi-node state machine replication

## Additional Exercises

1. **Saga Pattern**: Implement compensating transactions for distributed workflows
2. **State History**: Track all state transitions for audit trails
3. **Dynamic Workflows**: Build state machines from configuration files
4. **State Visualization**: Generate state diagrams from code
5. **Testing Framework**: Create property-based tests for state machines

## Further Reading

- [State Machine Design Patterns](https://refactoring.guru/design-patterns/state)
- [Saga Pattern for Microservices](https://microservices.io/patterns/data/saga.html)
- [JOTP StateMachine API](../../api/io/github/seanchatmangpt/jotp/statemachine/StateMachine.html)
