# Explanations: Design Decisions

> "Every design is a bet. The best designers place bets they can reason about. They're honest about the trade-offs. And they don't bet more than they can afford to lose."
> — Adapted from Joe Armstrong's approach to pragmatic system design

Every architecture involves trade-offs. This document explains the reasoning behind JOTP's major decisions: what we chose, what we rejected, and — critically — where the choices were genuinely hard.

---

## Decision 1: Sealed Interfaces for Messages

**Chosen:** `sealed interface Msg permits Increment, Reset, GetState {}`

**Rejected:** Open interfaces with `instanceof` chains, `Object` message type, `enum` discriminators

### Architecture: Type System Hierarchy

```
┌─────────────────────────────────────────────────────────────────┐
│                      Sealed Message Type System                  │
└─────────────────────────────────────────────────────────────────┘

                     Message (sealed interface)
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
    CounterMsg         AccountMsg        SystemMsg
    (sealed)           (sealed)          (sealed)
         │                 │                 │
    ┌────┴────┐      ┌─────┴─────┐      ┌───┴────┐
    │         │      │           │      │        │
 Increment  Reset  Deposit  Withdraw  Shutdown  Debug
 (record)  (record) (record)  (record)   (record) (record)

Compiler guarantee: All subtypes known at compile time
Exhaustiveness checking: switch must handle ALL cases
```

### Comparison: Sealed vs. Open Types

**Open Type (BAD):**
```java
interface Message {}

class Increment implements Message {}
class Reset implements Message {}

// Handler:
if (msg instanceof Increment) { ... }
else if (msg instanceof Reset) { ... }
// Easy to miss a case — silent runtime bug
```

**Sealed Type (GOOD):**
```java
sealed interface Message permits Increment, Reset {}
record Increment(int n) implements Message {}
record Reset() implements Message {}

// Handler:
switch (msg) {
    case Increment(var n) -> ...;
    case Reset _ -> ...;
    // COMPILER ERROR if case missing
}
```

### Why Sealed Interfaces Win

Sealed interfaces give the compiler complete knowledge of all message types. This enables exhaustive pattern matching:

```java
// Sealed: compiler verifies ALL cases handled
sealed interface CounterMsg permits Increment, Reset, GetState {}

(state, msg) -> switch (msg) {
    case Increment _ -> state + 1;
    case Reset _     -> 0;
    case GetState _  -> state;
    // No default needed — compiler knows these are all cases
}

// CONTRAST: Open interface — compiler cannot verify completeness
interface CounterMsg {}  // BAD
(state, msg) -> {
    if (msg instanceof Increment) ...
    else if (msg instanceof Reset) ...
    // Easy to forget a case. Silently broken at runtime.
}
```

**The Fortune 500 consequence:** In a payment system handling 10 types of financial events, a missing `instanceof` case silently drops messages. A sealed interface makes this a compile error. The cost of the type annotation is zero; the cost of a silent bug in production is catastrophic.

**Migration from Akka's untyped actors:** Akka Typed already moved in this direction. If you're migrating from Akka, this decision is not a new concept — it's a Java-native version of what TypedActor.Behavior already requires.

---

## Decision 2: Pure Handler Functions Over Object-Oriented Actors

**Chosen:** `BiFunction<S, M, S>` — a pure function `(state, message) → new_state`

**Rejected:** Class-based actors with mutable fields, Akka-style `Behavior<M>` objects

### Why Pure Functions Win

The handler function signature is `(S state, M msg) → S newState`. This is a pure function:
- Given the same state and message, always returns the same new state
- No hidden state, no side effects on the handler itself
- Completely testable without mocking

```java
// Test a Proc handler with zero infrastructure
var handler = (AccountState state, AccountMsg msg) -> switch (msg) {
    case Deposit(var amount) -> new AccountState(state.balance() + amount);
    case Withdraw(var amount) -> {
        if (amount > state.balance()) throw new InsufficientFundsException();
        yield new AccountState(state.balance() - amount);
    }
};

// UNIT TEST: Pure function, no Proc needed
@Test void deposit_increases_balance() {
    var initial = new AccountState(100);
    var result = handler.apply(initial, new Deposit(50));
    assertThat(result.balance()).isEqualTo(150);
}
```

