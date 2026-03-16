# Migrating from Erlang/OTP to JOTP

A comprehensive guide for Erlang developers moving to JOTP (Java 26 implementation of OTP primitives).

## Overview

JOTP brings Erlang/OTP's battle-tested concurrency patterns to the JVM using Java 26's virtual threads, sealed types, and pattern matching. This guide provides side-by-side comparisons and practical migration strategies.

**Key Philosophy:** JOTP maintains OTP's "Let It Crash" philosophy while leveraging Java's type system and tooling ecosystem.

---

## Concept Mapping

### Core Primitives

| Erlang/OTP | JOTP | Notes |
|------------|------|-------|
| `spawn/1,3` | `Proc.spawn()` or `new Proc<>()` | Virtual threads instead of BEAM processes |
| `Pid` | `Proc<S,M>` | Typed handles with compile-time message safety |
| `!` (send) | `proc.tell(msg)` | Fire-and-forget message passing |
| `receive...end` | Handler function with pattern matching | No selective receive; use separate processes |
| `link/1` | `ProcLink.link()` | Bilateral crash propagation |
| `monitor/2` | `ProcMonitor.monitor()` | Unilateral DOWN notifications |
| `gen_server` | `Proc<S,M>` or `GenServer` | State machine via handler function |
| `gen_statem` | `StateMachine<S,E,D>` | Full OTP feature parity |
| `gen_event` | `EventManager<E>` | Event handler pattern |
| `supervisor` | `Supervisor` | All restart strategies supported |
| `proc_lib` | `ProcLib` | Startup handshake pattern |

### Data Structures

| Erlang | JOTP | Rationale |
|--------|------|-----------|
| Tuples `{ok, Value}` | `Result<T,E>` sealed type | Type-safe with railway-oriented programming |
| Lists `[1,2,3]` | `List.of(1,2,3)` | Immutable collections |
| Maps `#{key => val}` | `record` or `Map<String,Object>` | Records for compile-time safety |
| Atoms `:ok` | `enum` or sealed records | Enums for fixed sets |
| Pattern matching | `switch` with sealed types | Compiler-enforced exhaustiveness |
| Records `-record()` | `record` classes | Same semantics, Java syntax |

---

## Process Migration

### Basic Process Spawning

**Erlang:**
```erlang
-module(counter).
-export([start/0, increment/1, get_count/1]).

start() ->
    spawn(fun() -> loop(0) end).

increment(Pid) ->
    Pid ! increment.

get_count(Pid) ->
    Pid ! {get_count, self()},
    receive
        {count, N} -> N
    end.

loop(Count) ->
    receive
        increment ->
            loop(Count + 1);
        {get_count, Pid} ->
            Pid ! {count, Count},
            loop(Count)
    end.
```

**JOTP:**
```java
// Message protocol (sealed interface for type safety)
sealed interface CounterMsg permits Increment, GetCount {}
record Increment() implements CounterMsg {}
record GetCount() implements CounterMsg {}

// State (immutable record)
record CounterState(long count) {}

// Process creation
var counter = Proc.spawn(
    new CounterState(0),
    (state, msg) -> switch (msg) {
        case Increment _ -> new CounterState(state.count() + 1);
        case GetCount _ -> state;  // ask() returns state
    }
);

// Usage
counter.tell(new Increment());  // Fire-and-forget
long count = counter.ask(new GetCount())
    .get(1, TimeUnit.SECONDS).count();
```

**Key Differences:**
1. **Typed messages:** Sealed interfaces ensure compile-time exhaustiveness
2. **No receive block:** Handler function processes messages sequentially
3. **Explicit state:** Immutable records instead of recursive function arguments
4. **ask/tell patterns:** Built-in request-reply vs fire-and-forget

### gen_server Migration

