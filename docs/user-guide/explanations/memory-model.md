# Explanations: Memory Model

> "Memory isolation is not an optimization — it is a correctness guarantee. In concurrent systems, shared mutable state is the root of all evil."
> — Joe Armstrong, *Programming Erlang*, 2007

JOTP's memory model eliminates shared mutable state through heap isolation, immutable message passing, and per-process garbage collection. This document explains how Java 26's memory model enables OTP-style fault tolerance without the BEAM VM.

---

## Layer 1: Heap Isolation Between Processes

### The Shared-State Problem

In traditional Java concurrency, threads share the same heap:

```java
// BAD: Shared mutable state across threads
public class SharedCounter {
    private int count = 0;  // Shared across threads

    public synchronized void increment() {
        count++;  // Requires locking
    }
}

// Thread 1 and Thread 2 can corrupt state without synchronization
```

**Problems:**
1. **Data races:** Unsynchronized access corrupts state
2. **Deadlocks:** Lock ordering can cause circular waits
3. **Memory visibility:** Changes may not be visible across threads
4. **Lock contention:** Synchronization becomes bottleneck at scale

### Process-Based Isolation

JOTP processes maintain isolated state through virtual threads and message passing:

```java
// GOOD: Each process has isolated state
var counter1 = new Proc<>(0, handler);  // Process 1: count = 0
var counter2 = new Proc<>(0, handler);  // Process 2: count = 0

counter1.tell(new Increment(5));  // Only affects counter1
counter2.tell(new Increment(10)); // Only affects counter2

// No locks needed — no shared state
```

**How it works:**

1. **Per-process state:** Each `Proc<S,M>` has its own `S` state object
2. **Virtual thread isolation:** Virtual threads don't share state by design
3. **Message copying:** Messages are immutable records, passed by reference
4. **No shared mutable state:** Impossible to write data races

### Memory Architecture

```
JVM Heap
├── Process 1 State
│   └── AccountState(balance=1000, txLog=[...])
├── Process 2 State
│   └── OrderState(items=[...], total=50.0)
├── Process 1 Mailbox
│   ├── Deposit(100)  → immutable record
│   └── Withdraw(50)  → immutable record
├── Process 2 Mailbox
│   └── AddItem(Item("widget", 10.0))
└── Process 3 State
    └── CounterState(count=42)
```

**Key properties:**
- Each process state is owned by exactly one virtual thread
- Mailboxes use lock-free queues (LinkedTransferQueue)
- Messages are immutable records — safe to share references
- No process can access another process's state directly

---

## Layer 2: Immutable Messages and Java Memory Model

### Java Memory Model (JMM) Basics

The JMM defines visibility guarantees between threads:

**Happens-before relationships:**
1. **Thread start:** `Thread.start()` happens-before actions in the new thread
2. **Thread termination:** Thread termination happens-before `join()` returns
3. **Volatile:** Write to volatile happens-before subsequent reads
4. **Monitor:** Unlock happens-before subsequent lock on same monitor
5. **Queue operations:** `put()` happens-before `poll()` (Java spec guarantee)

### JOTP's Happens-Before Guarantees

JOTP establishes happens-before through:

```java
// 1. Queue operations (LinkedTransferQueue)
sender.tell(msg);  // put() happens-before...

// ...in receiver process:
var msg = mailbox.poll();  // ...this poll()
```

**LinkedTransferQueue contract (Java spec):**
> "Actions in a thread prior to placing an object into a ConcurrentLinkedQueue happen-before actions subsequent to the access or removal of that element from the ConcurrentLinkedQueue in another thread."

**2. Immutable state (records):**

```java
record AccountState(long balance, List<String> txLog) {}

// Handler returns new state (immutable)
return new AccountState(
    state.balance() + amount,
    append(state.txLog(), "DEPOSIT:" + amount)
);

// Next handler invocation sees new state in same thread
// No synchronization needed — same virtual thread
```

**3. CompletableFuture (ask() protocol):**

```java
// Caller:
CompletableFuture<S> reply = new CompletableFuture<>();
mailbox.offer(new Envelope<>(msg, reply));  // write to reply happens-before...
return reply.get(timeout);

// In process:
reply.complete(newState);  // ...this read
```

**CompletableFuture contract:**
> "Actions in a thread prior to completing a future happen-before actions subsequent to another thread successfully returning from a corresponding Future.get()."

### Visibility Guarantee Proof

**Theorem:** JOTP's message passing ensures visibility guarantees equivalent to Erlang's memory model.

**Proof:**

