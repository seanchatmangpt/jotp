# Explanations: Let It Crash Philosophy

> "Erlang programmers don't believe in defensive programming. They know that programs will fail. They design systems to fail safely, to recover quickly, and to isolate failures so one bad component cannot take down the whole system. This is not laziness. This is engineering."
> — Joe Armstrong, *Programming Erlang* (2nd edition)

The foundational philosophy behind JOTP's fault-tolerance model. Every supervisor, every `Transition.stop()`, every crash callback — all of it flows from this single idea.

---

## The Traditional Java Approach and Its Hidden Cost

Java's heritage is defensive programming. You write code to handle every possible failure in place:

```java
// Traditional Java: defend at every level
public PaymentResult processPayment(PaymentRequest request) {
    try {
        validateRequest(request);
    } catch (ValidationException e) {
        log.warn("Invalid request: {}", e.getMessage());
        return PaymentResult.validationError(e.getMessage());
    }

    PaymentGateway gateway = null;
    try {
        gateway = gatewayPool.acquire();
        GatewayResponse response = gateway.charge(request.amount());
        if (response.status() == GATEWAY_ERROR) {
            // Is this a transient error? A permanent one? Should we retry?
            // We don't know, so we handle it here, partially, with guesswork:
            log.error("Gateway error: {}", response.errorCode());
            return PaymentResult.gatewayError(response.errorCode());
        }
        return PaymentResult.success(response.transactionId());
    } catch (GatewayTimeoutException e) {
        // Did the charge complete? We don't know.
        // Retrying might double-charge. Not retrying might miss a charge.
        log.error("Gateway timeout — payment state unknown", e);
        return PaymentResult.uncertain();  // The worst possible result
    } catch (Exception e) {
        log.error("Unexpected error processing payment", e);
        return PaymentResult.internalError();
    } finally {
        if (gateway != null) gatewayPool.release(gateway);
    }
}
```

This code is trying to be correct. It is not. The try/catch soup has five failure modes that all return different `PaymentResult` variants — and the *worst* one, `PaymentResult.uncertain()`, emerges from the one case that matters most (the gateway timing out when the charge may or may not have completed). The state machine's context is now corrupted: you don't know if the charge happened.

**The hidden cost:** Every `catch` block that swallows an error and returns a partial result makes the system's state harder to reason about. After ten such handlers, you have a system that appears healthy in logs while quietly accumulating invalid state. Production bugs in this code are not the bugs you caught — they are the invalid state transitions that happened silently for months before someone noticed the accounts were wrong.

---

## Armstrong's Insight: Clean Restart Beats Partial Recovery

Armstrong's insight was specific and uncomfortable: **attempting to recover from a partially-corrupted state is more dangerous than crashing and restarting with clean state.**

The argument:

1. **You can only clean up what you know is broken.** If your error handler runs after a complex operation fails halfway through, it may not know which parts succeeded and which failed. Cleanup code that runs in an unknown state is worse than no cleanup code — it can make the corruption systematic.

2. **Clean state is trustworthy.** A process that starts fresh from its initial state is in a known-good condition. The supervisor knows the initial state is valid; the initial state was designed and tested. A process that recovered from a partially-completed operation is in a state nobody designed and nobody tested.

3. **Supervisors separate "fail fast" from "keep trying."** The process crashes when it encounters an unrecoverable situation. The *supervisor* — not the process — is responsible for the recovery strategy. This separation of concerns means the process can be pure (handle happy paths, crash on unexpected input) and the supervisor can be policy (how many restarts, with what backoff, with what fallback).

```
Traditional approach:
  Process ──► Error ──► Error handler ──► Partial recovery ──► Continue
                               ↑
                        "What state am I in?"

Let it crash:
  Process ──► Error ──► CRASH
                           ↓
                    Supervisor detects crash
                           ↓
                    Restart with clean initial state
                           ↓
                    Continue (from known-good state)
```

