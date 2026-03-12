# How-to: Error Handling in JOTP

## Overview

JOTP provides several patterns for handling errors safely and idiomatically, inspired by Erlang/OTP's "let it crash" philosophy combined with Java 26's modern error handling mechanisms.

## Pattern 1: Using `Result<T, E>` for Railway-Oriented Programming

The `Result<T, E>` sealed interface provides a type-safe way to represent success or failure:

```java
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.Result.Ok;
import io.github.seanchatmangpt.jotp.Result.Err;

// Wrap a throwing operation
var result = Result.of(() -> Integer.parseInt("123"));
// result is now Ok<Integer>

// Chain operations
var doubled = result
    .map(n -> n * 2)
    .map(n -> "Result: " + n);

// Handle success or failure
var output = doubled.fold(
    err -> "Error: " + err.getMessage(),
    success -> success
);
```

## Pattern 2: Crash Recovery with Supervised Retry

For operations that may fail transiently, use `CrashRecovery` to retry with exponential backoff:

```java
import io.github.seanchatmangpt.jotp.CrashRecovery;

var result = CrashRecovery.retry(3, () -> {
    // This will be retried up to 3 times if it throws
    return unreliableNetworkCall();
});

if (result instanceof Ok<String> ok) {
    System.out.println("Success: " + ok.value());
} else {
    System.out.println("Failed after retries");
}
```

## Pattern 3: Process-Level Error Handling

Processes can trap exit signals from linked processes and handle them gracefully:

```java
import io.github.seanchatmangpt.jotp.*;

var worker = new Proc<>(0, (state, msg) -> {
    throw new RuntimeException("Worker crash");
});

var supervisor = new Proc<>(0, (state, msg) -> {
    if (msg instanceof ExitSignal signal) {
        System.out.println("Worker failed: " + signal.reason());
        return state + 1;  // count failures
    }
    return state;
});

// Enable exit signal trapping
supervisor.trapExits(true);

// Link supervisor to worker
ProcLink.link(supervisor, worker);

// Now when worker crashes, supervisor receives ExitSignal message
```

## Pattern 4: Supervisor Strategies for Fault Tolerance

The `Supervisor` class manages child processes with different restart strategies:

```java
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;

// ONE_FOR_ONE: restart only the failed child
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,  // max restarts
    Duration.ofSeconds(60)  // time window
);

// ALL_FOR_ONE: restart all children when one fails
var supervisor2 = new Supervisor(
    Supervisor.Strategy.ALL_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

// REST_FOR_ONE: restart failed child and all started after it
var supervisor3 = new Supervisor(
    Supervisor.Strategy.REST_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);
```

## Pattern 5: State Machine Error Transitions

Use `StateMachine<S, E, D>` to model error states explicitly:

```java
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;

record State {}
record Normal() extends State {}
record Error(String reason) extends State {}

sealed interface Event {}
record ProcessRequest() implements Event {}
record Failure(String reason) implements Event {}

var fsm = new StateMachine<>(
    new Normal(),
    (state, event, data) -> switch (state) {
        case Normal _ when event instanceof ProcessRequest ->
            Transition.next(new Normal());
        case Normal _ when event instanceof Failure f ->
            Transition.next(new Error(f.reason()));
        case Error e when event instanceof ProcessRequest ->
            Transition.next(new Normal());  // retry
        default -> Transition.keep();
    }
);
```

## Best Practices

1. **Use `Result<T, E>` for computations** — avoid throwing exceptions for expected failures
2. **Let processes crash** — supervision trees handle restart automatically
3. **Trap exits strategically** — only when you need custom handling
4. **Use state machines for complex error flows** — makes error paths explicit and testable
5. **Always set timeouts on `ask()`** — prevents deadlocks and hanging requests

## Further Reading

- [Tutorial 02: Your First Process](../tutorials/02-first-process.md) — Basic process patterns
- [Supervisor Strategy Reference](../reference/supervisor.md) — Detailed supervision semantics
- [PhD Thesis: Let It Crash](../phd-thesis-otp-java26.md#section-5-let-it-crash) — Formal error handling semantics