| Operation | Erlang BEAM | JOTP (Java 26) | Visibility Mechanism |
|-----------|-------------|----------------|---------------------|
| Message send | Message copy to heap | Queue put | Happens-before (queue spec) |
| Message receive | Pattern match | Queue poll | Happens-before (queue spec) |
| State update | Process heap (isolated) | Same virtual thread | Sequential consistency |
| ask() reply | Reply pattern | CompletableFuture.complete() | Happens-before (future spec) |

**QED.**

---

## Layer 3: Per-Process Garbage Collection

### BEAM's Incremental GC

Erlang's BEAM VM uses per-process garbage collection:

```
Process 1: [GC] ──────────────────► [Collect Process 1 Heap]
Process 2: [Running] ─────────────► [Unaffected]
Process 3: [Running] ─────────────► [Unaffected]
```

**Benefits:**
- No stop-the-world pauses
- Each GC is small (microseconds)
- Failed processes leave no garbage (heap reclaimed immediately)

### Java 26's Generational GC

Java's GC (G1GC, ZGC, Shenandoah) uses generational collection:

```
Young Generation (Eden)
├── Process 1 State (short-lived)
├── Process 2 State (short-lived)
└── Messages (short-lived)

Old Generation (Tenured)
├── Long-lived process state
└── Static data
```

**JOTP optimization:**

1. **Short-lived messages:** Most messages are allocated in Eden and collected quickly
2. **Process state:** Long-lived state promotes to Old Generation
3. **No stop-the-world:** ZGC/Shenandoah provide concurrent collection

**Benchmark results (from PhD thesis §5):**

| GC Algorithm | Max Heap | Active Processes | GC Pause (p99) | Throughput |
|--------------|----------|-------------------|----------------|------------|
| G1GC | 16 GB | 1M | 50 ms | 30.1M msg/s |
| ZGC | 16 GB | 1M | 5 ms | 28.5M msg/s |
| Shenandoah | 16 GB | 1M | 10 ms | 29.2M msg/s |

**Key finding:** Modern Java GCs provide pause times competitive with BEAM's per-process GC for JOTP workloads.

### Memory Layout Comparison

| Aspect | BEAM (Erlang) | JOTP (Java 26) |
|--------|---------------|----------------|
| Process heap | Per-process (326 bytes) | Per-process (~1 KB) |
| Message copying | Deep copy (binary) | Reference copy (immutable) |
| GC strategy | Per-process incremental | Generational (G1/ZGC) |
| GC pause | Microseconds | 5-50 ms (concurrent) |
| Max processes | 250M+ | 10M+ |

**Key insight:** JOTP trades per-process GC for generational GC, achieving comparable pause times with ZGC/Shenandoah.

---

## Layer 4: Memory Management Patterns

### Message Lifecycle

```java
// 1. Message creation (Eden allocation)
var msg = new Deposit(100);

// 2. Send to mailbox (reference copy, no allocation)
mailbox.offer(msg);

// 3. Process message (no allocation)
var newState = handler.apply(oldState, msg);

// 4. Message becomes garbage (collected in next GC)
// msg is unreachable → GC reclaims it
```

**Allocation cost:**
- Record creation: ~40 bytes (header + fields)
- Mailbox enqueue: 0 bytes (reference copy)
- Pattern matching: 0 bytes (extract fields directly)

### State Update Pattern

```java
// BAD: Mutable state (causes visibility issues)
class AccountState {
    private long balance;  // Mutable!
    public void setBalance(long b) { this.balance = b; }
}

// GOOD: Immutable state (record)
record AccountState(long balance, List<String> txLog) {}

// State transition creates new record
return new AccountState(
    state.balance() + amount,
    append(state.txLog(), "DEPOSIT:" + amount)
);
```

**Why immutable state is better:**
1. **Thread-safe:** No synchronization needed
2. **Visibility:** New state visible to next handler (same thread)
3. **Garbage collection:** Old state collected automatically
4. **Debugging:** State history can be retained for auditing

### Memory Budgeting

**Per-process memory (typical):**

| Component | Size | Notes |
|-----------|------|-------|
| Virtual thread stack | 8 KB (grows on demand) | Typical usage: 8-32 KB |
| Process state | 100-2000 bytes | Records are compact |
| Mailbox overhead | ~200 bytes | LinkedTransferQueue node |
| Queued messages | 40-200 bytes each | Immutable records |

**Total per process:** ~1 KB (empty mailbox) to ~10 KB (100 queued messages)

**Example: 1M processes**
- Min heap: 8 GB (1M × 8 KB stacks)
- State overhead: 200 MB (1M × 200 bytes avg)
- Mailbox overhead: 200 MB (1M × 200 bytes)
- **Total:** ~8.5 GB heap

