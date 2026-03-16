# Testing Issues Troubleshooting Guide

## Flaky Async Tests

### Symptoms
- Tests pass locally but fail in CI
- Intermittent timeout failures
- Race conditions in assertions
- Non-deterministic test outcomes

### Diagnosis Steps

1. **Identify async operations:**
   ```java
   @Test
   void testAsyncBehavior() {
       ProcRef<String, String> ref = proc.spawn();
       // ^ This is async - might not be ready immediately
       ref.send("hello");
       // ^ send() returns immediately
       assertEquals("world", state);  // Flaky - might not be processed yet
   }
   ```

2. **Check for missing waits:**
   ```bash
   # Run test multiple times to detect flakiness
   mvnd test -Dtest=FlakyTest -Dit.test.repeat=10
   ```

3. **Review assertion timing:**
   ```java
   // Bad: No wait for async operation
   proc.send(new Message());
   assertEquals(result, getResult());  // May execute before message processed

   // Good: Wait for result
   proc.send(new Message());
   await().atMost(5, TimeUnit.SECONDS)
         .until(() -> getResult().equals(expected));
   ```

### Solutions

#### Use Awaitility Properly
```java
import static org.awaitility.Awaitility.await;

@Test
void testAsyncProcessing() {
    ProcRef<String, String> ref = proc.spawn();
    ref.send("input");

    // Wait for async operation to complete
    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(() -> {
            assertEquals("expected", ref.state());
        });
}
```

#### Implement Test Barriers
```java
class Barrier {
    private final CountDownLatch latch = new CountDownLatch(1);

    void await() throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    void release() {
        latch.countDown();
    }
}

@Test
void testWithBarrier() {
    Barrier barrier = new Barrier();

    Proc<String, String> proc = new Proc<>("test") {
        protected void handle(Message msg) {
            process(msg);
            barrier.release();  // Signal test that we're done
        }
    };

    proc.send(new Message());
    barrier.await();  // Wait for process
}
```

#### Use ProcRef.await()
```java
@Test
void testProcStart() {
    Proc<String, String> proc = new Proc<>("test");
    ProcRef<String, String> ref = proc.spawn();

    // Wait for process to be ready
    Proc<String, String> proxy = ref.await(5, TimeUnit.SECONDS);
    assertNotNull(proxy);

    // Now safe to send messages
    proxy.send("hello");
}
```

### Prevention
- Always wait for async operations
- Use barriers for synchronization
- Run tests multiple times in CI
- Add timeouts to all waits

---

## Awaitility Timeouts

### Symptoms
- `ConditionTimeoutException`
- Tests fail waiting for conditions
- Increasing timeout doesn't help
- Conditions never become true

### Diagnosis Steps

1. **Check condition logic:**
   ```java
   await().until(() -> {
       return proc.state();  // If this throws, timeout occurs
   });
   ```

2. **Add debugging to condition:**
   ```java
   await()
       .until(() -> {
           String state = proc.state();
           log.info("Current state: {}", state);
           return state.equals("expected");
       });
   ```

3. **Verify process is running:**
   ```java
   assertEquals(ProcState.RUNNING, ref.state());
   // If not RUNNING, condition will never be met
   ```

### Solutions

#### Fix Condition Logic
**Problem:** Condition throws exception instead of returning false
```java
// Bad: Exception causes timeout
await().until(() -> {
    return proc.getNonExistentField();  // Throws NullPointerException
});

// Good: Handle exceptions
await().untilAsserted(() -> {
    assertDoesNotThrow(() -> {
        assertEquals("expected", proc.getState());
    });
});
```

#### Increase Timeout Appropriately
```java
// Bad: Too short for operation
await().atMost(100, TimeUnit.MILLISECONDS);  // Too short

// Good: Reasonable timeout
await().atMost(5, TimeUnit.SECONDS);  // Adjust based on operation
```

#### Check Polling Interval
```java
// Default is 100ms, might be too long for fast operations
await()
    .pollInterval(10, TimeUnit.MILLISECONDS)  // Poll faster
    .atMost(1, TimeUnit.SECONDS)
    .until(() -> result.isReady());
```

### Prevention
- Set appropriate timeouts for each operation
- Add logging to conditions for debugging
- Handle exceptions in conditions
- Test with different system loads

---

## Mock Setup Problems

### Symptoms
- Mock not invoked in test
- Wrong mock called
- Mock returns null unexpectedly
- Verifications fail

### Diagnosis Steps

