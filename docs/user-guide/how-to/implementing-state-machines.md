# Implementing State Machines in JOTP

## Problem Statement
You need to model complex stateful workflows with multiple states, events, and transitions, such as order processing, protocol handshakes, or device lifecycle management.

## Solution Overview
JOTP's `StateMachine<S,E,D>` implements OTP's `gen_statem` behavior, providing full support for state machines with state timeouts, event timeouts, postponed events, and state enter callbacks.

## Step-by-Step Instructions

### 1. Define States, Events, and Data

**Define states** (sealed interface of records):

```java
public sealed interface LockState permits Locked, Open {}

public record Locked() implements LockState {}
public record Open() implements LockState {}
```

**Define events** (sealed interface of records):

```java
public sealed interface LockEvent permits PushButton, Reset {}

public record PushButton(char digit) implements LockEvent {}
public record Reset() implements LockEvent {}
```

**Define data** (carried across all states):

```java
public record LockData(String entered, String code) {
    public LockData {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("Code cannot be empty");
        }
    }

    public LockData withEntered(String newEntered) {
        return new LockData(newEntered, code);
    }
}
```

### 2. Create the State Machine

**Basic state machine** (no state enter):

```java
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.*;

var sm = StateMachine.of(
    new Locked(),                    // Initial state
    new LockData("", "1234"),        // Initial data
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                if (entered.equals(data.code())) {
                    // Correct code: unlock
                    yield Transition.nextState(new Open(), data.withEntered(""));
                } else if (entered.length() >= data.code().length()) {
                    // Wrong code: reset
                    yield Transition.keepState(data.withEntered(""));
                } else {
                    // Keep waiting
                    yield Transition.keepState(data.withEntered(entered));
                }
            }
            case SMEvent.User(Reset()) ->
                yield Transition.keepState(data.withEntered(""));
            default ->
                yield Transition.keepState(data);
        };

        case Open() -> switch (event) {
            case SMEvent.User(PushButton(var _)) ->
                // Any button while open: ignore (stay open)
                yield Transition.keepState(data);
            case SMEvent.User(Reset()) ->
                // Reset while open: lock it
                yield Transition.nextState(new Locked(), data);
            default ->
                yield Transition.keepState(data);
        };
    }
);
```

### 3. Handle Transitions

**Transition types:**

```java
// Move to new state
Transition.nextState(newState, newData)

// Stay in current state, update data
Transition.keepState(newData)

// Like keepState but re-triggers state enter + postponed event replay
Transition.repeatState(newData)

// Stop the state machine
Transition.stop("Invalid state")

// Stop and reply to pending calls
Transition.stopAndReply("Shutdown failed", actions...)
```

**Transition with actions:**

```java
yield Transition.nextState(
    new Open(),
    data.withEntered(""),
    Action.stateTimeout(10_000, "lock"), // Auto-lock after 10 seconds
    Action.reply(from, "Unlocked")       // Reply to pending call
);
```

### 4. Use State Timeouts

**State timeout** (auto-canceled on state change):

```java
var sm = StateMachine.of(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.StateTimeout(var content) -> {
                // Reset entered digits on timeout
                yield Transition.keepState(data.withEntered(""));
            }
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                yield Transition.nextState(
                    new Locked(),
                    data.withEntered(entered),
                    Action.stateTimeout(5_000, "reset") // Reset after 5s of inactivity
                );
            }
            // ... other cases
        };
        // ... other states
    }
);
```

**Event timeout** (canceled by any event):

```java
var sm = StateMachine.of(
    new WaitingForInput(),
    new Data(),
    (state, event, data) -> switch (state) {
        case WaitingForInput() -> switch (event) {
            case SMEvent.EventTimeout(var _) -> {
                // No input received within timeout
                yield Transition.nextState(new TimedOut(), data);
            }
            case SMEvent.User(InputReceived(var input)) -> {
                // Input received, cancel timeout automatically
                yield Transition.nextState(new Processing(), data.withInput(input));
            }
            // ... other cases
        };
        // ... other states
    }
);

// Start event timeout when entering waiting state
yield Transition.nextState(
    new WaitingForInput(),
    data,
    Action.eventTimeout(30_000, "timeout") // 30 second timeout
);
```

### 5. Use Postpone and NextEvent

**Postpone events** (defer until next state change):

```java
var sm = StateMachine.of(
    new Open(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Open() -> switch (event) {
            case SMEvent.User(PushButton(var _)) -> {
                // Defer button presses while open
                yield Transition.keepState(data, Action.postpone());
            }
            // ... other cases
        };
        case Locked() -> {
            // When we transition to Locked, all postponed events are replayed
            // So button presses during Open() will be processed here
            yield Transition.nextState(new Locked(), data);
        }
        // ... other states
    }
);
```

