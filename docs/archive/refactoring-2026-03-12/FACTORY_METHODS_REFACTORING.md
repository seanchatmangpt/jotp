# ⚠️ ARCHIVED - Factory Methods Standardization Refactoring

**ARCHIVE NOTICE**: This is completed project documentation from March 2026.
**Project Completion**: 2026-03-12
**Status**: ✅ COMPLETED
**Details**: `ARCHIVE_NOTICE.md` in this directory

---

## Overview

This refactoring standardizes factory method patterns across all OTP primitives in JOTP, establishing consistent, explicit, and discoverable creation patterns while maintaining full backward compatibility.

**Status:** Complete ✓

## Motivation

Factory methods provide several advantages over constructors:

1. **Explicit Intent** — Method names like `spawn()`, `create()`, and `start()` clearly indicate intent
2. **Testability** — Easy to mock, stub, or subclass (constructors are final)
3. **Flexibility** — Can add variants (named supervisors, custom timeouts) without breaking existing code
4. **Discoverability** — IDE autocomplete and Javadoc make patterns visible to users
5. **Evolution** — Can add precondition checks, logging, or instrumentation later

## Changes Summary

### 1. Proc<S,M> — `Proc.spawn()`

**Added:**
- `public static <S,M> Proc<S,M> spawn(S initial, BiFunction<S,M,S> handler)` — Creates and starts a lightweight process with a virtual thread mailbox
- Comprehensive javadoc with usage examples showing typical process patterns
- Cross-references to `tell()`, `ask()`, and `ProcRef` for process references

**Deprecated:**
- `public Proc(S initial, BiFunction<S,M,S> handler)` constructor (marked `@Deprecated(since="1.0", forRemoval=true)`)
- Will remain functional for backward compatibility but logs migration path in javadoc

**Files Changed:**
- `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Proc.java`

### 2. Supervisor — `Supervisor.create()`

**Added:**
- `public static Supervisor create(Strategy strategy, int maxRestarts, Duration window)` — Creates unnamed supervisor
- `public static Supervisor create(String name, Strategy strategy, int maxRestarts, Duration window)` — Creates named supervisor (better for diagnostics)
- Comprehensive javadoc with usage examples showing ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE strategies
- Examples show restart limits and graceful shutdown

**Deprecated:**
- `public Supervisor(Strategy strategy, int maxRestarts, Duration window)` constructor
- `public Supervisor(String name, Strategy strategy, int maxRestarts, Duration window)` named constructor
- Both marked `@Deprecated(since="1.0", forRemoval=true)`

**Files Changed:**
- `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java`

### 3. StateMachine<S,E,D> — `StateMachine.create()`

**Added:**
- `public static <S,E,D> StateMachine<S,E,D> create(S initialState, D initialData, TransitionFn<S,E,D> fn)` — Creates and starts a state machine
- Comprehensive javadoc with detailed code lock example showing all three transition types
- Examples demonstrate pattern matching with sealed interfaces and records
- References to `send()`, `call()`, `state()`, and `data()` query methods

**Deprecated:**
- `public StateMachine(S initialState, D initialData, TransitionFn<S,E,D> fn)` constructor
- Marked `@Deprecated(since="1.0", forRemoval=true)`

**Files Changed:**
- `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/StateMachine.java`

### 4. EventManager<E> — Already Has `EventManager.start()`

**Enhanced Javadoc:**
- `public static <E> EventManager<E> start()` — Improved javadoc with complete logging handler example
- `public static <E> EventManager<E> start(Duration timeout)` — Improved javadoc explaining custom timeout behavior
- Added cross-references to `addHandler()`, `notify()`, `syncNotify()`, `deleteHandler()`, and `call()`

**No Deprecations:**
- Constructors already private; factory pattern was pre-existing

**Files Changed:**
- `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/EventManager.java`

### 5. Parallel — Already Has `Parallel.all()`

**Enhanced Javadoc:**
- `public static <T> Result<List<T>, Exception> all(List<Supplier<T>> tasks)` — Expanded javadoc
- Added Armstrong quote emphasizing fail-fast philosophy
- Complete usage example showing success/failure handling
- Cross-references to `Result<T,E>` railway-oriented error handling

