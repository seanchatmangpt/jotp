# Runtime Issues Troubleshooting Guide

## Process Won't Start

### Symptoms
- `spawn()` returns `ProcRef` but process never initializes
- Initialization timeout in `await()`
- Process exits immediately after creation
- "Process not ready" exceptions

### Diagnosis Steps

1. **Check process logs:**
   ```java
   // Enable debug logging
   Logger logger = LoggerFactory.getLogger(Proc.class);
   logger.setLevel(Level.DEBUG);
   ```

2. **Verify virtual thread availability:**
   ```bash
   # Check JVM supports virtual threads
   java --version
   # Should be Java 21+ (JOTP uses Java 26)
   ```

3. **Inspect process state:**
   ```java
   ProcRef<String, String> ref = proc.ref();
   System.out.println("State: " + ref.state());  // STARTING, RUNNING, EXITED
   ```

4. **Check for exceptions in init:**
   ```java
   Proc<S, M> proc = new Proc<S, M>(initialState) {
       @Override
       protected void init() {
           try {
               // Init code
           } catch (Exception e) {
               log.error("Init failed", e);
               throw e;
           }
       }
   };
   ```

### Solutions

#### Fix Init Block
**Problem:** Exception in `init()` kills process before it starts
```java
// Bad: Exception escapes init()
Proc<String, String> proc = new Proc<>("state") {
    @Override
    protected void init() {
        throw new RuntimeException("Setup failed");  // Process dies
    }
};

// Good: Handle exceptions gracefully
Proc<String, String> proc = new Proc<>("state") {
    @Override
    protected void init() {
        try {
            // Setup code
        } catch (Exception e) {
            log.error("Init failed, using defaults", e);
            // Use safe defaults instead of crashing
        }
    }
};
```

#### Use Timeout on Await
```java
ProcRef<String, String> ref = proc.spawn();
try {
    Proc<String, String> proxy = ref.await(5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    log.error("Process failed to start in 5s");
    ref.shutdown();  // Clean up stuck process
}
```

#### Check Supervisor Blocking
**Problem:** Supervisor process blocking child startup
```java
// Ensure supervisor is running
ProcRef<String, String> supervisor = supervisorRef.await();
if (supervisor.state() != ProcState.RUNNING) {
    throw new IllegalStateException("Supervisor not running");
}

// Then spawn child
ProcRef<String, String> child = supervisor.spawnChild(childSpec);
```

### Prevention
- Always use `await()` with timeout
- Log exceptions in `init()` block
- Test process startup in isolation
- Use health checks before dependency injection

---

## Messages Not Delivered

### Symptoms
- `send()` succeeds but `receive()` never gets message
- Messages disappear without error
- Intermittent delivery failures
- Messages arrive out of order

### Diagnosis Steps

1. **Verify mailbox size:**
   ```java
   ProcRef<String, String> ref = proc.ref();
   log.info("Mailbox size: " + ref.mailboxSize());
   ```

2. **Check message type matching:**
   ```java
   // Ensure pattern matching handles all message types
   @Override
   protected void handle(M message) {
       switch (message) {
           case TypeA a -> handleA(a);
           case TypeB b -> handleB(b);
           // Missing: default case -> logs dropped messages
       }
   }
   ```

3. **Test message delivery:**
   ```java
   // Send test message
   ref.send("ping");
   // Wait for response
   String reply = ref.ask(5, TimeUnit.SECONDS);
   ```

4. **Check for blocking operations:**
   ```java
   // Long-running operation blocks mailbox processing
   @Override
   protected void handle(M message) {
       Thread.sleep(10000);  // BAD: Blocks for 10s
   }
   ```

### Solutions

#### Fix Pattern Matching
**Problem:** Unhandled message types are silently dropped
```java
// Bad: Missing default case
@Override
protected void handle(Message msg) {
    switch (msg) {
        case Ping p -> reply(new Pong());
        case Shutdown s -> exit();
        // Other messages are dropped!
    }
}

// Good: Handle all cases
@Override
protected void handle(Message msg) {
    switch (msg) {
        case Ping p -> reply(new Pong());
        case Shutdown s -> exit();
        case null, default -> {
            log.warn("Unhandled message: " + msg);
            // Optionally throw to crash process
        }
    }
}
```

