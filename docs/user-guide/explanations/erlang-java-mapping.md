# Explanations: Erlang-Java Mapping

> "The BEAM is a virtual machine designed for concurrent, fault-tolerant systems. Java 26 with virtual threads and sealed types is the closest any mainstream language has come to BEAM semantics."

A comprehensive side-by-side reference for every major OTP pattern and its Java/JOTP equivalent. Use this as a lookup table when reading Erlang code and translating to Java.

---

## Processes

### Spawning a Process

| | Code |
|---|---|
| **Erlang** | `Pid = spawn(Module, Function, [InitArg]).` |
| **Java** | `var proc = new Proc<>(initialState, handler);` |
| **Notes** | Erlang's Pid is dynamic (atom); Java's Proc is typed `Proc<S,M>` |

### Spawning with Link

| | Code |
|---|---|
| **Erlang** | `Pid = spawn_link(Module, Function, Args).` |
| **Java** | `var proc = new Proc<>(state, handler); ProcLink.link(parentProc, proc);` |
| **Notes** | Bilateral crash propagation — if either crashes, the other receives EXIT signal |

### Checking if Process is Alive

| | Code |
|---|---|
| **Erlang** | `is_process_alive(Pid).` |
| **Java** | `proc.isAlive()` — check via `ProcRef` or direct `Proc` handle |

---

## Message Passing

### Send (Fire-and-Forget)

**Sequence Diagram:**

```
Erlang/OTP                         Java/JOTP
────────────────────────────────────────────────────────
Pid ! {increment, 5}                proc.tell(new Increment(5))
     │                                     │
     ▼                                     ▼
Message copied to mailbox          Message queued (ref copy)
     │                                     │
     ▼                                     ▼
Receiver processes later           Receiver processes later
```

| | Code |
|---|---|
| **Erlang** | `Pid ! {increment, 5}.` |
| **Java** | `proc.tell(new Increment(5));` |
| **Notes** | `tell()` is non-blocking; Erlang's `!` is also non-blocking |

### Synchronous Call with Reply

**Sequence Diagram:**

```
Erlang/OTP                         Java/JOTP
────────────────────────────────────────────────────────
gen_server:call(Pid, req, 5000)    proc.ask(req, 5s).get()
     │                                     │
     ▼                                     ▼
Send message + From                  Send msg + CompletableFuture
     │                                     │
     ▼                                     ▼
Block on receive                     Block on future.get()
     │                                     │
     ▼                                     ▼
{reply, Response}                    future.complete(response)
     │                                     │
     ▼                                     ▼
Return Response                      Return Response
```

| | Code |
|---|---|
| **Erlang** | `gen_server:call(Pid, get_count, 5000).` |
| **Java** | `proc.ask(new GetCount(), Duration.ofSeconds(5)).get();` |
| **Notes** | Both block caller; both have timeout; both fail with exception on timeout |

### Async Send + Receive Reply

| | Code |
|---|---|
| **Erlang** | `Pid ! {get, self()}, receive {count, N} -> N end.` |
| **Java** | `var future = proc.ask(new GetCount()); future.thenAccept(state -> use(state));` |

---

## Pattern Matching

### Message Dispatch

**Erlang:**
```erlang
receive
    {ok, Value} ->
        process(Value);
    {error, timeout} ->
        handle_timeout();
    {error, Reason} ->
        handle_error(Reason)
end.
```

**Java:**
```java
sealed interface Response permits Ok, Error {}
record Ok(int value) implements Response {}
sealed interface Error permits Timeout, OtherError {}
record Timeout() implements Error {}
record OtherError(String reason) implements Error {}

// In process handler:
(state, msg) -> switch (msg) {
    case Ok(var value)       -> process(value);
    case Timeout _           -> handleTimeout();
    case OtherError(var why) -> handleError(why);
}
// Compiler verifies ALL cases are handled
```

### Nested Pattern Matching

**Erlang:**
```erlang
case Result of
    {ok, {User, {wallet, Balance}}} when Balance > 0 ->
        charge(User, Balance);
    {ok, {User, {wallet, 0}}} ->
        decline(User);
    {error, not_found} ->
        create_account()
end.
```

**Java:**
```java
record Wallet(long balance) {}
record User(String id, Wallet wallet) {}

switch (result) {
    case Result.Success(User(var id, Wallet(var bal))) when bal > 0 ->
        charge(id, bal);
    case Result.Success(User(var id, Wallet(var bal))) when bal == 0 ->
        decline(id);
    case Result.Failure _ ->
        createAccount();
}
```

---

## gen_server Patterns

### Full gen_server Translation

**Erlang gen_server:**
```erlang
-module(account_server).
-behaviour(gen_server).

-record(state, {balance = 0, txs = []}).

init([]) -> {ok, #state{}}.

handle_call(balance, _From, State) ->
    {reply, State#state.balance, State};
handle_call({deposit, Amount}, _From, State) ->
    NewBalance = State#state.balance + Amount,
    NewState = State#state{balance = NewBalance,
                           txs = [{deposit, Amount} | State#state.txs]},
    {reply, NewBalance, NewState};
handle_call({withdraw, Amount}, _From, State) ->
    case State#state.balance >= Amount of
        true ->
            NewBalance = State#state.balance - Amount,
            {reply, {ok, NewBalance}, State#state{balance = NewBalance}};
        false ->
            {reply, {error, insufficient_funds}, State}
    end;
handle_cast(reset, _State) ->
    {noreply, #state{}}.
```

