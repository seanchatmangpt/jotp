# Interoperability Guide

How to integrate JOTP with the Java ecosystem: Spring Boot, JDBC, REST APIs, messaging systems, and migration paths from Erlang, Elixir, and Akka.

---

## Calling JOTP from Spring Boot

JOTP runs alongside Spring Boot — the supervisor tree lives in a Spring `ApplicationContext` as a managed bean.

### Register the Root Supervisor as a Spring Bean

```java
@Configuration
public class JotpConfig {

    @Bean(destroyMethod = "shutdown")
    public Supervisor rootSupervisor() {
        return Supervisor.oneForOne()
            .restartWindow(Duration.ofMinutes(1), 10)
            .add("order-service",    OrderServiceProc::start)
            .add("inventory-service", InventoryServiceProc::start)
            .add("notification-service", NotificationServiceProc::start)
            .build();
    }

    @Bean
    public ProcRef<OrderState, OrderMsg> orderServiceRef(Supervisor rootSupervisor) {
        return rootSupervisor.getRef("order-service");
    }
}
```

### Inject into Controllers

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final ProcRef<OrderState, OrderMsg> orderService;

    public OrderController(ProcRef<OrderState, OrderMsg> orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        try {
            var confirmation = orderService.ask(
                replyTo -> new PlaceOrder(request.item(), request.qty(), replyTo),
                Duration.ofSeconds(5)
            );
            return ResponseEntity.ok(new OrderResponse(confirmation.orderId()));
        } catch (TimeoutException e) {
            return ResponseEntity.status(503).build();
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<String> getStatus(@PathVariable String id) {
        var status = orderService.ask(
            replyTo -> new QueryStatus(id, replyTo),
            Duration.ofSeconds(2)
        );
        return ResponseEntity.ok(status);
    }
}
```

### Graceful Shutdown with Spring Lifecycle

```java
@Component
public class JotpShutdownHook implements DisposableBean {
    private final Supervisor rootSupervisor;

    public JotpShutdownHook(Supervisor rootSupervisor) {
        this.rootSupervisor = rootSupervisor;
    }

    @Override
    public void destroy() {
        rootSupervisor.shutdown(Duration.ofSeconds(30));
    }
}
```

---

## JOTP + JDBC

Database-backed processes encapsulate connection lifecycle and state:

```java
public class UserRepositoryProc {

    sealed interface Msg permits FindUser, SaveUser {}
    record FindUser(long id, ProcRef<Optional<User>, ?> replyTo) implements Msg {}
    record SaveUser(User user, ProcRef<Result<User, String>, ?> replyTo) implements Msg {}

    record State(DataSource dataSource) {}

    public static Proc<State, Msg> start(DataSource dataSource) {
        return Proc.start(
            (State state) -> (Msg msg) -> switch (msg) {
                case FindUser(var id, var replyTo) -> {
                    var user = Result.of(() -> {
                        try (var conn = state.dataSource().getConnection();
                             var stmt = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
                            stmt.setLong(1, id);
                            var rs = stmt.executeQuery();
                            return rs.next() ? Optional.of(mapRow(rs)) : Optional.<User>empty();
                        }
                    });
                    replyTo.send(user.orElseThrow());
                    yield state;
                }
                case SaveUser(var user, var replyTo) -> {
                    var result = Result.of(() -> {
                        try (var conn = state.dataSource().getConnection();
                             var stmt = conn.prepareStatement(
                                 "INSERT INTO users (name, email) VALUES (?, ?) RETURNING *")) {
                            stmt.setString(1, user.name());
                            stmt.setString(2, user.email());
                            var rs = stmt.executeQuery();
                            rs.next();
                            return mapRow(rs);
                        }
                    });
                    replyTo.send(result);
                    yield state;
                }
            },
            new State(dataSource)
        );
    }
}
```

**Key insight:** The database connection is owned by the process. If the process crashes (e.g., connection drops), the supervisor restarts it with a fresh connection. No connection pool state is leaked.

---

## JOTP + REST APIs (HTTP-Driven Process Lifecycle)

Use Java's built-in `HttpClient` (Java 11+) inside process handlers:

```java
public class ExternalApiProc {

    sealed interface Msg permits FetchData, PostData {}
    record FetchData(String endpoint, ProcRef<Result<String, String>, ?> replyTo) implements Msg {}
    record PostData(String endpoint, String body, ProcRef<Result<Integer, String>, ?> replyTo) implements Msg {}

    record State(HttpClient client, String baseUrl) {}

    public static Proc<State, Msg> start(String baseUrl) {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        return Proc.start(
            (State state) -> (Msg msg) -> switch (msg) {
                case FetchData(var endpoint, var replyTo) -> {
                    var result = Result.of(() -> {
                        var request = HttpRequest.newBuilder()
                            .uri(URI.create(state.baseUrl() + endpoint))
                            .GET().build();
                        var response = state.client().send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200)
                            throw new RuntimeException("HTTP " + response.statusCode());
                        return response.body();
                    });
                    replyTo.send(result);
                    yield state;
                }
                case PostData(var endpoint, var body, var replyTo) -> {
                    var result = Result.of(() -> {
                        var request = HttpRequest.newBuilder()
                            .uri(URI.create(state.baseUrl() + endpoint))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .header("Content-Type", "application/json")
                            .build();
                        var response = state.client().send(request, HttpResponse.BodyHandlers.discarding());
                        return response.statusCode();
                    });
                    replyTo.send(result);
                    yield state;
                }
            },
            new State(client, baseUrl)
        );
    }
}
```

---

## Integrating with Existing Thread Pools

When calling JOTP from non-virtual-thread contexts (e.g., existing `ExecutorService`-based code), use `ask()` with a timeout. It blocks the calling thread but is safe from any context:

```java
// From any thread type (platform, virtual, pool worker)
ExecutorService legacyPool = Executors.newFixedThreadPool(10);