1. **Verify mock injection:**
   ```java
   @Test
   void testWithMock() {
       Dependency dep = mock(Dependency.class);
       Proc<String, String> proc = new Proc<>("test", dep);

       // Verify mock was passed
       assertNotNull(proc.dependency);
   }
   ```

2. **Check message flow:**
   ```java
   @Test
   void testMockInteraction() {
       depMock.call();
       verify(depMock).call();  // Verify mock was called

       // If this fails, check if message was actually sent
   }
   ```

3. **Enable mock debugging:**
   ```java
   @ExtendWith(MockitoExtension.class)
   class TestWithDebugging {
       @Mock
       Dependency dep;

       @Test
       void test() {
           // Enable verbose logging
           Mockito.mockingProgress().setVerificationStrategy(VerboseVerificationStrategy.INSTANCE);
       }
   }
   ```

### Solutions

#### Fix Mock Injection
```java
// Bad: Mock not passed to process
Proc<String, String> proc = new Proc<>("test");
Dependency dep = mock(Dependency.class);
// dep not used!

// Good: Inject mock via constructor
Proc<String, String> proc = new Proc<>("test", dep);

// Or use factory
ProcRef<String, String> ref = Proc.spawn(
    () -> new Proc<String, String>("test", depMock)
);
```

#### Use Answer for Complex Mocks
```java
// Bad: Simple stubbing not enough
when(depMock.compute(any())).thenReturn(result);

// Good: Custom Answer for async behavior
when(depMock.compute(any())).thenAnswer(invocation -> {
    String input = invocation.getArgument(0);
    // Simulate async processing
    CompletableFuture.supplyAsync(() -> {
        return processInput(input);
    });
    return "immediate";
});
```

#### Verify In-Order Calls
```java
@Test
void testCallSequence() {
    InOrder inOrder = inOrder(depMock1, depMock2);

    proc.send(new Message());

    inOrder.verify(depMock1).prepare();
    inOrder.verify(depMock2).execute();
    inOrder.verify(depMock1).cleanup();
}
```

### Prevention
- Use constructor injection for dependencies
- Verify mocks are actually called
- Use explicit verification over stubbing
- Enable verbose mock debugging in development

---

## Test Isolation Failures

### Symptoms
- Tests pass individually but fail in suite
- State leaks between tests
- Port conflicts
- Registry already registered errors

### Diagnosis Steps

1. **Run tests individually:**
   ```bash
   mvnd test -Dtest=TestA
   mvnd test -Dtest=TestB
   mvnd test -Dtest=TestA,TestB  # Both together
   ```

2. **Check for static state:**
   ```java
   // Bad: Static state persists
   public static ProcRegistry registry = ProcRegistry.getInstance();

   // Good: Fresh registry per test
   @BeforeEach
   void setUp() {
       ProcRegistry.setInstance(new ProcRegistry());
   }
   ```

3. **Look for port conflicts:**
   ```bash
   # Check if ports in use
   lsof -i :8080

   # Use random ports in tests
   @DynamicPropertySource
   static void configurePort(DynamicPropertyRegistry registry) {
       registry.add("server.port", () -> SocketUtils.findAvailableTcpPort());
   }
   ```

### Solutions

#### Proper Test Lifecycle
```java
class ProcTest {
    private Proc<String, String> proc;

    @BeforeEach
    void setUp() {
        // Fresh state per test
        proc = new Proc<>("test-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        // Clean up
        if (proc != null) {
            proc.shutdown(Duration.ofSeconds(5));
        }
        ProcRegistry.getInstance().clear();
    }
}
```

#### Use Test Registry
```java
// Isolated registry per test
class TestRegistry {
    private static final ThreadLocal<ProcRegistry> registries =
        ThreadLocal.withInitial(ProcRegistry::new);

    @BeforeEach
    void setUp() {
        registries.set(new ProcRegistry());
        ProcRegistry.setInstance(registries.get());
    }
}
```

#### Randomize Resource Names
```java
@Test
void testProcRegistry() {
    String uniqueName = "proc-" + UUID.randomUUID();

    ProcRef<String, String> ref = Proc.spawn(uniqueName, ...);
    assertNotNull(ref);

    // No conflict with other tests
}
```

### Prevention
- Always clean up in @AfterEach
- Use unique names for registered processes
- Avoid static mutable state
- Run tests in random order

---

## Supervisor Testing Issues

### Symptoms
- Children not starting in tests
- Restart strategies not applied
- Test hangs waiting for shutdown
- Supervisor state unclear

### Diagnosis Steps

