# Programming Examples

Practical Java 26 patterns used throughout JOTP. These examples show idiomatic Java 26 techniques that replace classic Erlang idioms.

---

## Sealed Records as Message Types

Erlang uses atoms and tuples for messages. Java 26 uses **sealed interfaces** with **records** — giving compile-time exhaustiveness checking that Erlang cannot provide.

**Erlang:**
```erlang
handle_message({increment}, State) -> State + 1;
handle_message({get, From}, State) -> From ! State, State;
handle_message({reset}, _State)    -> 0.
```

**Java 26 equivalent:**
```java
sealed interface CounterMsg permits Inc, Get, Reset {}
record Inc()                                     implements CounterMsg {}
record Get(ProcRef<Integer, ?> replyTo)          implements CounterMsg {}
record Reset()                                   implements CounterMsg {}

var handler = (Integer state) -> (CounterMsg msg) -> switch (msg) {
    case Inc()            -> state + 1;
    case Get(var replyTo) -> { replyTo.send(state); yield state; }
    case Reset()          -> 0;
};
// Compiler error if a case is missing — exhaustiveness enforced statically
```

### Nested Record Patterns

Java 26 record patterns support destructuring at any depth:

```java
record Point(double x, double y) {}
record Circle(Point center, double radius) {}
record Rectangle(Point topLeft, Point bottomRight) {}

sealed interface Shape permits Circle, Rectangle {}

double area = switch (shape) {
    case Circle(Point(var x, var y), var r)                         -> Math.PI * r * r;
    case Rectangle(Point(var x1, var y1), Point(var x2, var y2))   -> Math.abs(x2-x1) * Math.abs(y2-y1);
};
```

---

## List Processing with Stream Gatherers

Erlang list comprehensions become Java streams with **gatherers** (Java 22+, final in Java 26).

**Erlang list comprehension:**
```erlang
Evens = [X * 2 || X <- List, X rem 2 =:= 0].
```

**Java 26 equivalent:**
```java
var evens = list.stream()
    .filter(x -> x % 2 == 0)
    .map(x -> x * 2)
    .toList();
```

**Sliding window (no Erlang equivalent — needs manual recursion):**
```java
import java.util.stream.Gatherers;

var windows = IntStream.rangeClosed(1, 10)
    .boxed()
    .gather(Gatherers.windowSliding(3))
    .toList();
// [[1,2,3], [2,3,4], [3,4,5], ...]
```

**Parallel stream processing:**
```java
var results = items.parallelStream()
    .map(item -> processItem(item))
    .toList();
```

For fault-tolerant parallel processing, prefer `Parallel` over parallel streams — it provides supervision semantics:

```java
// Parallel with fail-fast and timeout
var results = Parallel.map(items, item -> processItem(item), Duration.ofSeconds(10));
```

---

## Concurrent Pipelines with `Parallel`

`Parallel` implements OTP's `pmap` — structured fan-out with fail-fast semantics.

### Fan-Out / Fan-In Pattern

```java
public record SearchResult(String source, List<Item> items) {}

var sources = List.of("database", "cache", "external-api");

// All searches run concurrently; if any fails, all are cancelled
List<SearchResult> results = Parallel.map(
    sources,
    source -> new SearchResult(source, search(source, query))
);
```

### Pipeline Stage Pattern

```java
// Stage 1: fetch
var rawData = Parallel.map(ids, id -> fetchItem(id));

// Stage 2: transform
var transformed = rawData.stream().map(Transformer::apply).toList();

// Stage 3: persist
var saved = Parallel.map(transformed, item -> repository.save(item));
```

### Timeout-Bounded Fan-Out

```java
try {
    var results = Parallel.map(
        requests,
        req -> callExternalService(req),
        Duration.ofSeconds(5)  // Hard deadline
    );
} catch (TimeoutException e) {
    // All in-progress tasks cancelled automatically
    return fallbackResults();
}
```

---

## Error Handling with `Result<T,E>`

Replace checked exceptions at system boundaries with the `Result<T,E>` railway pattern.

### Wrapping Throwing Operations

```java
// Instead of try-catch everywhere:
Result<User, String> user = Result.of(() -> userRepo.findById(id));

// Pattern-matched handling
return switch (user) {
    case Success(var u) -> Response.ok(u.toJson());
    case Failure(var e) -> Response.notFound(e);
};
```

### Railway Chaining

```java
Result<OrderConfirmation, String> result = Result.of(() -> parseRequest(body))
    .map(req -> validateOrder(req))
    .flatMap(order -> checkInventory(order))
    .flatMap(order -> chargePayment(order))
    .map(payment -> confirmOrder(payment));
```

### Recovery

```java
Result<Config, String> config = Result.of(() -> loadFromFile("config.json"))
    .recover(err -> loadFromEnvironment())  // fallback on failure
    .recover(err -> Config.defaults());     // second fallback
```

### In Process Handlers

