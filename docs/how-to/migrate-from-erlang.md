# How-To: Migrate from Erlang/OTP to JOTP

> "The goal of OTP was not to make Erlang programs look like Erlang programs. The goal was to solve the right problems: fault tolerance, concurrency, and distribution. JOTP solves the same problems in Java."
> — Adapted from Joe Armstrong's writings on OTP design

This guide helps Erlang and Elixir developers port OTP patterns to JOTP. Every OTP concept has a direct Java equivalent — the mapping is intentional and complete.

---

## The Core Translation

The fundamental shift: Erlang's dynamic typing and pattern matching translate to Java's sealed interfaces and pattern switch expressions.

| Erlang Concept | Java/JOTP Equivalent | Notes |
|----------------|---------------------|-------|
| `spawn(M, F, A)` | `new Proc<>(state, handler)` | Direct equivalent |
| `Pid ! Message` | `proc.tell(msg)` | Fire-and-forget |
| `gen_server:call(Pid, Msg, Timeout)` | `proc.ask(msg, Duration)` | Synchronous request-reply |
| `supervisor:start_link/2` | `new Supervisor(strategy, max, window)` | Supervision tree |
| `gen_statem` | `StateMachine<S,E,D>` | State machine |
| `gen_event` | `EventManager<E>` | Event broadcasting |
| `process_flag(trap_exit, true)` | `proc.trapExits(true)` | Exit signal handling |
| `{ok, Value} \| {error, Reason}` | `Result<T,E>` sealed type | Railway-oriented errors |
| `global:register_name/2` | `ProcRegistry.register(name, proc)` | Name registry |
| `timer:send_after/3` | `ProcTimer.sendAfter(delay, proc, msg)` | Timed messages |
| `monitor(process, Pid)` | `ProcMonitor.monitor(proc)` | Unilateral monitoring |
| `link(Pid)` | `ProcLink.link(proc1, proc2)` | Bilateral crash propagation |
| `sys:get_state/1` | `ProcSys.of(proc).getState()` | Process introspection |
| `proc_lib:start_link/3` | `ProcLib.startLink(...)` | Startup handshake |

---

## Pattern-by-Pattern Translation

### 1. Spawning Processes

**Erlang:**
```erlang
% Define a process that maintains a counter
counter_loop(Count) ->
    receive
        increment ->
            counter_loop(Count + 1);
        {get, ReplyPid} ->
            ReplyPid ! {count, Count},
            counter_loop(Count);
        stop ->
            ok
    end.

% Spawn it
Pid = spawn(mymodule, counter_loop, [0]).
```

**Java/JOTP:**
```java
// Define messages as sealed records
sealed interface CounterMsg permits Increment, Get, Stop {}
record Increment() implements CounterMsg {}
record Get() implements CounterMsg {}
record Stop() implements CounterMsg {}

// Create process (Proc is the equivalent of spawn + gen_server combined)
var counter = new Proc<>(
    0,  // Initial state (equivalent to counter_loop(0))
    (count, msg) -> switch (msg) {
        case Increment _ -> count + 1;
        case Get _       -> count;    // state returned = response
        case Stop _      -> count;    // use proc.stop() to actually stop
    }
);
```

**Key difference:** In Erlang, you send messages to a Pid and the process decides how to reply using another `Pid ! reply`. In JOTP, `ask()` returns the state after processing — the "reply" is part of the state type. Design your state to contain what callers need to read.

---

### 2. Message Tuples → Sealed Records

**Erlang message tuples:**
```erlang
% Messages are tagged tuples
Pid ! {pay, Amount, Currency}.
Pid ! {refund, TransactionId}.
Pid ! {get_balance, ReplyPid}.
```

**Java sealed records:**
```java
// Messages are sealed interfaces with record implementations
sealed interface PaymentMsg permits Pay, Refund, GetBalance {}
record Pay(BigDecimal amount, String currency) implements PaymentMsg {}
record Refund(String transactionId) implements PaymentMsg {}
record GetBalance() implements PaymentMsg {}

// Pattern matching is exhaustive (compiler enforces)
(state, msg) -> switch (msg) {
    case Pay(var amount, var currency) -> state.debit(amount);
    case Refund(var txId)              -> state.credit(lookup(txId));
    case GetBalance _                  -> state;  // no state change
}
```

**Why sealed records are better than tuples:**
- Compiler enforces exhaustive pattern matching (missing a case = compile error)
- Type parameters carry domain meaning (`PaymentMsg`, not just `Object`)
- Refactoring is safe: rename a record field, and all usages fail to compile

---

### 3. gen_server → Proc

