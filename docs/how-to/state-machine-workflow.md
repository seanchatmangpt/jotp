# State Machine Workflow

## Overview

While `Proc` is a simple message handler that transforms state on each message, `StateMachine` is a more structured abstraction for modeling complex workflows.

**StateMachine** separates three concerns:
- **State** — What the machine is doing (e.g., `Idle`, `Processing`, `Complete`)
- **Event** — What happened (e.g., `Start`, `Cancel`, `Finish`)
- **Data** — The machine's internal variables (order info, counters, etc.)

This is Erlang/OTP's `gen_statem` pattern, which prevents state explosion and makes workflows explicit.

## Basic Example: Order Processing

Imagine an order processing workflow:

```
Idle --(Start)--> Processing --(Complete)--> Done
  |                   |
  +--(Error)--------> Failed
```

With `Proc`, you'd handle all transitions in one big switch. With `StateMachine`, each state is a sealed type with its own transitions:

```java
import io.github.seanchatmangpt.jotp.*;

// Events (what can happen)
sealed interface OrderEvent {
    record Start() implements OrderEvent {}
    record Process() implements OrderEvent {}
    record Complete() implements OrderEvent {}
    record Fail(String reason) implements OrderEvent {}
    record Cancel() implements OrderEvent {}
}

// States (where the machine can be)
sealed interface OrderState {
    record Idle() implements OrderState {}
    record Processing() implements OrderState {}
    record Done(String result) implements OrderState {}
    record Failed(String reason) implements OrderState {}
}

// Data (mutable context)
record OrderData(
    String orderId,
    long startTime,
    int attemptCount
) {
    OrderData withAttemptCount(int count) {
        return new OrderData(orderId, startTime, count);
    }
}

// Define transitions
var machine = StateMachine.builder()
    // Idle state: only "Start" is valid
    .state(OrderState.Idle.class)
        .on(OrderEvent.Start.class, (event, data) ->
            Transition.to(new OrderState.Processing(), data)
        )

    // Processing state: "Complete" or "Fail" are valid
    .state(OrderState.Processing.class)
        .on(OrderEvent.Complete.class, (event, data) ->
            Transition.to(new OrderState.Done("Success!"), data)
        )
        .on(OrderEvent.Fail.class, (event, data) ->
            Transition.to(
                new OrderState.Failed(event.reason()),
                data.withAttemptCount(data.attemptCount() + 1)
            )
        )
        .on(OrderEvent.Cancel.class, (event, data) ->
            Transition.to(new OrderState.Failed("Cancelled"), data)
        )

    // Done state: no further transitions (terminal)
    .state(OrderState.Done.class)
        .onAny((event, data) ->
            Transition.stay(data)  // Ignore all events
        )

    // Failed state: no further transitions (terminal)
    .state(OrderState.Failed.class)
        .onAny((event, data) ->
            Transition.stay(data)
        )

    .build(
        new OrderState.Idle(),
        new OrderData("order-123", System.currentTimeMillis(), 0)
    );

// Wrap it in a process
var orderProcess = Proc.spawn(
    machine,
    (stateMachine, event) -> switch (event) {
        case OrderEvent e -> stateMachine.handle(e);
        default -> stateMachine;
    }
);

// Drive the workflow
orderProcess.tell(new OrderEvent.Start());       // Idle → Processing
orderProcess.tell(new OrderEvent.Process());      // ignored (not handled in Processing)
orderProcess.tell(new OrderEvent.Complete());     // Processing → Done
orderProcess.tell(new OrderEvent.Fail(...));      // ignored (Done is terminal)

// Check final state
var finalMachine = orderProcess.ask("get-state", 1000).join();
System.out.println("Final state: " + finalMachine.state());  // Done(Success!)
```

## Advanced: Actions and Guards

Transitions can have **guards** (conditions) and **actions** (side effects):

```java
sealed interface PaymentEvent {
    record Pay(double amount) implements PaymentEvent {}
}

sealed interface PaymentState {
    record Idle() implements PaymentState {}
    record Charged(double amount) implements PaymentState {}
    record Declined(String reason) implements PaymentState {}
}

var machine = StateMachine.builder()
    .state(PaymentState.Idle.class)
        .on(PaymentEvent.Pay.class, (event, data) -> {
            // Guard: only accept amounts > 0
            if (event.amount() <= 0) {
                return Transition.to(
                    new PaymentState.Declined("Invalid amount"),
                    data
                );
            }

            // Action: log the charge, charge card, etc.
            System.out.println("Charging: $" + event.amount());
            processCharge(event.amount());

            // Transition
            return Transition.to(
                new PaymentState.Charged(event.amount()),
                data
            );
        })

    .state(PaymentState.Charged.class)
        .onAny((event, data) -> Transition.stay(data))

    .build(new PaymentState.Idle(), data);
```