**Java/JOTP:**
```java
record AccountState(long balance, List<String> txLog) {}
sealed interface AccountMsg permits Balance, Deposit, Withdraw, Reset {}
record Balance() implements AccountMsg {}
record Deposit(long amount) implements AccountMsg {}
record Withdraw(long amount) implements AccountMsg {}
record Reset() implements AccountMsg {}

var account = new Proc<>(
    new AccountState(0, List.of()),
    (state, msg) -> switch (msg) {
        case Balance _ -> state;  // ask() returns state; caller reads balance

        case Deposit(var amount) -> new AccountState(
            state.balance() + amount,
            prepend(state.txLog(), "deposit:" + amount)
        );

        case Withdraw(var amount) when amount <= state.balance() -> new AccountState(
            state.balance() - amount,
            prepend(state.txLog(), "withdraw:" + amount)
        );

        case Withdraw _ -> state;  // Insufficient funds: keep state, caller handles via ask()

        case Reset _ -> new AccountState(0, List.of());
    }
);

// Equivalent of gen_server:call(Pid, balance)
long balance = account.ask(new Balance(), Duration.ofSeconds(1)).get().balance();

// Equivalent of gen_server:cast(Pid, reset)
account.tell(new Reset());
```

---

## Supervisor Patterns

### Architecture: Supervisor Tree Comparison

```
Erlang/OTP Supervisor Tree          Java/JOTP Supervisor Tree
─────────────────────────────────────────────────────────────
supervisor:start_link/2              new Supervisor(ONE_FOR_ONE, 5, 60s)
        │                                     │
        ▼                                     ▼
    ChildSpec1                          supervise("child1", ...)
        │                                     │
        ▼                                     ▼
    worker_1 (gen_server)              Proc<S, M>
        │                                     │
        ▼                                     ▼
    ChildSpec2                          supervise("child2", ...)
        │                                     │
        ▼                                     ▼
    worker_2 (gen_server)              Proc<S, M>

When worker_1 crashes:
  ONE_FOR_ONE → Restart only worker_1
  ONE_FOR_ALL → Restart worker_1 and worker_2
  REST_FOR_ONE → Restart worker_1 and all after it
```

### Child Specification Translation

**Erlang child spec:**
```erlang
ChildSpec = #{
    id => my_worker,
    start => {my_worker, start_link, []},
    restart => permanent,
    shutdown => 5000,
    type => worker,
    modules => [my_worker]
}.
```

**Java/JOTP:**
```java
// No explicit child spec — just call supervise()
ProcRef<WorkerState, WorkerMsg> ref = supervisor.supervise(
    "my-worker",           // id
    new WorkerState(),     // initial state (replaces start_link args)
    workerHandler          // handler function (replaces module)
);
// permanent restart is default
// 5000ms shutdown timeout is default
```

### Supervisor Strategy Translation

| Erlang | Java |
|--------|------|
| `{one_for_one, MaxR, MaxT}` | `new Supervisor(ONE_FOR_ONE, MaxR, Duration.ofSeconds(MaxT))` |
| `{one_for_all, MaxR, MaxT}` | `new Supervisor(ONE_FOR_ALL, MaxR, Duration.ofSeconds(MaxT))` |
| `{rest_for_one, MaxR, MaxT}` | `new Supervisor(REST_FOR_ONE, MaxR, Duration.ofSeconds(MaxT))` |

---

## gen_statem Patterns

### State Machine Translation

**Erlang gen_statem:**
```erlang
callback_mode() -> handle_event_function.

handle_event({call, From}, lock, unlocked, Data) ->
    {next_state, locked, Data, {reply, From, ok}};
handle_event({call, From}, unlock, locked, Data = #{code := Code, input := Code}) ->
    {next_state, unlocked, Data#{attempts => 0}, {reply, From, ok}};
handle_event({call, From}, unlock, locked, Data) ->
    Attempts = maps:get(attempts, Data, 0) + 1,
    {keep_state, Data#{attempts => Attempts}, {reply, From, {error, wrong_code}}}.
```

**Java/JOTP:**
```java
enum LockState { LOCKED, UNLOCKED }

sealed interface LockEvent permits Lock, Unlock {}
record Lock() implements LockEvent {}
record Unlock(String code) implements LockEvent {}

record LockData(String correctCode, int attempts) {}

var lock = new StateMachine<>(
    LockState.LOCKED,
    new LockData("1234", 0),
    (state, event, data) -> switch (state) {
        case LOCKED -> switch (event) {
            case Unlock(var code) when code.equals(data.correctCode()) ->
                new Transition.Next<>(LockState.UNLOCKED, new LockData(data.correctCode(), 0));
            case Unlock _ ->
                new Transition.Keep<>(new LockData(data.correctCode(), data.attempts() + 1));
            default -> new Transition.Keep<>(data);
        };
        case UNLOCKED -> switch (event) {
            case Lock _ -> new Transition.Next<>(LockState.LOCKED, data);
            default -> new Transition.Keep<>(data);
        };
    }
);
```