**Erlang gen_server:**
```erlang
-module(counter_server).
-behaviour(gen_server).

% Callback implementations
handle_call(get_count, _From, Count) ->
    {reply, Count, Count};
handle_call({add, N}, _From, Count) ->
    {reply, ok, Count + N};
handle_cast(reset, _Count) ->
    {noreply, 0};
handle_info(timeout, Count) ->
    {noreply, Count};
init([]) ->
    {ok, 0}.
```

**Java/JOTP:**
```java
sealed interface CounterMsg permits GetCount, Add, Reset {}
record GetCount() implements CounterMsg {}
record Add(int n) implements CounterMsg {}
record Reset() implements CounterMsg {}

// One handler function replaces handle_call + handle_cast + handle_info
var counter = new Proc<>(
    0,
    (count, msg) -> switch (msg) {
        case GetCount _ -> count;           // caller reads state after ask()
        case Add(var n) -> count + n;       // synchronous: use ask()
        case Reset _    -> 0;              // fire-and-forget: use tell()
    }
);

// Equivalent of gen_server:call
int current = counter.ask(new GetCount(), Duration.ofSeconds(1)).get();

// Equivalent of gen_server:cast
counter.tell(new Reset());

// Equivalent of gen_server:call with argument
counter.ask(new Add(5), Duration.ofSeconds(1)).get();
```

**No init/terminate callbacks:** JOTP has no `init()` or `terminate()` callbacks. Set initial state in the constructor (`new Proc<>(initialState, handler)`). For cleanup on termination, use `addCrashCallback()` or a `ProcMonitor`.

---

### 4. supervisor → Supervisor

**Erlang supervisor:**
```erlang
-module(my_supervisor).
-behaviour(supervisor).

init([]) ->
    {ok, {
        {one_for_one, 5, 60},  % Strategy, MaxRestarts, MaxSeconds
        [
            {worker_1, {worker_module, start_link, []}, permanent, 5000, worker, [worker_module]},
            {worker_2, {worker_module, start_link, []}, permanent, 5000, worker, [worker_module]}
        ]
    }}.
```

**Java/JOTP:**
```java
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,  // {one_for_one, ...}
    5,                                 // MaxRestarts
    Duration.ofSeconds(60)            // MaxSeconds
);

// Children added via supervise(), not child specs
ProcRef<WorkerState, WorkerMsg> worker1 = supervisor.supervise(
    "worker-1",              // Child ID
    new WorkerState(),       // Initial state (replaces start_link args)
    workerHandler            // Handler function (replaces module callbacks)
);

ProcRef<WorkerState, WorkerMsg> worker2 = supervisor.supervise(
    "worker-2",
    new WorkerState(),
    workerHandler
);
```

**Differences:**
- No `child_spec` — use `supervise(id, state, handler)` directly
- No `permanent` / `transient` / `temporary` child types — all children are permanent by default
- No module registration — handlers are lambda functions, not module names

---

### 5. gen_statem → StateMachine

**Erlang gen_statem:**
```erlang
-module(door_statem).
-behaviour(gen_statem).

% States: locked, unlocked
% Events: lock, unlock, push
callback_mode() -> state_functions.

locked(enter, _OldState, Data) ->
    {keep_state, Data};
locked({call, From}, unlock, Data = #{code := Code, given := Code}) ->
    {next_state, unlocked, Data, {reply, From, ok}};
locked({call, From}, unlock, Data) ->
    {keep_state, Data#{attempts => maps:get(attempts, Data, 0) + 1}, {reply, From, wrong_code}}.

unlocked(enter, _OldState, Data) ->
    {keep_state, Data};
unlocked({call, From}, lock, Data) ->
    {next_state, locked, Data, {reply, From, ok}};
unlocked({call, From}, push, Data) ->
    {next_state, locked, Data, {reply, From, open}}.
```

**Java/JOTP:**
```java
// States
enum DoorState { LOCKED, UNLOCKED }

// Events
sealed interface DoorEvent permits Lock, Unlock, Push {}
record Lock() implements DoorEvent {}
record Unlock(String code) implements DoorEvent {}
record Push() implements DoorEvent {}

// Data (context)
record DoorData(String correctCode, int attempts) {}

var door = new StateMachine<>(
    DoorState.LOCKED,
    new DoorData("1234", 0),
    (state, event, data) -> switch (state) {
        case LOCKED -> switch (event) {
            case Unlock(var code) when code.equals(data.correctCode()) ->
                new Transition.Next<>(DoorState.UNLOCKED, data);
            case Unlock _ ->
                new Transition.Keep<>(new DoorData(data.correctCode(), data.attempts() + 1));
            default -> new Transition.Keep<>(data);
        };
        case UNLOCKED -> switch (event) {
            case Lock _ -> new Transition.Next<>(DoorState.LOCKED, data);
            case Push _ -> new Transition.Next<>(DoorState.LOCKED, data);
            default -> new Transition.Keep<>(data);
        };
    }
);
```