#### Use Sealed Message Types
```java
// Compile-time guarantee all cases handled
public sealed interface Message permits Ping, Pong, Shutdown {
    record Ping() implements Message {}
    record Pong() implements Message {}
    record Shutdown() implements Message {}
}

// Compiler warns if switch is non-exhaustive
@Override
protected void handle(Message msg) {
    switch (msg) {
        case Ping p -> reply(new Pong());
        case Pong p -> handlePong(p);
        case Shutdown s -> exit();
        // No default needed - compiler verifies exhaustiveness
    }
}
```

#### Avoid Blocking Operations
```java
// Bad: Blocks mailbox thread
protected void handle(Work work) {
    String result = heavyComputation(work);  // Takes 10s
    reply(result);
}

// Good: Spawn worker process
protected void handle(Work work) {
    spawnWorker().send(work);
}

protected void handle(Result result) {
    reply(result);
}
```

### Prevention
- Use sealed interfaces for message types
- Enable exhaustive pattern matching warnings
- Monitor mailbox size in production
- Profile handler execution time

---

## Mailbox Overflow

### Symptoms
- OutOfMemoryError in process mailbox
- Increasing memory usage over time
- Messages delivered with extreme latency
- "Mailbox full" warnings in logs

### Diagnosis Steps

1. **Check mailbox depth:**
   ```java
   ProcRef<S, M> ref = proc.ref();
   long depth = ref.mailboxSize();
   log.info("Mailbox depth: " + depth);
   ```

2. **Profile message throughput:**
   ```java
   AtomicLong sent = new AtomicLong(0);
   AtomicLong processed = new AtomicLong(0);

   // Compare rates
   long sendRate = sent.get() / elapsed;
   long processRate = processed.get() / elapsed;

   if (sendRate > processRate) {
       log.warn("Producer faster than consumer!");
   }
   ```

3. **Heap dump analysis:**
   ```bash
   # Dump heap
   jmap -dump:format=b,file=heap.hprof <pid>

   # Analyze with MAT
   # Look for: java.util.concurrent.ConcurrentLinkedQueue
   # containing message objects
   ```

### Solutions

#### Implement Backpressure
```java
// Bad: Unbounded send
for (int i = 0; i < 1_000_000; i++) {
    ref.send(new Message(i));  // Mailbox grows forever
}

// Good: Check mailbox size before send
for (int i = 0; i < 1_000_000; i++) {
    while (ref.mailboxSize() > 1000) {
        Thread.sleep(10);  // Backpressure
    }
    ref.send(new Message(i));
}
```

#### Use Bounded Mailbox
```java
Proc<S, M> proc = new Proc<S, M>(state) {
    @Override
    protected void init() {
        setMaxMailboxSize(1000);  // Drop or block when full
    }

    @Override
    protected void handleOverflow(Message dropped) {
        log.warn("Mailbox full, dropping: " + dropped);
        metrics.increment("mailbox.overflow");
    }
};
```

#### Increase Consumer Throughput
```java
// Batch processing
protected void handle(Batch batch) {
    batch.messages().forEach(this::process);
}

// Or spawn more consumers
protected void init() {
    for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
        spawnConsumer();
    }
}
```

### Prevention
- Set maximum mailbox size
- Monitor mailbox depth metrics
- Implement backpressure in producers
- Use batch messages for bulk data

---

## Deadlock Diagnosis

### Symptoms
- Application hangs indefinitely
- Process stuck in `ask()` call
- Multiple processes waiting on each other
- Thread dump shows waiting state

### Diagnosis Steps

1. **Capture thread dump:**
   ```bash
   # Get PID
   jps -l | grep jotp

   # Dump threads
   jstack <pid> > threaddump.txt

   # Look for:
   # - "waiting on condition" in Proc.ask()
   # - "BLOCKED" on synchronized
   ```

2. **Check for circular waits:**
   ```java
   // Deadlock pattern:
   procA.ask(() -> {
       // Waiting for procB
       return procB.ask(() -> {
           // Waiting for procA (DEADLOCK)
           return "stuck";
       });
   });
   ```

3. **Monitor timeouts:**
   ```java
   // Use timeout to detect deadlocks
   try {
       String result = ref.ask(5, TimeUnit.SECONDS);
   } catch (TimeoutException e) {
       log.error("Potential deadlock - no response in 5s");
       captureThreadDump();
   }
   ```

4. **Use deadlock detection:**
   ```bash
   # JVM built-in detection
   java -Djdk.findDeadlock=true ...
   ```

### Solutions