**NextEvent** (insert synthetic internal event):

```java
var sm = StateMachine.of(
    new Processing(),
    new Data(),
    (state, event, data) -> switch (state) {
        case Processing() -> switch (event) {
            case SMEvent.User(Start(var requestId)) -> {
                // Start processing, then validate when done
                yield Transition.nextState(
                    new Processing(),
                    data.withRequestId(requestId),
                    Action.nextEvent(new ValidateRequest())
                );
            }
            case SMEvent.Internal(ValidateRequest()) -> {
                // Synthetic event processed before next external event
                yield Transition.nextState(new Validating(), data);
            }
            // ... other cases
        };
        // ... other states
    }
);
```

### 6. Enable State Enter Callbacks

```java
var sm = StateMachine.create(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.Enter(var previousState) -> {
                // Called when entering Locked state
                logger.info("Entering Locked, previous: " + previousState);
                // Must return keepState or repeatState from enter callback
                yield Transition.keepState(data);
            }
            case SMEvent.User(PushButton(var b)) -> {
                // Normal event handling
                yield Transition.keepState(data.withEntered(data.entered() + b));
            }
            // ... other cases
        };
        // ... other states
    }
)
.withStateEnter() // Enable state enter callbacks
.start();
```

### 7. Send Events to State Machine

**Fire-and-forget (cast):**

```java
sm.send(new PushButton('1'));
sm.send(new PushButton('2'));
sm.send(new PushButton('3'));
sm.send(new PushButton('4'));
```

**Request-reply (call):**

```java
CompletableFuture<LockData> future = sm.call(new GetCurrentData());
LockData data = future.get(5, TimeUnit.SECONDS);
```

**Query current state/data:**

```java
LockState currentState = sm.state();
LockData currentData = sm.data();
boolean running = sm.isRunning();
String stopReason = sm.stopReason();
```

**Graceful shutdown:**

```java
sm.stop(); // Stops the state machine
```

## Common Mistakes

### 1. Forgetting to Handle All Event Types
```java
// BAD - doesn't handle StateTimeout
case Locked() -> switch (event) {
    case SMEvent.User(PushButton(var b)) -> handleButton(b);
    // StateTimeout not handled -> keeps state indefinitely
};

// GOOD - handle all event types
case Locked() -> switch (event) {
    case SMEvent.StateTimeout(var _) -> reset();
    case SMEvent.User(PushButton(var b)) -> handleButton(b);
    default -> Transition.keepState(data);
};
```

### 2. Mutable Data in Transitions
```java
// BAD - mutable data
record Data(List<String> items) {
    Data() {
        this.items = new ArrayList<>();
    }
}
// Handler modifies list in place
data.items().add("item");
yield Transition.keepState(data);

// GOOD - immutable data
record Data(List<String> items) {
    Data() {
        this.items = List.of();
    }
}
// Handler creates new instance
yield Transition.keepState(new Data(List.of(data.items(), "item")));
```

### 3. Not Setting Timeouts
```java
// BAD - waits forever
yield Transition.nextState(new WaitingForInput(), data);

// GOOD - set timeout
yield Transition.nextState(
    new WaitingForInput(),
    data,
    Action.eventTimeout(30_000, "timeout")
);
```

### 4. Blocking in Transition Function
```java
// BAD - blocks state machine thread
case SMEvent.User(ProcessLargeFile(var file)) -> {
    processFile(file); // Takes 10 seconds
    yield Transition.nextState(new Done(), data);
}

// GOOD - spawn worker process
case SMEvent.User(ProcessLargeFile(var file)) -> {
    var worker = Proc.spawn(file, this::processFileAsync);
    yield Transition.nextState(new Processing(), data.withWorker(worker));
}
```

## Complete Example: Traffic Light Controller