The key move: remove the error handler from the process. Move recovery to the supervisor. The process becomes a pure function of messages. The supervisor becomes the recovery policy.

---

## The Three Conditions for "Let It Crash"

"Let it crash" is not appropriate in all contexts. It requires three things to be true:

### Condition 1: The State Is Disposable or Recoverable

The process's in-memory state must be either disposable (losing it is acceptable) or recoverable from a durable source.

**Disposable:** A cache process. If it crashes, it restarts empty. The worst case is a cache miss — a performance cost, not a data loss event.

**Recoverable:** A payment state machine. If it crashes, it restarts from the last durable checkpoint (the payment event log). The state at crash time is reconstructed by replaying events. Nothing is lost.

**NOT disposable/recoverable:** A counter that tracks the number of unique user IDs seen, with no persistence. If this crashes, the count is gone. "Let it crash" here means accepting data loss. This is sometimes acceptable; it must be a conscious choice.

### Condition 2: A Supervisor Will Restart with Clean State

"Let it crash" requires that the crash is supervised. An unsupervised crash is not "let it crash" — it is just a crash. The process must be under a `Supervisor` that will restart it.

```java
// WRONG: "let it crash" without supervision = bug, not design
var counter = new Proc<>(0, (count, msg) -> switch (msg) {
    case Increment _ -> count + 1;
    case InvalidMsg _ -> throw new RuntimeException("invalid");  // crashes unrecoverably
});

// RIGHT: supervised crash = designed recovery
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
ProcRef<Integer, Msg> counter = supervisor.supervise("counter", 0,
    (count, msg) -> switch (msg) {
        case Increment _ -> count + 1;
        case InvalidMsg _ -> throw new RuntimeException("invalid");  // supervisor restarts
    }
);
```

### Condition 3: Errors Are Logged for Human Investigation

A process that crashes and restarts silently is a process that can crash indefinitely without anyone noticing. "Let it crash" does not mean "ignore the crash." It means "don't handle the error *here*." The crash must be logged, ideally with the message that caused it.

JOTP's `Supervisor` logs every restart. The `lastError` field on a `Proc` contains the last crash reason. The `addCrashCallback()` hook fires on every abnormal termination. In production, every crash should trigger at minimum a `logger.error()` call.

```java
proc.addCrashCallback(() ->
    logger.error("Process {} crashed: {}", proc, proc.lastError)
);
```

---

## Where "Let It Crash" Is the Wrong Approach

Armstrong was clear about the limits of his own philosophy. These are the cases where defensive programming is correct:

### 1. Operations with external side effects that cannot be undone

If your handler sends an HTTP request to charge a credit card, crashing and restarting after the charge has been sent (but before the response was received) creates a duplicate charge risk. "Let it crash" is wrong here. The correct pattern is:

1. Use a state machine (`StateMachine<S,E,D>`) that transitions through explicit states: `CHARGING → CHARGED → SETTLED`
2. Make the charge operation idempotent (pass a UUID, gateway deduplicates)
3. Use `Transition.stop()` to terminate the state machine if the charge state is unknown — then restart from the last known-good checkpoint

The principle holds: don't *handle* the error in place. But the process must be designed with idempotency and durable checkpoints before "let it crash" applies safely.

### 2. Resource allocation that requires explicit cleanup

If your process holds an open file handle, database transaction, or locked mutex, crashing without cleanup leaks the resource. The fix is not to add a `catch` block — it is to design state cleanup into the crash path:

```java
// Supervisor restart will create a fresh process
// But ensure resource is released even on crash:
proc.addCrashCallback(() -> {
    if (fileHandle != null) fileHandle.close();
    if (transaction != null) transaction.rollback();
});
```

### 3. Hardware actuators and physical systems

If your process controls a physical device (a valve, a motor, a display), crashing and restarting can leave the hardware in a dangerous intermediate state. This is not a JOTP context — it is a real-time embedded systems context. The philosophy does not apply.

---

## How JOTP Implements This