```java
var proc = Proc.start(
    state -> msg -> {
        var result = Result.of(() -> externalService.call(msg));
        return switch (result) {
            case Success(var data) -> state.withData(data);
            case Failure(var err)  -> {
                log.warn("External call failed: {}", err);
                yield state;  // Keep current state, discard message
            }
        };
    },
    initialState
);
```

---

## Pattern Matching on Complex Types

Java 26 pattern matching works at every level of nesting.

### Guarded Patterns

```java
Object value = getValue();
String description = switch (value) {
    case Integer i when i < 0    -> "negative integer: " + i;
    case Integer i when i == 0   -> "zero";
    case Integer i               -> "positive integer: " + i;
    case String s when s.isEmpty() -> "empty string";
    case String s                -> "string: " + s;
    case null                    -> "null";
    default                      -> "other: " + value.getClass().getSimpleName();
};
```

### Destructuring in Handlers

```java
sealed interface ApiRequest permits GetRequest, PostRequest, DeleteRequest {}
record GetRequest(String path, Map<String, String> params) implements ApiRequest {}
record PostRequest(String path, String body, String contentType) implements ApiRequest {}
record DeleteRequest(String path, String resourceId) implements ApiRequest {}

var response = switch (request) {
    case GetRequest(var path, var params) when path.startsWith("/api/v2") ->
        handleV2Get(path, params);
    case GetRequest(var path, var params)  ->
        handleLegacyGet(path, params);
    case PostRequest(var path, var body, "application/json") ->
        handleJsonPost(path, body);
    case PostRequest(var path, var body, var ct) ->
        Response.unsupportedMediaType(ct);
    case DeleteRequest(var path, var id)   ->
        handleDelete(path, id);
};
```

---

## Bit Manipulation and Binary Data

Java 26 provides structured access to binary data via `MemorySegment` (Panama API, JEP 454).

```java
import java.lang.foreign.*;
import java.nio.ByteOrder;

// Parse a binary protocol frame
try (var arena = Arena.ofConfined()) {
    var segment = arena.allocate(16);
    segment.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0xDEADBEEF);  // magic
    segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) 42); // version
    segment.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, System.nanoTime()); // timestamp

    int magic   = segment.get(ValueLayout.JAVA_INT_UNALIGNED, 0);
    short version = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4);
    long ts     = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 8);
}
```

For simple byte manipulation, Java's `ByteBuffer` is idiomatic:

```java
var buffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
buffer.putInt(0xCAFEBABE);  // magic
buffer.putShort((short) 1); // version
buffer.put(payload);
buffer.flip();

// Parse
int magic   = buffer.getInt();
short ver   = buffer.getShort();
byte[] data = new byte[buffer.remaining()];
buffer.get(data);
```

---

## Complete Working Example: Bank Account

A complete, supervised bank account system demonstrating message types, request-reply, and supervisor restart:

```java
import io.github.seanchatmangpt.jotp.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

public class BankExample {

    sealed interface AccountMsg permits Deposit, Withdraw, GetBalance {}
    record Deposit(BigDecimal amount) implements AccountMsg {}
    record Withdraw(BigDecimal amount, ProcRef<Result<BigDecimal, String>, ?> replyTo) implements AccountMsg {}
    record GetBalance(ProcRef<BigDecimal, ?> replyTo) implements AccountMsg {}

    record AccountState(String id, BigDecimal balance, List<String> log) {
        AccountState debit(BigDecimal amount) {
            var newLog = new ArrayList<>(log);
            newLog.add("debit " + amount);
            return new AccountState(id, balance.subtract(amount), newLog);
        }
        AccountState credit(BigDecimal amount) {
            var newLog = new ArrayList<>(log);
            newLog.add("credit " + amount);
            return new AccountState(id, balance.add(amount), newLog);
        }
    }

    public static void main(String[] args) throws Exception {
        var supervisor = Supervisor.oneForOne()
            .restartWindow(Duration.ofMinutes(1), 5)
            .add("account-001", () -> Proc.start(
                (AccountState state) -> (AccountMsg msg) -> switch (msg) {
                    case Deposit(var amount)             -> state.credit(amount);
                    case Withdraw(var amount, var reply) -> {
                        if (state.balance().compareTo(amount) < 0) {
                            reply.send(Result.failure("Insufficient funds"));
                            yield state;
                        }
                        reply.send(Result.success(amount));
                        yield state.debit(amount);
                    }
                    case GetBalance(var replyTo) -> { replyTo.send(state.balance()); yield state; }
                },
                new AccountState("001", BigDecimal.valueOf(1000), new ArrayList<>())
            ))
            .build();

        var account = supervisor.<AccountState, AccountMsg>getRef("account-001");

        account.send(new Deposit(BigDecimal.valueOf(500)));

        var result = account.ask(
            replyTo -> new Withdraw(BigDecimal.valueOf(200), replyTo),
            Duration.ofSeconds(1)
        );

        System.out.println("Withdraw: " + result);  // Withdraw: Success[200]

        var balance = account.ask(
            replyTo -> new GetBalance(replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Balance: " + balance);  // Balance: 1300
    }
}
```

---

*Next: [Reference Manual](reference-manual.md)*