**Erlang gen_server:**
```erlang
-module(account_server).
-behaviour(gen_server).

-record(state, {balance = 0, transactions = []}).

init([]) -> {ok, #state{}}.

handle_call(get_balance, _From, State) ->
    {reply, State#state.balance, State};

handle_call({deposit, Amount}, _From, State) ->
    NewBalance = State#state.balance + Amount,
    NewState = State#state{
        balance = NewBalance,
        transactions = [{deposit, Amount} | State#state.transactions]
    },
    {reply, NewBalance, NewState};

handle_call({withdraw, Amount}, _From, State = #state{balance = Balance}) when Amount =< Balance ->
    NewBalance = Balance - Amount,
    {reply, {ok, NewBalance}, State#state{balance = NewBalance}};

handle_call({withdraw, _Amount}, _From, State) ->
    {reply, {error, insufficient_funds}, State};

handle_cast(reset, _State) ->
    {noreply, #state{}}.

handle_info(Msg, State) ->
    io:format("Unexpected message: ~p~n", [Msg]),
    {noreply, State}.

terminate(_Reason, _State) ->
    ok.
```

**JOTP (Proc with handler):**
```java
// State record
record AccountState(long balance, List<String> transactions) {}

// Message protocol
sealed interface AccountMsg
    permits GetBalance, Deposit, Withdraw, Reset {}
record GetBalance() implements AccountMsg {}
record Deposit(long amount) implements AccountMsg {}
record Withdraw(long amount) implements AccountMsg {}
record Reset() implements AccountMsg {}

// Process creation
var account = Proc.spawn(
    new AccountState(0, List.of()),
    (state, msg) -> switch (msg) {
        case GetBalance _ -> state;  // ask() returns state

        case Deposit(var amount) -> new AccountState(
            state.balance() + amount,
            prepend(state.transactions(), "deposit:" + amount)
        );

        case Withdraw(var amount) when amount <= state.balance() ->
            new AccountState(
                state.balance() - amount,
                prepend(state.transactions(), "withdraw:" + amount)
            );

        case Withdraw _ -> state;  // Insufficient funds

        case Reset _ -> new AccountState(0, List.of());
    }
);

// Add termination callback
account.addTerminationCallback(reason -> {
    System.out.println("Account terminating: " + reason);
});
```

**JOTP (GenServer subclass):**
```java
public class AccountServer extends GenServer<AccountState, AccountMsg> {

    public AccountServer() {
        super(new AccountState(0, List.of()));
    }

    @Override
    protected AccountState handleCall(AccountMsg msg, From from, AccountState state) {
        return switch (msg) {
            case GetBalance _ -> state;  // Reply is automatic via ask()

            case Deposit(var amount) -> new AccountState(
                state.balance() + amount,
                prepend(state.transactions(), "deposit:" + amount)
            );

            case Withdraw(var amount) when amount <= state.balance() ->
                new AccountState(
                    state.balance() - amount,
                    prepend(state.transactions(), "withdraw:" + amount)
                );

            case Withdraw _ -> state;  // Caller handles error case

            case Reset _ -> new AccountState(0, List.of());
        };
    }

    @Override
    protected AccountState handleCast(AccountMsg msg, AccountState state) {
        return handleCall(msg, null, state);  // Same logic
    }

    @Override
    protected void handleInfo(Object msg, AccountState state) {
        System.out.println("Unexpected message: " + msg);
    }

    @Override
    protected void terminate(Throwable reason, AccountState state) {
        System.out.println("Account terminating: " + reason);
    }
}
```

**Key Differences:**
1. **No callback modules:** Single class with pattern matching
2. **Unified handler:** `handleCall/handleCast` merged with switch
3. **Explicit state mutations:** New state objects vs record updates
4. **No separate init:** Constructor handles initialization

### gen_statem Migration

**Erlang gen_statem:**
```erlang
-module(code_lock).
-behaviour(gen_statem).

callback_mode() -> handle_event_function.

locked({call, From}, {button, Digit}, Data = #{code := Code, input := Input}) ->
    case Input ++ [Digit] of
        Code ->
            {next_state, open, Data#{input := []}, [{reply, From, ok}, {state_timeout, 10000, lock}]};
        NewInput when length(NewInput) >= length(Code) ->
            {keep_state, Data#{input := []}, [{reply, From, {error, wrong_code}}]};
        NewInput ->
            {keep_state, Data#{input := NewInput}, [{reply, From, {error, incomplete}}]}
    end;
locked(state_timeout, lock, Data) ->
    {keep_state, Data#{input := []}}.

open({call, From}, lock, Data) ->
    {next_state, locked, Data, [{reply, From, ok}]};
open(state_timeout, lock, Data) ->
    {next_state, locked, Data}.
```