**The Akka comparison:** In Akka, a `Behavior<M>` is an object that returns a new `Behavior<M>` from `onMessage()`. The behavior itself can carry state, reference other actors, and perform side effects in the handler body. This flexibility creates complexity: you need to reason about behavior state and message handler state separately.

JOTP collapses this. The process state `S` is the only state. The handler is stateless. Testing is trivial. Composability is maximal.

### Composability Proof

Pure handlers compose naturally:

```java
// Compose two handlers: audit everything, then delegate
BiFunction<State, Msg, State> audit = (state, msg) -> {
    log.info("Processing: {}", msg);
    return state;  // audit doesn't change state
};

BiFunction<State, Msg, State> business = (state, msg) -> switch (msg) {
    // ... business logic
};

// Composed handler: audit then process
BiFunction<State, Msg, State> audited = audit.andThen(
    (state) -> (msg) -> business.apply(state, msg)
);
```

Try composing Akka Behaviors or Spring component handlers this cleanly.

---

## Decision 3: Rejected the Actor Model

**Rejected:** Akka Typed actors, Vert.x Verticles, Quasar Actors

### What Actors Got Wrong

The actor model as popularized by Akka conflates three concerns:
1. **Identity** — the actor reference (PID)
2. **Behavior** — the message handler
3. **Lifecycle** — supervision, restart, state management

In JOTP, these are separate:
- **Identity:** `ProcRef<S,M>` — a stable handle that survives restarts
- **Behavior:** `BiFunction<S,M,S>` — a pure function
- **Lifecycle:** `Supervisor` — manages restarts, strategies, intensity limits

Separation allows independent evolution, testing, and composition. You can swap the behavior without touching the identity. You can test the behavior without starting the lifecycle.

**The Akka API complexity problem:**

```scala
// Akka Typed: behavior + lifecycle + identity entangled
object Counter {
  sealed trait Command
  case class Increment(replyTo: ActorRef[Int]) extends Command
  case class GetValue(replyTo: ActorRef[Int]) extends Command

  def apply(): Behavior[Command] = counting(0)
  private def counting(n: Int): Behavior[Command] =
    Behaviors.receiveMessage {
      case Increment(replyTo) =>
        replyTo ! n + 1
        counting(n + 1)
      case GetValue(replyTo) =>
        replyTo ! n
        Behaviors.same
    }
}
```

```java
// JOTP: behavior is just a function
sealed interface CounterMsg permits Increment, GetValue {}
record Increment() implements CounterMsg {}
record GetValue() implements CounterMsg {}

BiFunction<Integer, CounterMsg, Integer> counter = (n, msg) -> switch (msg) {
    case Increment _ -> n + 1;
    case GetValue _ -> n;
};
```

JOTP's version is testable as a pure function, requires no supervision framework to start, and has no circular dependency between the handler and the identity reference.

---

## Decision 4: Virtual Threads Over Reactive Programming

**Chosen:** Virtual threads (`Thread.startVirtualThread`)

**Rejected:** Project Reactor (`Mono`/`Flux`), RxJava, CompletableFuture chains

### Architecture: Concurrency Model Comparison

```
┌─────────────────────────────────────────────────────────────────┐
│                    Virtual Thread Model (JOTP)                   │
└─────────────────────────────────────────────────────────────────┘

  Process 1      Process 2      Process 3      Process N
  (virtual)      (virtual)      (virtual)      (virtual)
      │              │              │              │
      ▼              ▼              ▼              ▼
  ┌────────────────────────────────────────────────────┐
  │         Blocking I/O (traditional Java API)         │
  │  - httpClient.send()  - db.query()  - file.read()  │
  └────────────────────────────────────────────────────┘
      │              │              │              │
      ▼              ▼              ▼              ▼
  ┌────────────────────────────────────────────────────┐
  │              Virtual Thread Scheduler               │
  │      (ForkJoinPool, Carrier Threads = CPU cores)   │
  └────────────────────────────────────────────────────┘

Code: Sequential, blocking, easy to debug


┌─────────────────────────────────────────────────────────────────┐
│                  Reactive Model (Project Reactor)                │
└─────────────────────────────────────────────────────────────────┘

  Mono/Flux chains
      │
      ▼
  ┌─────────────────────────────────────────────────────────┐
  │  .flatMap(req -> fetchUser(req.userId()))               │
  │  .flatMap(user -> fetchAccount(user.accountId()))       │
  │  .flatMap(account -> charge(account, amount))           │
  │  .onErrorResume(InsufficientFundsException.class, ...)  │
  │  .subscribe()                                           │
  └─────────────────────────────────────────────────────────┘
      │
      ▼
  ┌─────────────────────────────────────────────────────────┐
  │         Reactive Scheduler (event loop, callbacks)      │
  └─────────────────────────────────────────────────────────┘

Code: Async chains, hard to debug, callback hell in a suit
```