1. **Check supervisor state:**
   ```java
   SupervisorRef ref = supervisor.ref();
   assertEquals(SupervisorState.RUNNING, ref.state());

   // Check children
   List<ProcRef<?, ?>> children = ref.getChildren();
   assertFalse(children.isEmpty());
   ```

2. **Test restart behavior:**
   ```java
   @Test
   void testChildRestart() {
       // Spawn child
       supervisor.startChild(spec);

       // Crash child
       childRef.exit(new RuntimeException("Test crash"));

       // Wait for restart
       await().atMost(5, TimeUnit.SECONDS)
              .until(() -> {
                  return ref.getChildren().size() == 1;
              });
   }
   ```

3. **Verify shutdown:**
   ```java
   @AfterEach
   void tearDown() {
       boolean stopped = supervisor.awaitTermination(10, TimeUnit.SECONDS);
       assertTrue(stopped, "Supervisor did not shutdown in time");
   }
   ```

### Solutions

#### Use Test Doubles for Children
```java
class TestChild extends Proc<String, String> {
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private final CountDownLatch terminateLatch = new CountDownLatch(1);

    @Override
    protected void init() {
        initLatch.countDown();
    }

    @Override
    protected void terminate() {
        terminateLatch.countDown();
    }

    void awaitInit() throws InterruptedException {
        assertTrue(initLatch.await(5, TimeUnit.SECONDS));
    }

    void awaitTerminate() throws InterruptedException {
        assertTrue(terminateLatch.await(5, TimeUnit.SECONDS));
    }
}

@Test
void testSupervisor() {
    TestChild child = new TestChild();
    supervisor.startChild(child);
    child.awaitInit();  // Wait for ready

    child.exit(new RuntimeException("Crash"));
    child.awaitTerminate();  // Wait for shutdown
}
```

#### Verify Restart Strategy
```java
@Test
void testOneForOneStrategy() {
    Supervisor supervisor = new Supervisor(
        RestartStrategy.ONE_FOR_ONE,
        5,
        Duration.ofSeconds(10)
    );

    // Start 3 children
    supervisor.startChild(spec1);
    supervisor.startChild(spec2);
    supervisor.startChild(spec3);

    // Crash child1
    child1Ref.exit(new RuntimeException("Crash"));

    // Verify only child1 restarted
    await().until(() -> {
        List<ProcRef<?, ?>> children = supervisor.ref().getChildren();
        return children.size() == 3 &&
               !children.contains(child1Ref) &&  // Old ref gone
               children.stream().anyMatch(ref -> ref.id().startsWith("child1"));  // New ref exists
    });
}
```

### Prevention
- Use latches to synchronize test lifecycle
- Test restart strategies explicitly
- Always shutdown supervisors in tests
- Verify child states after operations

---

## Quick Reference

### Test Structure Template

```java
@ExtendWith(MockitoExtension.class)
class ProcTest {

    @Mock
    Dependency dependency;

    private Proc<String, String> proc;

    @BeforeEach
    void setUp() {
        proc = new Proc<>("test-" + UUID.randomUUID(), dependency);
        ProcRegistry.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        if (proc != null) {
            proc.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void testAsyncOperation() {
        // Arrange
        String input = "test";

        // Act
        proc.send(new Message(input));

        // Assert
        await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertEquals("expected", proc.state());
            });

        verify(dependency).handle(any());
    }
}
```

### Common Awaitility Patterns

```java
// Wait for state change
await().until(() -> proc.state().equals("ready"));

// Wait with custom condition
await().untilTrue(proc.isReady());

// Wait with polling
await()
    .pollInterval(100, TimeUnit.MILLISECONDS)
    .atMost(5, TimeUnit.SECONDS)
    .until(() -> result.isComplete());

// Wait for multiple conditions
await().untilAsserted(() -> {
    assertAll(
        () -> assertEquals("ready", proc1.state()),
        () -> assertEquals("ready", proc2.state()),
        () -> verify(dependency).init()
    );
});
```

### Debugging Flaky Tests

```bash
# Run test many times
mvnd test -Dtest=FlakyTest -Dit.test.repeat=100

# With different ordering
mvnd test -Dtest=*Test -Dsurefire.runOrder=random

# With timeout detection
mvnd test -Dtest=*Test -Dsurefire.timeoutForced=120s

# Enable verbose output
mvnd test -X -Dtest=*Test
```

---

## Related Issues

- **Runtime Issues:** If tests expose runtime problems, see `runtime-issues.md`
- **Debugging:** For test debugging techniques, see `debugging-techniques.md`
- **Performance:** If tests are slow, see `performance-issues.md`