**JOTP StateMachine:**
```java
// States
sealed interface LockState permits Locked, Open {}
record Locked() implements LockState {}
record Open() implements LockState {}

// Events
sealed interface LockEvent permits Button, Lock {}
record Button(char digit) implements LockEvent {}
record Lock() implements LockEvent {}

// Data
record LockData(String correctCode, String input) {}

// State machine creation
var lockMachine = StateMachine.of(
    new Locked(),
    new LockData("1234", ""),
    (state, event, data) -> switch (state) {
        case Locked _ -> switch (event) {
            case SMEvent.User(Button(var digit)) -> {
                var newInput = data.input() + digit;
                if (newInput.equals(data.correctCode())) {
                    yield Transition.nextState(
                        new Open(),
                        new LockData(data.correctCode(), ""),
                        Action.stateTimeout(10_000, new Lock())
                    );
                } else if (newInput.length() >= data.correctCode().length()) {
                    yield Transition.keepState(
                        new LockData(data.correctCode(), "")
                    );
                } else {
                    yield Transition.keepState(
                        new LockData(data.correctCode(), newInput)
                    );
                }
            }
            case SMEvent.StateTimeout(var content) when content instanceof Lock ->
                yield Transition.keepState(new LockData(data.correctCode(), ""));
            default -> Transition.keepState(data);
        };

        case Open _ -> switch (event) {
            case SMEvent.User(Lock _), SMEvent.StateTimeout(var _) ->
                yield Transition.nextState(new Locked(), data);
            default ->
                yield Transition.keepState(data, Action.postpone());
            };
        };
    }
);
```

**Key Differences:**
1. **Explicit state/data separation:** State and data are distinct types
2. **Nested pattern matching:** Switch on state, then event
3. **Action objects:** Explicit timeout/postpone actions
4. **Type-safe transitions:** Compiler verifies all states handled

---

## Supervisor Migration

### Basic Supervisor

**Erlang:**
```erlang
-module(account_sup).
-behaviour(supervisor).

init([]) ->
    ChildSpecs = [
        #{
            id => account,
            start => {account_server, start_link, []},
            restart => permanent,
            shutdown => 5000,
            type => worker,
            modules => [account_server]
        }
    ],
    {ok, {#{strategy => one_for_one, intensity => 5, period => 60}, ChildSpecs}}.
```

**JOTP:**
```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

var accountRef = supervisor.supervise(
    "account",
    new AccountState(0, List.of()),
    accountHandler
);
```

### Dynamic Child Specification

**Erlang:**
```erlang
%% With restart intensity
ChildSpec = #{
    id => worker1,
    start => {my_worker, start_link, [Arg]},
    restart => permanent,
    shutdown => brutal_kill,
    type => worker,
    significant => true
},
supervisor:start_child(SupPid, ChildSpec).
```

**JOTP:**
```java
var spec = new Supervisor.ChildSpec<>(
    "worker1",
    () -> new WorkerState(arg),
    workerHandler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.BrutalKill(),
    Supervisor.ChildSpec.ChildType.WORKER,
    true  // significant
);

var workerRef = supervisor.startChild(spec);
```

### Restart Strategies

**Erlang:**
```erlang
{ok, {#{strategy => one_for_one, intensity => 5, period => 60}, []}}
{ok, {#{strategy => one_for_all, intensity => 3, period => 30}, []}}
{ok, {#{strategy => rest_for_one, intensity => 10, period => 100}, []}}
{ok, {#{strategy => simple_one_for_one, intensity => 2, period => 10}, []}}
```

