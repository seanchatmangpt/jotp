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

**ApplicationController** — static registry; equivalent to Erlang's `application:` module.

| Method | Erlang equivalent | Notes |
|---|---|---|
| `load(spec)` | `application:load/1` | Stores spec; no start |
| `unload(name)` | `application:unload/1` | Throws if running |
| `start(name)` | `application:start/1` | TEMPORARY run type |
| `start(name, RunType)` | `application:start/2` | PERMANENT/TRANSIENT/TEMPORARY |
| `start(name, RunType, StartType)` | `application:start/2` | Passes Normal/Takeover/Failover to callback |
| `stop(name)` | `application:stop/1` | Normal termination; TRANSIENT does NOT cascade |
| `stop(name, true)` | crash/abnormal exit | TRANSIENT cascades like PERMANENT |
| `restart(name)` | `application:restart/1` | Stop + start, preserves RunType and env overrides |
| `loadedApplications()` | `application:loaded_applications/0` | Includes running |
| `whichApplications()` | `application:which_applications/0` | Running only |
| `getEnv(app, key)` | `application:get_env/2` | Returns Optional |
| `getEnv(app, key, default)` | `application:get_env/3` | Returns value or default |
| `setEnv(app, key, value)` | `application:set_env/3` | Runtime override; survives restart |
| `unsetEnv(app, key)` | `application:unset_env/2` | Removes override; spec env visible again |
| `getKey(app, key)` | `application:get_key/2` | Keys: description/vsn/modules/registered/applications/env/mod/start_args |
| `reset()` | — | Test isolation; call in @BeforeEach |

Thread safety: all operations use ConcurrentHashMap. Call reset() in @BeforeEach.

**ApplicationSpec** — immutable record built via `ApplicationSpec.builder(name)`. Equivalent to a `.app` file. Fields: `description`, `vsn`, `modules`, `registered`, `applications` (dependencies), `env`, `mod` (callback), `startArgs`.

**ApplicationCallback<S>** — `-behaviour(application)` interface.

```java
ApplicationSpec.builder("my-app")
    .mod((startType, args) -> switch (startType) {
        case StartType.Normal()           -> startNormal(args);
        case StartType.Takeover(var node) -> takeoverFrom(node);
        case StartType.Failover(var node) -> failoverFrom(node);
    })
    .build();
```

The switch is exhaustive — compiler-enforced because StartType is sealed.

**StartType** — sealed: `Normal()`, `Takeover(String node)`, `Failover(String node)`. Use exhaustive switch in callbacks.

**RunType** — `PERMANENT` (cascade-stop all), `TRANSIENT` (treated as PERMANENT in this impl), `TEMPORARY` (default, no cascade).

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
├── Application.java           ← lifecycle orchestrator (Infrastructure nested interface)
├── ApplicationController.java ← application_controller: load/start/stop/query
├── ApplicationSpec.java       ← .app resource file as a Java record + Builder
├── ApplicationCallback.java   ← -behaviour(application): start/2, stop/1
├── StartType.java             ← sealed: Normal | Takeover(node) | Failover(node)
├── RunType.java               ← enum: PERMANENT | TRANSIENT | TEMPORARY
├── ApplicationInfo.java       ← {Name, Description, Vsn} tuple record
└── dogfood/          ← template-generated examples, not production primitives
```