#### Fix Circular Ask Chains
**Problem:** Process A asks B, B asks A (deadlock)
```java
// Bad: Deadlock
Proc<String, String> a = new Proc<>("A") {
    protected void handle(Msg m) {
        String response = b.ask(() -> "from A");  // Waits for B
    }
};

Proc<String, String> b = new Proc<>("B") {
    protected void handle(Msg m) {
        String response = a.ask(() -> "from B");  // Waits for A (DEADLOCK)
    }
};

// Good: Use fire-and-forget + callback
a.send(new Request());
// A → B, then B → A via callback
```

#### Use One-Way Messages
```java
// Instead of blocking ask:
String result = proc.ask(() -> "request");

// Use async pattern:
proc.send(new Request());
// Later, in handle(Response):
protected void handle(Response r) {
    processResult(r.value());
}
```

#### Implement Timeout with Fallback
```java
// Good: Timeout prevents indefinite hang
try {
    String result = proc.ask(5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    log.warn("Proc timeout, using fallback");
    result = fallbackValue();
}
```

### Prevention
- Avoid nested `ask()` calls
- Always use timeout in `ask()`
- Prefer fire-and-forget messaging
- Run deadlock detector in development

---

## Virtual Thread Pinning

### Symptoms
- Poor performance despite virtual threads
- "Pinned virtual thread" warnings
- Platform thread exhaustion
- High CPU under load

### Diagnosis Steps

1. **Enable pinning diagnostics:**
   ```bash
   java -Djdk.virtualThreadScheduler.pinning.mode=warn \
        -Djdk.tracePinnedThreads=full ...
   ```

2. **Check for blocking operations:**
   ```java
   // Common pinning causes:
   synchronized (lock) {
       Thread.sleep(1000);  // PINNED
   }

   nativeMethod();  // PINNED if it blocks
   ```

3. **Profile thread behavior:**
   ```bash
   # Monitor virtual thread count
   jcmd <pid> Thread.dump_to_file -format=json threads.json

   # Check parked vs pinned threads
   ```

### Solutions

#### Replace synchronized with ReentrantLock
```java
// Bad: synchronized pins virtual thread
synchronized (lock) {
    process();
}

// Good: ReentrantLock doesn't pin
private final ReentrantLock lock = new ReentrantLock();

void process() {
    lock.lock();
    try {
        process();
    } finally {
        lock.unlock();
    }
}
```

#### Avoid Native Blocking
```java
// Bad: Blocks carrier thread
FileInputStream fis = new FileInputStream(file);
byte[] data = fis.readAllBytes();  // PINNED

// Good: Use async I/O
AsynchronousFileChannel channel = AsynchronousFileChannel.open(path);
Future<Integer> read = channel.read(buffer, 0);
```

#### Move Heavy Work to Platform Thread
```java
// Good: Explicitly use platform thread for pinning operations
ExecutorService platformPool = Executors.newFixedThreadPool(4);

void heavyOperation() {
    CompletableFuture.supplyAsync(
        () -> {
            synchronized (lock) {
                return blockingOperation();
            }
        },
        platformPool  // Use platform thread pool
    ).thenAcceptAsync(result -> {
        // Back to virtual thread
        this.send(new Result(result));
    });
}
```

### Prevention
- Enable pinning warnings in development
- Use ReentrantLock instead of synchronized
- Prefer async I/O over blocking I/O
- Profile with JDK Flight Recorder

---

## Quick Reference

### Enable Debug Logging
```java
// Log4j2
<Configuration status="debug">
    <Loggers>
        <Logger name="io.github.seanchatmangpt.jotp" level="debug"/>
    </Loggers>
</Configuration>
```

### Capture Thread Dump
```bash
# Signal-based
kill -QUIT <pid>

# jstack
jstack -l <pid> > threaddump.txt

# JCMD
jcmd <pid> Thread.print
```

### Heap Dump
```bash
# Live objects
jmap -dump:live,format=b,file=heap.hprof <pid>

# All objects
jmap -dump:format=b,file=heap.hprof <pid>
```

### JVM Flags for Diagnostics
```bash
java -Djdk.tracePinnedThreads=full \
     -Djdk.virtualThreadScheduler.pinning.mode=warn \
     -Djdk.findDeadlock=true \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/tmp/heap.hprof \
     ...
```

---

## Related Issues

- **Supervision Issues:** If processes crash during startup, see `supervision-issues.md`
- **Performance:** If system is slow, see `performance-issues.md`
- **Debugging:** For advanced diagnostics, see `debugging-techniques.md`