**JOTP:**
```java
Supervisor.create(ONE_FOR_ONE, 5, Duration.ofSeconds(60))
Supervisor.create(ONE_FOR_ALL, 3, Duration.ofSeconds(30))
Supervisor.create(REST_FOR_ONE, 10, Duration.ofSeconds(100))

// Simple one-for-one (dynamic pool)
var template = Supervisor.ChildSpec.worker(
    "conn",
    ConnState::new,
    connHandler
);
var pool = Supervisor.createSimple(template, 2, Duration.ofSeconds(10));
var conn1 = pool.startChild();  // Creates "conn-1"
var conn2 =.startChild();       // Creates "conn-2"
```

---

## Pattern Matching Translation

### Message Dispatch

**Erlang:**
```erlang
receive
    {ok, Value} ->
        process_success(Value);
    {error, Reason} when Reason =:= timeout ->
        handle_timeout();
    {error, Reason} ->
        handle_error(Reason);
    Unexpected ->
        log("Unexpected: ~p", [Unexpected])
end.
```

**JOTP:**
```java
sealed interface Response permits Ok, Error {}
record Ok(int value) implements Response {}
sealed interface Error extends Error {}
record Timeout() implements Error {}
record OtherError(String reason) implements Error {}

// In process handler
(state, msg) -> switch (msg) {
    case Ok(var value) -> processSuccess(value);
    case Timeout _ -> handleTimeout();
    case OtherError(var reason) -> handleError(reason);
};
// Compiler verifies ALL cases handled - no "Unexpected" needed
```

### Guards and Pattern Matching

**Erlang:**
```erlang
handle_call({transfer, To, Amount}, _From, State = #state{balance = Bal})
  when Amount > 0, Amount =< Bal ->
    NewBal = Bal - Amount,
    To ! {deposit, Amount},
    {reply, {ok, NewBal}, State#state{balance = NewBal}}.
```

**JOTP:**
```java
case Transfer(var to, var amount)
    when amount > 0 && amount <= state.balance() -> {
    var newBal = state.balance() - amount;
    to.tell(new Deposit(amount));
    yield new AccountState(newBal, state.transactions());
}
```

### Nested Pattern Matching

**Erlang:**
```erlang
case Result of
    {ok, {User, #{wallet := #{balance := Bal}}}} when Bal > 0 ->
        charge(User, Bal);
    {ok, {User, #{wallet := #{balance := 0}}}} ->
        decline(User);
    {error, not_found} ->
        create_account()
end.
```

**JOTP:**
```java
record Wallet(long balance) {}
record UserData(String id, Wallet wallet) {}

switch (result) {
    case Success(UserData(var id, Wallet(var bal))) when bal > 0 ->
        charge(id, bal);
    case Success(UserData(var id, Wallet(var bal))) when bal == 0 ->
        decline(id);
    case Failure(var reason) when "not_found".equals(reason) ->
        createAccount();
}
```

---

## Error Handling and Let It Crash

### try...catch vs Exception Propagation

**Erlang:**
```erlang
handle_call(risky_operation, _From, State) ->
    try
        Result = risky:operation(),
        {reply, {ok, Result}, State}
    catch
        Error:Reason ->
            {reply, {error, Reason}, State}
    end.
```

**JOTP:**
```java
// In JOTP, exceptions crash the process by default (Let It Crash)
// Supervisor restarts with fresh state
case RiskyOperation _ -> {
    try {
        var result = riskyOperation();
        yield state;  // ask() returns result
    } catch (Exception e) {
        throw e;  // Process crashes, supervisor restarts
    }
}
```

### Exit Signals

**Erlang:**
```erlang
%% Trapping exits
process_flag(trap_exit, true).

%% Handle EXIT signal
handle_info({'EXIT', Pid, Reason}, State) ->
    io:format("Process ~p died: ~p~n", [Pid, Reason]),
    {noreply, State}.
```

**JOTP:**
```java
// Enable exit trapping
proc.trapExits(true);

// Handle ExitSignal in message loop
(state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> {
        System.out.println("Linked process died: " + reason);
        yield state;
    }
    // ... other message cases
};
```

---

## Process Registry