### The Reactive Programming Problem

Reactive programming solves a real problem (non-blocking I/O) with the wrong abstraction level. The result is "callback hell in a suit":

```java
// Reactive: logic fragmented across operators
Mono.just(request)
    .flatMap(req -> userService.fetchUser(req.userId()))
    .flatMap(user -> accountService.fetchAccount(user.accountId()))
    .flatMap(account -> paymentService.charge(account, request.amount()))
    .onErrorResume(InsufficientFundsException.class, e -> Mono.error(new PaymentFailed(e)))
    .doOnSuccess(result -> auditLog.record(result))
    .subscribe();
```

This code is:
- Untestable without a reactive test harness
- Impossible to debug with a stack trace (frames are internal operators)
- Unreadable to 90% of Java developers
- Brittle in error handling (which `onErrorResume` catches which exception?)

```java
// JOTP: logic is sequential, readable, debuggable
(state, msg) -> {
    var user = userService.fetchUser(msg.userId());   // blocks virtual thread
    var account = accountService.fetchAccount(user);   // blocks virtual thread
    var result = paymentService.charge(account, msg);  // blocks virtual thread
    auditLog.record(result);
    return new State(result);
};
```

The JOTP version is:
- Debuggable with standard stack traces
- Testable as a pure function
- Readable by any Java developer
- Exception handling follows standard Java semantics

**Performance parity:** Virtual threads eliminate the performance reason to prefer reactive. A virtual thread that blocks on I/O costs ~100 ns to park, releasing the carrier thread. The reactive chain has equivalent overhead in operator allocation and scheduling. At 10K concurrent requests, virtual threads and reactive have nearly identical throughput.

> **Joe Armstrong's position:** Armstrong explicitly rejected callback-based concurrency in Erlang. The receive loop is blocking (from the process's perspective), yet massively concurrent. Virtual threads bring this model to Java.

---

## Decision 5: Result<T,E> Over Checked Exceptions

**Chosen:** `Result<T,E>` sealed type with `Success`/`Failure` variants

**Rejected:** Checked exceptions, unchecked exceptions only, `Optional`

### Why Exceptions Fail at Process Boundaries

Exception-based error handling has one fatal flaw for process-based systems: exceptions do not compose across process boundaries.

```java
// Exception-based: information lost across boundary
try {
    proc.ask(new PaymentMsg(amount), Duration.ofSeconds(5));
} catch (Exception e) {
    // What happened? Was it a timeout? A payment failure? A network error?
    // The exception type and message are your only hints.
    // The domain semantics are gone.
}
```

`Result<T,E>` preserves domain semantics:

```java
// Result-based: error is part of the domain model
sealed interface PaymentResult permits Success, Failure {
    record Success(String transactionId) implements PaymentResult {}
    record Failure(FailureReason reason) implements PaymentResult {}
}

enum FailureReason { INSUFFICIENT_FUNDS, GATEWAY_TIMEOUT, INVALID_CARD }

// Caller gets typed, exhaustive error handling
switch (proc.ask(new PaymentMsg(amount), Duration.ofSeconds(5))) {
    case PaymentResult.Success(var txId) -> confirmPayment(txId);
    case PaymentResult.Failure(INSUFFICIENT_FUNDS) -> showFundsError();
    case PaymentResult.Failure(GATEWAY_TIMEOUT) -> retryLater();
    case PaymentResult.Failure(INVALID_CARD) -> updateCard();
}
```

**Railway-oriented composition:**