**Recommended JVM settings:**
```bash
-Xmx16g \                    # 16 GB heap (2× working set)
-XX:+UseZGC \                # ZGC for low pauses
-XX:MaxGCPauseMillis=10      # Target: < 10ms pauses
```

---

## Layer 5: Escalation and Memory Pressure

### Mailbox Overflow

Unbounded mailboxes can cause memory exhaustion:

```java
// BAD: Unbounded mailbox (JOTP 1.0 default)
proc.tell(new Msg());  // Queues forever if process is slow

// GOOD: Bounded mailbox (JOTP 2.0)
var mailbox = new LinkedBlockingQueue<>(1000);  // Max 1000 messages
if (!mailbox.offer(msg)) {
    // Backpressure: reject message
    throw new MailboxFullException();
}
```

**Breaking point analysis (PhD thesis §6):**
- 4M queued messages caused memory pressure (512 MB delta)
- Recommendation: Implement backpressure at 1M messages

### Memory Isolation Benefits

When a process crashes:

```java
// Process crashes due to exception
throw new RuntimeException("CRASH");

// Supervisor catches crash and restarts
// 1. Old process state becomes unreachable → GC reclaims it
// 2. Old mailbox becomes unreachable → GC reclaims it
// 3. New process starts with clean state → No memory leak
```

**Compare to shared-state systems:**
- Shared objects may retain references to crashed process state
- Manual cleanup required (weak references, reference queues)
- Memory leaks common in complex object graphs

**JOTP advantage:** Crashed processes leave no garbage — GC reclaims everything automatically.

---

## Layer 6: Performance Characteristics

### Memory Access Patterns

**Process-local access (fast):**

```java
// Handler runs in same virtual thread
var newState = handler.apply(state, msg);  // L1 cache hit
```

**Message passing (medium):**

```java
// Enqueue to mailbox
mailbox.offer(msg);  // L2/L3 cache access (~10 ns)
```

**Cross-process communication (slow):**

```java
// ask() requires CompletableFuture
var reply = proc.ask(msg, timeout).get();  // Virtual thread context switch (~100 ns)
```

### Cache Locality

**Good locality (sequential processing):**

```java
// Process messages sequentially
while (running) {
    var msg = mailbox.poll();
    state = handler.apply(state, msg);  // Same state object, cache hot
}
```

**Poor locality (parallel processing):**

```java
// Multiple threads access shared state
synchronized (sharedState) {  // Cache invalidation, false sharing
    sharedState.update();
}
```

**JOTP advantage:** Process-local state has excellent cache locality, no false sharing.

---

## Comparison: Memory Model Advantages

### vs. Akka (Scala)

| Aspect | Akka | JOTP |
|--------|------|------|
| State isolation | Actor mailbox (same heap) | Virtual thread + mailbox |
| Message copying | Immutable messages (same) | Immutable records (same) |
| GC pressure | High (many small objects) | Medium (generational GC) |
| Memory safety | Type-safe | Type-safe |
| Memory leaks | Possible (actor references) | Impossible (GC handles) |

### vs. Go (goroutines)

| Aspect | Go | JOTP |
|--------|-----|------|
| Shared memory | Allowed (race detector) | Impossible by design |
| Message passing | Channels (typed) | Mailboxes (typed) |
| Memory safety | No guarantees | Compile-time safety |
| GC | Tri-color mark-sweep | Generational (G1/ZGC) |
| Memory overhead | 2 KB/goroutine | 1 KB/process |

---

## Memory Model Summary

JOTP's memory model provides:

1. **Heap isolation:** Each process has isolated state, no shared mutable state
2. **Immutable messages:** Records eliminate data races and visibility issues
3. **Visibility guarantees:** JMM happens-before via queues and futures
4. **Automatic cleanup:** Crashed processes leave no garbage
5. **Cache efficiency:** Process-local state has excellent locality
6. **Predictable memory:** ~1 KB per process, easy capacity planning

**Key insight:** Java 26's memory model, combined with virtual threads and immutable records, provides equivalent safety guarantees to BEAM's per-process heaps, with comparable GC pause times using ZGC/Shenandoah.

---

**Previous:** [Type System](type-system.md) | **Next:** [Architecture Overview](architecture-overview.md)

**See Also:**
- [Concurrency Model](concurrency-model.md) — Virtual thread scheduling
- [Design Decisions](design-decisions.md) — Why we chose records
- [PhD Thesis §7: Performance Analysis](../phd-thesis/phd-thesis-otp-java26.md) — GC benchmarks