Every JOTP primitive is designed around the let-it-crash philosophy:

**`Proc<S,M>`:** The handler is a pure function. Throw a `RuntimeException` from the handler to crash the process. The supervisor restarts it. No try/catch in the handler means no possibility of partial recovery disguised as success.

**`Supervisor`:** The supervisor is the recovery policy. Its three restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE) represent three different answers to "what should happen to the system when one component fails?" The restart intensity limit (`maxRestarts` within `window`) is the circuit breaker: if a process keeps crashing, the supervisor stops restarting it and surfaces the failure to its own parent.

**`StateMachine<S,E,D>.Transition.stop(reason)`:** When a state machine encounters an unrecoverable situation, it calls `Transition.stop("reason")`. This terminates the state machine gracefully — no crash, but no pretending either. The machine is done; the reason is recorded; the caller receives an `IllegalStateException` if they were waiting on a call.

**`CrashRecovery`:** For cases where retry-with-backoff is the correct policy (transient external service failures), `CrashRecovery` implements "let it crash and try again" with explicit retry limits and backoff strategy — not hidden in a catch block, but as a first-class architectural choice.

```java
// The "let it crash" pattern in JOTP
var processor = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofMinutes(1));

ProcRef<ProcessorState, ProcessorMsg> worker = processor.supervise(
    "payment-worker",
    ProcessorState.INITIAL,
    (state, msg) -> switch (msg) {
        // Happy path: pure data transformation
        case ProcessPayment(var req) -> state.withPayment(req);
        // Error path: throw, crash, supervisor restarts with INITIAL state
        case InvalidPayment(var reason) ->
            throw new IllegalArgumentException("Invalid payment: " + reason);
    }
);
// If InvalidPayment arrives 4 times in 1 minute → supervisor crashes
// Parent supervisor or monitoring system detects this and escalates
```

---

## The Practical Consequence for Architecture

Adopting "let it crash" changes how you design software at every level:

**Process design:** Handlers become shorter. There are no nested try/catch blocks. Each handler does one thing and either succeeds or crashes. A handler that is 50 lines long is a smell — it is probably doing too much and should be split across a state machine or multiple cooperating processes.

**Error propagation:** Errors propagate through supervision tree topology, not through return values or exception hierarchies. A failure in a leaf process crashes that process, which the supervisor restarts. If the leaf keeps crashing, the supervisor crashes, which its parent restarts. Failures bubble up naturally without explicit propagation code.

**Testing:** Testing a JOTP handler is testing a pure function. You can write:
```java
// Test the crash behavior directly — no mocking needed
assertThatThrownBy(() -> handler.apply(state, new InvalidMsg()))
    .isInstanceOf(IllegalArgumentException.class);
```

**Operations:** When a process crashes in production, the log entry is the entire story: message type, stack trace, process ID, crash count. You don't need to trace through layers of error handling to understand what happened.

---

## From Philosophy to Practice

> "The question is not whether errors will occur. They will. The question is what your system does when they do."

The let-it-crash philosophy answers: crash fast, isolate the failure, restart clean, keep the rest of the system running. It is the OTP answer to 40 years of distributed systems engineering.

JOTP brings this answer to Java — with the type safety and tooling Java developers expect, and the fault-tolerance guarantees Erlang engineers have relied on since 1987.

---

**See Also:**
- [Handling Process Crashes](../how-to/handling-process-crashes.md) — Practical crash handling patterns
- [Build Supervision Trees](../how-to/build-supervision-trees.md) — Structuring recovery policies
- [Tutorial 04: Supervision Basics](../tutorials/04-supervision-basics.md) — Hands-on supervision tutorial
- [Supervisor API](../reference/api-supervisor.md) — Complete Supervisor reference
- [Design Decisions](design-decisions.md) — Why JOTP chose pure handlers over class-based actors

---

**Previous:** [Design Decisions](design-decisions.md) | **Next:** [OTP Equivalence](otp-equivalence.md)