**No Deprecations:**
- Constructors already private; factory pattern was pre-existing

**Files Changed:**
- `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Parallel.java`

### 6. CrashRecovery — Already Has `CrashRecovery.retry()`

**Enhanced Javadoc:**
- `public static <T> Result<T, Exception> retry(int maxAttempts, Supplier<T> supplier)` — Expanded javadoc
- Added comprehensive HTTP retry example with error handling
- Clarified distinction between resilient single-task execution vs. persistent supervisor management
- Cross-references to `Supervisor` for long-running process supervision

**No Deprecations:**
- Constructors already private; factory pattern was pre-existing

**Files Changed:**
- `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/CrashRecovery.java`

## New Demonstration Class

**Created:** `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/FactoryMethodPatterns.java`

Comprehensive examples showing:
1. `exampleProcSpawn()` — Lightweight process creation
2. `exampleSupervisorCreate()` — Unnamed supervision tree
3. `exampleNamedSupervisor()` — Named supervision tree
4. `exampleStateMachineCreate()` — State machine with sealed types
5. `exampleEventManagerStart()` — Event manager with handlers
6. `exampleParallelAll()` — Parallel task execution
7. `exampleCrashRecoveryRetry()` — Resilient retry logic
8. `exampleCompleteIntegration()` — All patterns working together

Each example includes:
- Clear variable names and type signatures
- Sealed interface patterns for type safety
- Usage of new factory methods (not constructors)
- Realistic scenarios (counters, locks, logging)

## Documentation Updates

### module-info.java
- Updated usage example in module javadoc to use `Proc.spawn()` instead of `new Proc()`
- Updated supervisor example to use `Supervisor.create()` factory
- Added proper error handling in example code
- Clarified Duration import requirement

### README.md
- Updated "Quick Start" section with modern factory pattern examples
- Added Counter record and sealed interface examples
- Shows full workflow: define → spawn → supervise → send messages
- Demonstrates `Supervisor.create()` with strategy and window parameters

### CLAUDE.md
- Updated "Joe Armstrong / Erlang/OTP patterns" section with factory method callouts
- Highlighted standardized factory names: `Proc.spawn()`, `Supervisor.create()`, etc.
- Added bold formatting for factory method names for discoverability
- Updated all six refactored primitives with factory method references

## Backward Compatibility

**Full Backward Compatibility Maintained:**

1. **Old constructors remain functional** — All deprecated constructors still work with `@Deprecated` annotations
2. **No breaking changes** — Existing code using `new Proc()`, `new Supervisor()`, `new StateMachine()` continues to compile and run
3. **Deprecation warnings** — IDE and compiler will flag old patterns, guiding users toward new factories
4. **Removal schedule** — Marked `forRemoval=true` indicates intent to remove in next major version

**Migration Path:**
```java
// Old (deprecated)
var proc = new Proc<>(state, handler);
var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));
var sm = new StateMachine<>(state, data, fn);

// New (recommended)
var proc = Proc.spawn(state, handler);
var supervisor = Supervisor.create(strategy, 5, Duration.ofSeconds(60));
var sm = StateMachine.create(state, data, fn);
```

## Testing Recommendations

When adding test files, update them to use new factory methods:

```java
@Test
void testProcSpawn() {
    Proc<Counter, CounterMsg> proc = Proc.spawn(
        new Counter(0),
        (state, msg) -> // handle
    );
    // assertions
}

@Test
void testSupervisorCreate() {
    Supervisor supervisor = Supervisor.create(
        Supervisor.Strategy.ONE_FOR_ONE,
        5,
        Duration.ofSeconds(60)
    );
    // assertions
}

@Test
void testStateMachineCreate() {
    StateMachine<State, Event, Data> sm = StateMachine.create(
        initialState,
        initialData,
        (state, event, data) -> // transitions
    );
    // assertions
}
```

## Design Patterns Applied