legacyPool.submit(() -> {
    // This blocks the pool thread until JOTP responds
    var result = jotpProc.ask(
        replyTo -> new WorkRequest(data, replyTo),
        Duration.ofSeconds(10)
    );
    return result;
});
```

For fire-and-forget, `send()` is always non-blocking and safe from any context.

---

## Migrating from Erlang/Elixir

### Direct Primitive Mapping

| Erlang/Elixir | JOTP |
|---------------|------|
| `spawn(fun)` | `Proc.start(handler, state)` |
| `Pid ! msg` | `proc.send(msg)` |
| `gen_server:call(Pid, msg)` | `proc.ask(r -> msg, timeout)` |
| `supervisor:start_link({one_for_one, ...})` | `Supervisor.oneForOne().build()` |
| `gen_statem` | `StateMachine.create(handler, state, data)` |
| `gen_event:notify` | `eventManager.notify(event)` |
| `link(Pid)` | `ProcessLink.link(a, b)` |
| `erlang:monitor(process, Pid)` | `ProcessMonitor.monitor(proc)` |
| `global:register_name` | `ProcessRegistry.register(name, ref)` |
| `timer:send_after(Ms, Pid, Msg)` | `ProcTimer.sendAfter(duration, proc, msg)` |
| `sys:get_state(Pid)` | `ProcSys.getState(proc)` |
| `proc_lib:start_link` | `ProcLib.startLink(parent, init)` |

### Pattern Matching Translation

**Erlang:**
```erlang
handle_call({place_order, Item, Qty}, _From, State) ->
    NewState = add_order(State, Item, Qty),
    {reply, ok, NewState};
handle_call(get_orders, _From, State) ->
    {reply, State#state.orders, State}.
```

**JOTP:**
```java
sealed interface OrderMsg permits PlaceOrder, GetOrders {}
record PlaceOrder(String item, int qty) implements OrderMsg {}
record GetOrders(ProcRef<List<Order>, ?> replyTo) implements OrderMsg {}

state -> msg -> switch (msg) {
    case PlaceOrder(var item, var qty) -> state.addOrder(item, qty);
    case GetOrders(var replyTo)        -> { replyTo.send(state.orders()); yield state; }
}
```

---

## Migrating from Akka

| Akka | JOTP | Notes |
|------|------|-------|
| `Actor` | `Proc<S,M>` | JOTP uses functional style; no `AbstractBehavior` subclass |
| `ActorRef<T>` | `ProcRef<S,M>` | Stable, typed handle |
| `ActorSystem` | Root `Supervisor` | |
| `SupervisorStrategy` | Restart strategy on `Supervisor` | |
| `Behaviors.receive` | `state -> msg -> switch(msg) {...}` | Pure function |
| `ask` pattern | `proc.ask(r -> msg, timeout)` | Direct, no `AskPattern.ask()` boilerplate |
| `Routers.pool` | `Parallel.map()` or a supervisor pool | |
| `EventBus` | `EventManager<E>` | |
| Akka Streams | `Parallel` + process pipelines | |

**Key difference:** Akka uses object-oriented `Behavior<T>` classes. JOTP uses pure functions — no `AbstractBehavior` to extend, no `Behaviors.setup()`, no `ActorContext`.

### Before (Akka):
```java
public class CounterBehavior extends AbstractBehavior<CounterCommand> {
    private int count = 0;

    public static Behavior<CounterCommand> create() {
        return Behaviors.setup(CounterBehavior::new);
    }

    @Override
    public Receive<CounterCommand> createReceive() {
        return newReceiveBuilder()
            .onMessage(Increment.class, msg -> { count++; return this; })
            .onMessage(GetCount.class, msg -> { msg.replyTo().tell(count); return this; })
            .build();
    }
}
ActorRef<CounterCommand> counter = system.spawn(CounterBehavior.create(), "counter");
```

### After (JOTP):
```java
sealed interface CounterMsg permits Increment, GetCount {}
record Increment() implements CounterMsg {}
record GetCount(ProcRef<Integer, ?> replyTo) implements CounterMsg {}

var counter = Proc.start(
    (Integer count) -> (CounterMsg msg) -> switch (msg) {
        case Increment()         -> count + 1;
        case GetCount(var reply) -> { reply.send(count); yield count; }
    },
    0
);
```

---

## Migrating from Go

Go goroutines map directly to JOTP virtual threads, but JOTP adds supervision:

| Go | JOTP |
|----|------|
| `go func() { ... }()` | `Proc.start(handler, state)` |
| `chan<- T` | Process mailbox (via `send()`) |
| `<-chan` | `proc.ask(...)` |
| `select {}` | Pattern matching in handler |
| `sync.WaitGroup` | `Parallel.map(...)` |
| Manual goroutine restart | `Supervisor` |
| No supervision | `Supervisor` + restart strategies |

The key addition JOTP brings over Go: **automatic restart on crash** and **supervision trees**. Go goroutines that panic must be manually recovered — JOTP supervisors handle this transparently.

---

*Next: [Deployment Guide](deployment-guide.md)*