**Erlang:**
```erlang
%% Register
global:register_name(my_counter, Pid).

%% Lookup
case global:whereis_name(my_counter) of
    undefined -> not_found;
    Pid -> {ok, Pid}
end.

%% All registered names
global:registered_names().
```

**JOTP:**
```java
// Register
ProcRegistry.register("my-counter", proc);

// Lookup
var proc = ProcRegistry.whereis("my-counter");
if (proc == null) {
    // Not found
}

// All registered names
Set<String> names = ProcRegistry.registered();
```

---

## Timers

**Erlang:**
```erlang
%% Send after delay
timer:send_after(5000, self(), timeout).

%% Send interval
timer:send_interval(1000, self(), heartbeat).

%% Cancel timer
{ok, TRef} = timer:send_interval(1000, self(), heartbeat),
timer:cancel(TRef).
```

**JOTP:**
```java
// Send after delay
ProcTimer.sendAfter(Duration.ofSeconds(5), proc, new Timeout());

// Send interval
var timerRef = ProcTimer.sendInterval(Duration.ofSeconds(1), proc, new Heartbeat());

// Cancel timer
timerRef.cancel();
```

---

## Migration Checklist

### Phase 1: Core Concepts (Week 1-2)
- [ ] Understand virtual threads vs BEAM processes
- [ ] Learn sealed interfaces for message protocols
- [ ] Master pattern matching with switch expressions
- [ ] Practice immutable state with records

### Phase 2: Process Patterns (Week 3-4)
- [ ] Convert basic spawn loops to Proc<S,M>
- [ ] Migrate gen_server callbacks to handler functions
- [ ] Implement ask/tell patterns
- [ ] Set up process monitoring

### Phase 3: Supervision Trees (Week 5-6)
- [ ] Design supervisor hierarchy
- [ ] Define restart strategies
- [ ] Implement ChildSpec configurations
- [ ] Test crash recovery

### Phase 4: State Machines (Week 7-8)
- [ ] Identify gen_statem use cases
- [ ] Design state/event/data types
- [ ] Implement transition functions
- [ ] Add timeout and postpone actions

### Phase 5: Advanced Features (Week 9-10)
- [ ] Implement process registry
- [ ] Set up event managers
- [ ] Add distributed communication
- [ ] Implement hot code upgrade alternatives

---

## Common Gotchas

### 1. No Selective Receive

**Problem:** Erlang's selective receive allows filtering messages from mailbox.

**Solution:** Use separate processes per message type or priority queues.

```java
// Instead of selective receive
// receive
//     {urgent, Msg} -> handle_urgent(Msg)
// after 0 ->
//     receive
//         {normal, Msg} -> handle_normal(Msg)
//     end
// end

// Use separate processes
var urgentProc = Proc.spawn(initialState, urgentHandler);
var normalProc = Proc.spawn(initialState, normalHandler);
```

### 2. No Hot Code Loading

**Problem:** Erlang can load new code at runtime.

**Solution:** Use blue-green deployment or rolling updates.

```java
// Not available in JOTP
// code:load_file(my_module).

// Use deployment orchestration instead
// Kubernetes rolling update
// Load balancer shift
```

### 3. Mutable State Pitfalls

**Problem:** Sharing mutable state breaks isolation.

**Solution:** Always use immutable records.

```java
// WRONG - mutable list
record BadState(List<String> items) {}  // List can be modified

// CORRECT - immutable
record GoodState(List<String> items) {
    public GoodState {
        items = List.copyOf(items);  // Defensive copy
    }
}
```

### 4. Exception Handling

**Problem:** Overusing try-catch defeats "Let It Crash".

**Solution:** Let supervisors handle failures.

```java
// WRONG - catching everything
case ProcessMsg _ -> {
    try {
        riskyOperation();
    } catch (Exception e) {
        // Swallowing exceptions - defeats supervision
    }
}

// CORRECT - let it crash
case ProcessMsg _ -> riskyOperation();  // Supervisor will restart
```

### 5. Blocking Operations

**Problem:** Blocking in virtual threads can cascade.