---

## Error Handling

### Tagged Tuples → Result<T,E>

| Erlang | Java |
|--------|------|
| `{ok, Value}` | `Result.Success<>(value)` or `Result.of(() -> value)` |
| `{error, Reason}` | `Result.Failure<>(reason)` |
| `case X of {ok, V} -> ...; {error, R} -> ...` | `switch (x) { case Success(var v) -> ...; case Failure(var r) -> ... }` |

**Railway composition:**

**Erlang:**
```erlang
case parse_input(Raw) of
    {ok, Input} ->
        case validate(Input) of
            {ok, Valid} ->
                process(Valid);
            {error, ValidationError} ->
                {error, ValidationError}
        end;
    {error, ParseError} ->
        {error, ParseError}
end.
```

**Java:**
```java
Result.of(() -> parseInput(raw))
    .flatMap(input -> validate(input))
    .flatMap(valid -> process(valid));
```

---

## Process Registry

### Name Registration

| | Code |
|---|---|
| **Erlang** | `global:register_name(counter, Pid).` |
| **Java** | `ProcRegistry.register("counter", proc);` |

### Name Lookup

| | Code |
|---|---|
| **Erlang** | `Pid = global:whereis_name(counter).` |
| **Java** | `var proc = ProcRegistry.whereis("counter");` |

### All Registered Names

| | Code |
|---|---|
| **Erlang** | `global:registered_names().` |
| **Java** | `Set<String> names = ProcRegistry.registered();` |

---

## Timers

### Send After

| | Code |
|---|---|
| **Erlang** | `timer:send_after(5000, self(), tick).` |
| **Java** | `ProcTimer.sendAfter(Duration.ofSeconds(5), proc, new Tick());` |

### Periodic Messages

| | Code |
|---|---|
| **Erlang** | `timer:send_interval(1000, self(), heartbeat).` |
| **Java** | `ProcTimer.sendInterval(Duration.ofSeconds(1), proc, new Heartbeat());` |

### Cancel Timer

| | Code |
|---|---|
| **Erlang** | `timer:cancel(TRef).` |
| **Java** | `timerRef.cancel();` |

---

## Monitoring and Linking

### Unilateral Monitor (DOWN notification, doesn't kill watcher)

| | Code |
|---|---|
| **Erlang** | `MonRef = monitor(process, Pid).` |
| **Java** | `var monitor = ProcMonitor.monitor(proc);` |

### Bilateral Link (both die if either crashes)

| | Code |
|---|---|
| **Erlang** | `link(Pid).` |
| **Java** | `ProcLink.link(procA, procB);` |

---

## Process Introspection (sys module)

| Erlang | Java |
|--------|------|
| `sys:get_state(Pid)` | `ProcSys.of(proc).getState()` |
| `sys:suspend(Pid)` | `ProcSys.of(proc).suspend()` |
| `sys:resume(Pid)` | `ProcSys.of(proc).resume()` |
| `sys:statistics(Pid, get)` | `ProcSys.of(proc).statistics()` |

---

## What Has No Direct Equivalent

| Erlang Feature | Java 26 Status | Notes |
|----------------|----------------|-------|
| Hot code loading | Not in Java | Use blue-green deployment |
| Distributed processes | Not in JOTP 1.0 | Planned for 2.0; use Kafka bridge |
| Selective receive | Not in JOTP | Use separate processes per message type |
| `ets`/`dets` in-memory tables | Not in JOTP | Use `ConcurrentHashMap` |
| Binary pattern matching | Java `instanceof` only | Use sealed records for structure |
| Process dictionary | `ScopedValue` (Java 26) | Better scoped, not per-process |
| OTP release management | Not applicable | Use Docker/Kubernetes |

### Migration Strategy: When Direct Equivalents Don't Exist

**Hot Code Loading → Blue-Green Deployment:**

```
Erlang/OTP                          Java/JOTP
────────────────────────────────────────────────────────
Release Handler:                    Kubernetes:
  - Load new code version              - Deploy new pod
  - Switch processes to new code       - Switch traffic (service)
  - Rollback if issues                 - Rollback if issues
```

**Distributed Processes → Kafka Bridge:**

```
Erlang/OTP                          Java/JOTP
────────────────────────────────────────────────────────
Node A ───Distributed Erlang───► Node B
                                       │
                                       ▼
                            Kafka Topic (message log)
                                       │
                                       ▼
JVM 1 ───JOTP + Kafka Producer──► Kafka ◄───JOTP + Kafka Consumer◄─── JVM 2
```

---

**Previous:** [Design Decisions](design-decisions.md) | **Next:** [Reference](../reference/)

**See Also:**
- [OTP Equivalence](otp-equivalence.md) — Formal proof of equivalence
- [How-To: Migrate from Erlang](../how-to/migrate-from-erlang.md) — Step-by-step migration guide
- [Reference: Glossary](../reference/glossary.md) — Terminology dictionary