## Pattern: Timeouts with Timers

StateMachines often need timeouts (e.g., "abandon order if not completed within 5 minutes"):

```java
sealed interface OrderEvent {
    record Start() implements OrderEvent {}
    record Process() implements OrderEvent {}
    record Complete() implements OrderEvent {}
    record Timeout() implements OrderEvent {}
}

var orderProcess = Proc.spawn(
    StateMachine.builder()
        .state(OrderState.Processing.class)
            .on(OrderEvent.Complete.class, (event, data) ->
                Transition.to(new OrderState.Done("Success!"), data)
            )
            .on(OrderEvent.Timeout.class, (event, data) ->
                Transition.to(new OrderState.Failed("Timeout"), data)
            )
        .build(machine)
    ,
    (stateMachine, event) -> {
        // Reset timer on certain transitions
        if (event instanceof OrderEvent.Start) {
            // Clear old timer (if any)
            var timerRef = data.timerRef();
            if (timerRef != null) timerRef.cancel();

            // Set new timeout
            var newTimer = ProcTimer.sendAfter(
                5000,  // 5 seconds
                sender(),  // send to ourselves
                new OrderEvent.Timeout()
            );
            // Store timerRef in data for later cancellation
        }

        return switch (event) {
            case OrderEvent e -> stateMachine.handle(e);
            default -> stateMachine;
        };
    }
);
```

## Nested State Machines

For complex systems, nest state machines:

```java
sealed interface OrderState {
    record Payment(PaymentStateMachine paymentMachine) implements OrderState {}
    record Shipping(ShippingStateMachine shippingMachine) implements OrderState {}
    record Done() implements OrderState {}
}

// Main order machine delegates to payment machine when in Payment state
var orderMachine = StateMachine.builder()
    .state(OrderState.Payment.class)
        .on(PaymentEvent.class, (event, data) -> {
            var paymentMachine = OrderState.Payment.cast(state).paymentMachine();
            var newPaymentMachine = paymentMachine.handle((PaymentEvent) event);

            // Check if payment completed
            if (newPaymentMachine.state() instanceof PaymentState.Charged) {
                return Transition.to(
                    new OrderState.Shipping(shippingMachine),
                    data
                );
            }

            return Transition.to(
                new OrderState.Payment(newPaymentMachine),
                data
            );
        })
    // ... Shipping state ...
    .build(...);
```

## Code Lock Pattern

The pattern shown in the JOTP API docs uses a sealed code lock to ensure exhaustiveness:

```java
var result = switch (state) {
    case OrderState.Idle ignored -> {
        // Handle Idle state
        var nextState = processIdle(event);
        yield nextState;
    }
    case OrderState.Processing ignored -> {
        // Handle Processing state
        var nextState = processProcessing(event);
        yield nextState;
    }
    case OrderState.Done ignored -> {
        // Handle Done state (terminal)
        yield state;
    }
    // The compiler ensures you handle all cases!
};
```

This is type-safe: the compiler forces you to handle every state variant.

## When to Use StateMachine vs. Proc

| Scenario | Use |
|----------|-----|
| Simple message handler | `Proc` |
| Workflow with explicit states | `StateMachine` |
| Complex multi-stage process | `StateMachine` |
| State explosion (many states) | `StateMachine` |
| Fire-and-forget handler | `Proc` |

## Troubleshooting

**Q: I have a state transition, but it's not happening.**
A: Check:
1. Is the event type registered in that state?
2. Is the guard condition passing?
3. Are you calling `handle(event)` correctly?

**Q: How do I prevent invalid state transitions?**
A: That's the whole point of `StateMachine`! The sealed interface ensures only defined transitions occur. Unhandled events can be caught with `.onAny()`.

**Q: Can I add data to a transition?**
A: Yes! Return `Transition.to(nextState, newData)` to modify data.

## Next Steps

- **[Concurrent Pipelines](concurrent-pipelines.md)** — Chain state machines in parallel
- **[Creating Your First Process](creating-your-first-process.md)** — Review Proc basics
- **API Reference** — `StateMachine`, `Transition`, `Proc`