**Solution:** Use async APIs or dedicated blocking pool.

```java
// WRONG - blocks virtual thread
case ReadFile _ -> {
    var content = Files.readString(path);  // Blocking
    yield state;
}

// CORRECT - async or offload
case ReadFile _ -> {
    var future = CompletableFuture.supplyAsync(
        () -> Files.readString(path),
        blockingExecutor
    );
    // Handle async result
    yield state;
}
```

---

## Best Practices for Migration

### 1. Start with Greenfield Projects
Migrate non-critical services first to learn patterns without pressure.

### 2. Use Sealed Types Extensively
Leverage Java's type system to catch errors at compile time.

```java
sealed interface Result<T,E> permits Success, Failure {}
record Success<T>(T value) implements Result<T,Object> {}
record Failure<E>(E error) implements Result<Object,E> {}
```

### 3. Embrace Immutability
All state should be immutable records or value types.

```java
public record UserState(
    String id,
    String name,
    Instant createdAt,
    List<String> roles  // Immutable list
) {}
```

### 4. Design Message Protocols Carefully
Use sealed interfaces to ensure exhaustive pattern matching.

```java
sealed interface UserCommand
    permits Create, Update, Delete, Query {}
record Create(String id, String name) implements UserCommand {}
record Update(String id, String name) implements UserCommand {}
record Delete(String id) implements UserCommand {}
record Query(String id) implements UserCommand {}
```

### 5. Test Supervision Trees
Verify crash recovery works as expected.

```java
@Test
void testSupervisorRestartsCrashedChild() {
    var sup = Supervisor.create(ONE_FOR_ONE, 5, Duration.ofSeconds(60));
    var child = sup.supervise("test", state, handler);

    // Simulate crash
    child.proc().thread().interrupt();

    // Wait for restart
    Thread.sleep(200);

    // Verify new process
    assertTrue(child.proc().isRunning());
    assertNotSame(originalThread, child.proc().thread());
}
```

### 6. Monitor Process Health
Use ProcSys for introspection without killing processes.

```java
var stats = ProcSys.of(proc).statistics();
System.out.println("Messages in: " + stats.messagesIn());
System.out.println("Messages out: " + stats.messagesOut());
System.out.println("Queue depth: " + stats.queueDepth());
```

---

## Performance Considerations

### Virtual Thread Advantages
- **Scalability:** Millions of processes vs thousands of platform threads
- **Memory:** ~1 KB heap per process vs ~1 MB per thread
- **Latency:** Sub-millisecond context switching

### Virtual Thread Limitations
- **CPU-bound work:** No parallelism improvement
- **Blocking I/O:** Still blocks, but cheap
- **Native code:** Pinning can occur

### When to Use Platform Threads
- Heavy CPU computation
- JNI calls with pinning
- Legacy integration points

---

## Tooling and Ecosystem

### Build Tools
- **Maven:** Standard Java build tool
- **Gradle:** Alternative with Groovy/Kotlin DSL

### Testing
- **JUnit 5:** Unit testing framework
- **AssertJ:** Fluent assertions
- **Testcontainers:** Integration testing with Docker

### Observability
- **OpenTelemetry:** Distributed tracing
- **Micrometer:** Metrics collection
- **Logback/Log4j:** Structured logging

### Deployment
- **Docker:** Container packaging
- **Kubernetes:** Orchestration
- **Helidon/Quarkus:** Native compilation

---

## Further Reading

- [JOTP Architecture Overview](../explanations/architecture-overview.md)
- [Erlang-Java Mapping](../explanations/erlang-java-mapping.md)
- [OTP Equivalence Proof](../explanations/otp-equivalence.md)
- [Let It Crash Philosophy](../explanations/let-it-crash-philosophy.md)
- [How-To: Build Supervision Trees](../how-to/build-supervision-trees.md)

---

**Conclusion:** JOTP provides a faithful implementation of OTP primitives on Java 26. The migration requires mindset shifts from dynamic to static typing, but the core patterns remain the same. Embrace the type system, leverage immutability, and let supervisors handle failures.