```java
public class TrafficLightController {
    public sealed interface LightState permits Red, Yellow, Green {}
    public record Red() implements LightState {}
    public record Yellow() implements LightState {}
    public record Green() implements LightState {}

    public sealed interface LightEvent permits VehicleDetected, EmergencyMode, NormalMode {}
    public record VehicleDetected() implements LightEvent {}
    public record EmergencyMode() implements LightEvent {}
    public record NormalMode() implements LightEvent {}

    public record LightData(int vehicleCount, boolean emergencyMode) {}

    public static void main(String[] args) throws InterruptedException {
        var sm = StateMachine.create(
            new Red(),
            new LightData(0, false),
            TrafficLightController::handleTransition
        )
        .withStateEnter()
        .start();

        // Simulate events
        sm.send(new VehicleDetected());
        sm.send(new EmergencyMode());

        Thread.sleep(10000);
        sm.stop();
    }

    private static Transition<LightState, LightData> handleTransition(
        LightState state,
        SMEvent<LightEvent> event,
        LightData data
    ) {
        return switch (state) {
            case Red() -> switch (event) {
                case SMEvent.Enter(var _) -> {
                    System.out.println("→ RED light");
                    yield Transition.nextState(
                        new Red(),
                        data,
                        Action.stateTimeout(5_000, "to-green") // 5 seconds
                    );
                }
                case SMEvent.StateTimeout(var _) ->
                    Transition.nextState(new Green(), data);
                case SMEvent.User(VehicleDetected()) ->
                    Transition.keepState(data.withVehicleCount(data.vehicleCount() + 1));
                case SMEvent.User(EmergencyMode _) ->
                    Transition.nextState(new Red(), data.withEmergencyMode(true));
                default ->
                    Transition.keepState(data);
            };

            case Green() -> switch (event) {
                case SMEvent.Enter(var _) -> {
                    System.out.println("→ GREEN light");
                    yield Transition.nextState(
                        new Green(),
                        data,
                        Action.stateTimeout(
                            data.emergencyMode() ? 2_000 : 10_000, // 2s emergency, 10s normal
                            "to-yellow"
                        )
                    );
                }
                case SMEvent.StateTimeout(var _) ->
                    Transition.nextState(new Yellow(), data);
                case SMEvent.User(EmergencyMode()) ->
                    Transition.nextState(new Red(), data.withEmergencyMode(true));
                case SMEvent.User(VehicleDetected()) ->
                    Transition.keepState(data.withVehicleCount(data.vehicleCount() + 1));
                default ->
                    Transition.keepState(data);
            };

            case Yellow() -> switch (event) {
                case SMEvent.Enter(var _) -> {
                    System.out.println("→ YELLOW light");
                    yield Transition.nextState(
                        new Yellow(),
                        data,
                        Action.stateTimeout(2_000, "to-red") // 2 seconds
                    );
                }
                case SMEvent.StateTimeout(var _) ->
                    Transition.nextState(new Red(), data.withVehicleCount(0));
                case SMEvent.User(EmergencyMode()) ->
                    Transition.nextState(new Red(), data.withEmergencyMode(true));
                default ->
                    Transition.keepState(data);
            };
        };
    }
}
```

## Related Guides
- [Process Management](./process-management.md) - Basic process spawning
- [Building Supervision Trees](./building-supervision-trees.md) - Supervise state machines
- [Error Handling](./error-handling.md) - Handle state machine crashes

## Advanced Patterns

### Hierarchical State Machines

```java
// Abstract states (sealed interfaces)
public sealed interface ConnectionState
    permits Connected, Disconnected, Connecting {}

public sealed interface Connected permits Authenticated, Unauthenticated {}

// Concrete states
public record Disconnected() implements ConnectionState {}
public record Connecting() implements ConnectionState {}
public record Unauthenticated() implements ConnectionState, Connected {}
public record Authenticated() implements ConnectionState, Connected {}

// Handler with nested pattern matching
case Connected() -> switch (event) {
    // Handles both Authenticated and Unauthenticated
    case SMEvent.User(Disconnect()) ->
        Transition.nextState(new Disconnected(), data);
    // ... shared Connected behavior
};
case Authenticated() -> switch (event) {
    // Authenticated-specific behavior
    case SMEvent.User(SendMessage(var msg)) ->
        Transition.keepState(data);
    // ... other cases
};
```

### State Machine with Supervisor

```java
// Wrap state machine in a supervisor
Supervisor supervisor = Supervisor.create(
    "traffic-lights",
    Supervisor.Strategy.ONE_FOR_ONE,
    3,
    Duration.ofMinutes(1)
);

// State machine as a supervised child
StateMachine<LightState, LightEvent, LightData> sm = StateMachine.of(
    new Red(),
    new LightData(0, false),
    TrafficLightController::handleTransition
);

// Wrap state machine in a Proc
Proc<StateMachine<LightState, LightEvent, LightData>, LightEvent> proc =
    Proc.spawn(sm, (state, event) -> {
        state.send(event);
        return state;
    });

supervisor.supervise("light-controller", sm, (state, event) -> {
    state.send(event);
    return state;
});
```

### Generic Timeouts

```java
// Multiple named timeouts that don't auto-cancel
yield Transition.nextState(
    new Active(),
    data,
    Action.genericTimeout("heartbeat", 5_000, "ping"),
    Action.genericTimeout("session", 30_000, "expire")
);

// In handler
case SMEvent.GenericTimeout("heartbeat", var content) -> {
    // Heartbeat timeout
    yield Transition.keepState(data, Action.genericTimeout("heartbeat", 5_000, "ping"));
}
case SMEvent.GenericTimeout("session", var content) -> {
    // Session expired
    yield Transition.nextState(new Expired(), data);
}
```