1. **Static Factory Method Pattern** — Joshua Bloch (Effective Java Item 1)
   - More readable and maintainable than constructors
   - Can have descriptive names (`spawn`, `create`, `start`)
   - Can return subtypes or cache instances
   - Can add preconditions without modifying constructor signatures

2. **Builder Pattern (implicit)** — Supervisor supports optional names via overloaded factory
   - Unnamed: `Supervisor.create(strategy, maxRestarts, window)`
   - Named: `Supervisor.create(name, strategy, maxRestarts, window)`

3. **Factory Method Variant Patterns**
   - **Proc**: Single factory `spawn()`
   - **Supervisor**: Two factories (unnamed + named)
   - **StateMachine**: Single factory `create()`
   - **EventManager**: Two factories (default timeout + custom timeout)
   - **Parallel**: Single factory `all()`
   - **CrashRecovery**: Single factory `retry()`

## Files Modified

1. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Proc.java`
   - Added `spawn()` factory method
   - Deprecated constructor

2. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java`
   - Added `create(Strategy, int, Duration)` factory
   - Added `create(String, Strategy, int, Duration)` factory
   - Deprecated both constructors

3. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/StateMachine.java`
   - Added `create(S, D, TransitionFn)` factory
   - Deprecated constructor

4. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/EventManager.java`
   - Enhanced javadoc for `start()` and `start(Duration)` factories

5. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Parallel.java`
   - Enhanced javadoc for `all()` factory

6. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/CrashRecovery.java`
   - Enhanced javadoc for `retry()` factory

7. `/home/user/jotp/src/main/java/module-info.java`
   - Updated usage examples to show new factory patterns

8. `/home/user/jotp/README.md`
   - Updated Quick Start section with modern factory examples

9. `/home/user/jotp/CLAUDE.md`
   - Documented standardized factory method names for all primitives

10. `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/FactoryMethodPatterns.java` (NEW)
    - Comprehensive demonstration class with 8 examples

## Verification Steps

To verify the refactoring is complete and correct:

```bash
# Compile with all changes
mvnd compile

# Run all tests
mvnd test

# Run full verification including formatting
mvnd verify

# Format all code
mvnd spotless:apply

# Check specific test patterns
mvnd test -Dtest=FactoryMethodPatterns
```

## Future Enhancements

Potential follow-up improvements:

1. **Fluent Builder for Supervisor**
   ```java
   Supervisor supervisor = Supervisor.builder()
       .named("app")
       .strategy(Strategy.ONE_FOR_ALL)
       .maxRestarts(5)
       .window(Duration.ofSeconds(60))
       .build();
   ```

2. **Process Pools** — Factory creating multiple processes
   ```java
   var workers = Proc.spawnPool(10, initialState, handler);
   ```

3. **Configuration Objects**
   ```java
   SupervisorConfig config = new SupervisorConfig()
       .strategy(Strategy.ONE_FOR_ONE)
       .maxRestarts(5);
   var supervisor = Supervisor.create(config);
   ```

4. **Factory Caching** — For frequently-created patterns
   ```java
   var cachedFactory = Proc.spawnFactory(handler);
   var proc1 = cachedFactory.spawn(state1);
   var proc2 = cachedFactory.spawn(state2);
   ```

## References

- **Joshua Bloch, "Effective Java", Item 1**: "Consider Static Factory Methods Instead of Constructors"
- **Design Patterns: Elements of Reusable Object-Oriented Software** — Gang of Four Factory Method Pattern
- **OTP Design Principles** — Joe Armstrong's supervisory tree and process spawning model
- **Google Java Style Guide** — Method naming conventions for factory patterns

## Deliverables Checklist

- ✓ All OTP primitives using consistent static factory methods
- ✓ Old constructors marked @Deprecated with migration guidance
- ✓ Full backward compatibility maintained
- ✓ Comprehensive javadoc with usage examples
- ✓ New FactoryMethodPatterns demonstration class
- ✓ Updated module-info.java example
- ✓ Updated README.md Quick Start
- ✓ Updated CLAUDE.md architecture documentation
- ✓ All factory methods explicit and discoverable
- ✓ No breaking changes to public API