```java
var result = Result.of(() -> parseInput(raw))
    .flatMap(input -> validateInput(input))
    .flatMap(valid -> processPayment(valid))
    .map(receipt -> formatReceipt(receipt));

// Error handling at the end, not scattered throughout
switch (result) {
    case Success(var receipt) -> return receipt;
    case Failure(var ex) -> throw new ApiException(ex);
}
```

**Checked exceptions rejected because:** They require every intermediate layer to handle or re-throw exceptions from lower layers, creating massive API surface area. In a process hierarchy with 10 levels, every level must know about every lower-level exception type. This violates encapsulation.

---

## Decision 6: Exactly 15 Primitives

**Chosen:** 15 primitives covering the 80/20 rule of OTP

**Rejected:** "Complete" OTP coverage (~40+ modules), minimalist core (3-5 primitives)

### The 80/20 Application

Erlang/OTP has ~40 standard modules. In practice, production Erlang systems use 5-7 core modules for 95% of their code:

1. `gen_server` (→ `Proc<S,M>`) — 80% of all processes
2. `supervisor` (→ `Supervisor`) — every production system
3. `gen_statem` (→ `StateMachine`) — complex workflows
4. `gen_event` (→ `EventManager`) — event broadcasting
5. `proc_lib` (→ `ProcLib`) — startup handshake
6. `sys` (→ `ProcSys`) — observability
7. `timer` (→ `ProcTimer`) — timed messages

The remaining 33 modules are used by specialist code — distributed systems, hot code loading, trace frameworks. JOTP does not implement them because:
- They require distribution infrastructure (not in scope)
- They serve < 5% of use cases
- Building them incorrectly is worse than not building them

JOTP's 15 primitives = OTP's 7 core + supporting infrastructure (links, monitors, registry, crash recovery, exit signals, parallel). This is the point of maximum leverage.

### Why Not More?

Every additional primitive is a learning burden on developers. The ideal API surface is the minimum that enables all production patterns. Adding primitives for edge cases:
- Increases documentation surface
- Creates decision paralysis ("which primitive should I use?")
- Slows adoption — the most common JOTP objection is "it's too complex," not "it's missing features"

**The 15-primitive constraint is a product decision, not a technical limitation.**

---

## Decision 7: No Built-in Distribution

**Chosen:** Single-JVM only (in 1.0)

**Rejected:** Embedded cluster (like Akka Cluster), gRPC-based distribution, Infinispan integration

### Honest Assessment