---

### 6. Exit Signals and trap_exit

**Erlang:**
```erlang
% Trap exit signals as messages
process_flag(trap_exit, true),
receive
    {'EXIT', Pid, normal} ->
        io:format("Process ~p exited normally~n", [Pid]);
    {'EXIT', Pid, Reason} ->
        io:format("Process ~p crashed: ~p~n", [Pid, Reason])
end.
```

**Java/JOTP:**
```java
// Trap exits: ExitSignal messages are delivered to mailbox
proc.trapExits(true);

// In handler: handle ExitSignal messages
(state, msg) -> {
    if (msg instanceof ExitSignal exit) {
        if (exit.reason() == null) {
            log.info("Process exited normally");
        } else {
            log.error("Process crashed: {}", exit.reason());
        }
        return state;
    }
    // ... normal message handling
    return state;
}
```

---

### 7. Error Tuples → Result<T,E>

**Erlang:**
```erlang
case pay(Amount, Currency) of
    {ok, TransactionId} ->
        confirm_payment(TransactionId);
    {error, insufficient_funds} ->
        show_funds_error();
    {error, gateway_timeout} ->
        retry_later();
    {error, Reason} ->
        log:error("Unexpected payment error: ~p", [Reason])
end.
```

**Java/JOTP:**
```java
sealed interface PaymentError permits InsufficientFunds, GatewayTimeout, UnexpectedError {}
record InsufficientFunds() implements PaymentError {}
record GatewayTimeout() implements PaymentError {}
record UnexpectedError(Throwable cause) implements PaymentError {}

Result<String, PaymentError> result = pay(amount, currency);

switch (result) {
    case Result.Success(var txId)                     -> confirmPayment(txId);
    case Result.Failure(InsufficientFunds _)          -> showFundsError();
    case Result.Failure(GatewayTimeout _)             -> retryLater();
    case Result.Failure(UnexpectedError(var cause))   -> log.error("Unexpected error", cause);
}
```

**Railway-oriented composition (equivalent of Erlang's `{ok, _} | {error, _}` chaining):**
```java
Result.of(() -> parseInput(raw))
    .flatMap(input -> validate(input))
    .flatMap(valid -> pay(valid.amount(), valid.currency()))
    .fold(
        txId -> Response.ok(txId),
        error -> Response.error(error.toString())
    );
```

---

## Migration Strategy

### Phase 1: Identify OTP Patterns

Scan existing Erlang/Elixir code for:
- `gen_server` modules → replace with `Proc<S,M>`
- `supervisor` modules → replace with `Supervisor`
- `gen_statem` modules → replace with `StateMachine<S,E,D>`
- `gen_event` modules → replace with `EventManager<E>`

### Phase 2: Parallel Running

During migration, run JOTP and Erlang processes in parallel:
1. JOTP process sends messages to Erlang process (via gRPC/REST bridge)
2. Compare results
3. Once parity confirmed, cut over to JOTP process

### Phase 3: Cut Over

Replace the Erlang call site with JOTP:
```java
// Before: call Erlang via REST
var result = httpClient.post("/erlang/counter/increment", ...);

// After: call JOTP directly
counter.tell(new Increment());
var state = counter.ask(new GetCount(), Duration.ofSeconds(1)).get();
```

---

## What JOTP Doesn't Have (Yet)

Honest gaps:

| Erlang Feature | JOTP 1.0 Status | Workaround |
|----------------|-----------------|------------|
| Hot code loading | Not supported | Blue-green deployment |
| Distributed processes | Not supported (2.0 roadmap) | Use Kafka/gRPC between JVMs |
| Selective receive | Not supported | Use separate processes per message type |
| `ets`/`dets` tables | Not supported | Use ConcurrentHashMap or external DB |
| OTP release management | Not supported | Use Docker/Kubernetes |
| Distributed supervisor | Not supported | Use separate JVMs with Kubernetes |

---

**Previous:** [Build Supervision Trees](build-supervision-trees.md) | **Next:** [Explanations](../explanations/)

**See Also:**
- [Erlang-Java Mapping](../explanations/erlang-java-mapping.md) — Detailed pattern catalog
- [OTP Equivalence](../explanations/otp-equivalence.md) — Formal equivalence proof
- [Reference: Glossary](../reference/glossary.md) — Terminology dictionary
