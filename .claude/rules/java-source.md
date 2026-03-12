---
paths:
  - "src/main/java/**/*.java"
---

# Java 26 Source Rules

Applies when editing any file under `src/main/java/`.

## Language Features in Use

**Sealed types** — restrict inheritance, enable exhaustive switch:
```java
public sealed interface Transition<S, D>
    permits Transition.Keep, Transition.Next, Transition.Stop {}
// Switch expression must handle all permits — compiler-enforced
```

**Pattern matching** — use record patterns and binding variables:
```java
if (shape instanceof Circle(var radius)) { ... }  // extracts field directly
switch (result) {
    case Success(var v) -> use(v);
    case Failure(var e) -> handle(e);
}
```

**Virtual threads + StructuredTaskScope** (preview, `--enable-preview` required):
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task = scope.fork(() -> compute());
    scope.join().throwIfFailed();
    return task.get();
}
```

**Scoped values** — prefer over ThreadLocal for virtual thread compatibility:
```java
static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();
ScopedValue.where(CURRENT_USER, user).run(() -> handleRequest());
```

## OTP Primitive Implementation Notes

**Proc<S,M>** (`src/main/java/.../Proc.java`):
- Uses `LinkedTransferQueue` (lock-free MPMC) as mailbox
- `tell()` fire-and-forget; `ask(msg, timeout)` sync with `Duration` timeout
- `trapExits(true)` converts exit signals to mailbox messages
- State handler is a pure function `(S state, M msg) -> S`

**Supervisor** (`src/main/java/.../Supervisor.java`):
- `ONE_FOR_ONE`: only restart crashed child
- `ONE_FOR_ALL`: restart all children when one crashes
- `REST_FOR_ONE`: restart crashed child + all children started after it
- Restart intensity: sliding window (maxRestarts within duration)
- Prefer `Supervisor.create(strategy, children)` static factory over constructor

**StateMachine<S,E,D>**:
- Three transition types: `NextState(newState, newData)`, `KeepState(newData)`, `Stop(reason)`
- Transition function must be pure (no side effects)
- Use sealed interface for S (states) and E (events) to get exhaustiveness checking

**ProcRef<S,M>** — stable handle that survives supervisor restarts; never hold raw Proc.

**CrashRecovery** — wraps supplier in isolated virtual thread; returns `Result<T, Exception>`.

## Forbidden Patterns (guards scan on every edit)

```java
// H_TODO — never leave these
// TODO: implement later
// FIXME: this is broken

// H_MOCK — never in src/main/java
class MockPaymentService implements PaymentService { ... }
var mockRepo = mockRepository();

// H_STUB — never return empty stubs
public String getName() { return ""; }
public List<Item> getItems() { return null; } // stub
```

Instead: throw `UnsupportedOperationException("not implemented: <reason>")` if
genuinely blocked, or implement the real logic.

## Package Structure

```
io.github.seanchatmangpt.jotp
├── Proc.java, ProcRef.java, ProcLink.java, ProcMonitor.java
├── ProcRegistry.java, ProcTimer.java, ProcSys.java, ProcLib.java
├── Supervisor.java, CrashRecovery.java
├── StateMachine.java, EventManager.java
├── Parallel.java, ExitSignal.java
├── Result.java
└── dogfood/          ← template-generated examples, not production primitives
```