Distribution is the hardest problem in concurrent systems. The fallacies of distributed computing (network is reliable, latency is zero, bandwidth is infinite, topology doesn't change) make every distributed system fundamentally harder than its single-machine equivalent.

Erlang took 10 years to get distribution right. Akka Cluster took 5 years and still has known limitations. Kubernetes Operators add another layer of complexity.

JOTP 1.0 makes a deliberate choice: **be excellent at single-JVM fault tolerance** rather than mediocre at distributed fault tolerance.

**What you can do today for distribution:**
- Run multiple JOTP JVMs, coordinate via Kafka (message log is the truth)
- Use JOTP within a single Spring Boot service, deploy that service with Kubernetes (pod = single-JVM)
- Gateway pattern: JOTP handles per-JVM concurrency, a message bus handles cross-JVM coordination

**The roadmap:** Distribution is planned for JOTP 2.0. The design will learn from Erlang's proven approach (distributed Erlang) and Java's existing ecosystem (gRPC, Kafka, Chronicle Map). Not before it can be done correctly.

---

## Decision 8: Java Records for Messages

**Chosen:** Strongly recommend `record` types for messages and state

**Rejected:** POJO classes, `Map<String, Object>`, `String`/JSON messages

### Why Records Are the Right Type

Java records (JEP 395) are:
- **Immutable by construction:** All fields are `final`. You cannot accidentally mutate a message in transit.
- **Value semantics:** `equals()` and `hashCode()` based on fields. Two `Deposit(100)` records are equal.
- **Transparent:** `toString()` returns the field values. Debugging is trivial.
- **Deconstructable:** Pattern matching can destructure records directly.

```java
// Records enable pattern matching with destructuring
case Deposit(var amount) when amount > 10_000 -> flagForReview(amount);
case Deposit(var amount) -> processNormalDeposit(amount);
```

**The JSON alternative rejected:** Using `Map<String, Object>` or JSON strings for messages trades type safety for flexibility. The flexibility is never needed — message types in a system are known at compile time. The cost is runtime errors from `ClassCastException` and `NullPointerException` that sealed interfaces eliminate at compile time.

---

## Rejected Alternatives Summary

| Decision | What We Chose | What We Rejected | Why Rejected |
|----------|---------------|------------------|--------------|
| Message types | Sealed interfaces | Open interfaces | Missing case = silent bug |
| Handler model | Pure `BiFunction<S,M,S>` | Class-based actors | Entangles identity/behavior/lifecycle |
| Concurrency model | Virtual threads | Reactive (Mono/Flux) | Unreadable, hard to debug, same perf |
| Error handling | `Result<T,E>` | Checked exceptions | Breaks across process boundaries |
| API breadth | 15 primitives | Full OTP (40+ modules) | Learning burden; edge cases not worth it |
| Distribution | None in 1.0 | Embedded cluster | Distribution is hard; get 1-JVM right first |
| Message encoding | Java records | JSON / Map<String,Object> | Runtime errors vs compile errors |
| Actor framework | Rejected | Akka actors | Entangled concerns |

---

## What We'd Change in Hindsight

**`ask()` timeout:** The default ask timeout being configurable at the API call level is correct. But we'd add a per-process default timeout in 1.1 to reduce boilerplate in high-throughput services where every call uses the same timeout.

**`ProcRegistry` naming:** The global registry naming mirrors Erlang's `global:register_name/2`. In Java contexts, "Registry" suggests ServiceRegistry (Spring) or JNDI. We'd rename to `ProcNameService` in 2.0 for clarity.

**Bounded mailboxes:** JOTP 1.0 uses unbounded mailboxes (a slow consumer can accumulate unlimited messages). In 2.0, bounded mailboxes with configurable back-pressure strategies will be the default, with unbounded as opt-in. Erlang uses unbounded mailboxes and has had production incidents because of it — JOTP should learn from this.

**Type variance:** The `Proc<S,M>` type parameters are invariant. For event broadcasting patterns, covariant message types (`Proc<S, ? extends BaseMsg>`) would allow better hierarchy composition. This requires careful API design and is planned for 2.0.

---

## Architecture: Decision Trade-off Matrix

```
                    ┌─────────────────────────────────────────┐
                    │     JOTP Design Decision Space          │
                    └─────────────────────────────────────────┘

                              Type Safety
                                  ▲
                                  │
            Sealed Interfaces    │    Open Types
           (compile errors)      │   (runtime errors)
                   ┌─────────────┴─────────────┐
                   │                           │
         ┌─────────┴─────────┐       ┌─────────┴─────────┐
         │                   │       │                   │
  Pure Functions      Mutable Objects
  (testable)         (hard to test)
         │                   │
         └─────────┬─────────┘
                   │
      Virtual Threads    Reactive Streams
      (blocking OK)      (async chains)
                   │
         ┌─────────┴─────────┐
         │                   │
 Result<T,E>      Checked Exceptions
 (railway)        (cross-boundary)
                   │
         ┌─────────┴─────────┐
         │                   │
    15 Primitives    Full OTP (40+)
    (80/20 rule)     (complexity)
```

**Chosen path:** Follow arrows down-left (JOTP design)
- Sealed interfaces → Pure functions → Virtual threads → Result<T,E> → 15 primitives
- Maximizes correctness, testability, simplicity

---

## The Underlying Principle

Every decision above follows one principle:

**Make the correct thing easy and the incorrect thing impossible.**

Sealed interfaces make the incorrect thing (missing a message type) a compile error.
Pure functions make the incorrect thing (shared mutable state) a type error.
`Result<T,E>` makes the incorrect thing (ignoring errors) a compile error.
Virtual threads make the incorrect thing (blocking all threads) not a thing.

This is the philosophy of both Joe Armstrong (make the impossible impossible at the language level) and modern type-system research (correctness by construction). JOTP extends it to the Java ecosystem.

---

**Previous:** [Concurrency Model](concurrency-model.md) | **Next:** [Erlang-Java Mapping](erlang-java-mapping.md)
