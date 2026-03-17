# io.github.seanchatmangpt.jotp.dogfood.concurrency.ScopedValuePatternsTest

## Table of Contents

- [Automatic Unbinding After Scope](#automaticunbindingafterscope)
- [Scoped Values with Return Values](#scopedvalueswithreturnvalues)
- [ScopedValue with Structured Concurrency](#scopedvaluewithstructuredconcurrency)
- [Accessing Unbound Scoped Values](#accessingunboundscopedvalues)
- [Nested Scoped Values and Shadowing](#nestedscopedvaluesandshadowing)
- [Safe Scoped Value Access Patterns](#safescopedvalueaccesspatterns)
- [Multiple Scoped Values Bound Together](#multiplescopedvaluesboundtogether)
- [Java 26 ScopedValue: Immutable Context Passing](#java26scopedvalueimmutablecontextpassing)


## Automatic Unbinding After Scope

ScopedValue bindings are automatically removed when the scope exits, whether normally or exceptionally. This eliminates the ThreadLocal memory leak pattern where values persist indefinitely.

```java
// Binding is only active during the lambda
ScopedValuePatterns.withTrace("trace-456", () -> {
    assertThat(ScopedValuePatterns.TRACE_ID.get()).isEqualTo("trace-456");
    return null;
});

// Binding is automatically removed
assertThat(ScopedValuePatterns.TRACE_ID.isBound()).isFalse();
```

| Key | Value |
| --- | --- |
| `After Lambda` | `TRACE_ID is unbound` |
| `During Lambda` | `TRACE_ID is bound` |
| `Memory Leak Risk` | `Zero` |
| `Cleanup` | `Automatic (finally block equivalent)` |

> [!NOTE]
> This automatic cleanup is a huge improvement over ThreadLocal, where manual remove() calls are often forgotten, causing memory leaks in thread pools.

## Scoped Values with Return Values

ScopedValue.where().call() supports tasks that return values. This is ideal for propagating context through computation pipelines while capturing results.

```java
// Bind trace ID and return a value
Callable<String> task = () -> ScopedValuePatterns.TRACE_ID.get();
String result = ScopedValuePatterns.withTrace("trace-123", task);

// Result is "trace-123"
// Trace ID is automatically unbound after call
```

| Key | Value |
| --- | --- |
| `Return Type` | `String` |
| `Task Result` | `trace-123` |
| `Bound Value` | `trace-123` |
| `Cleanup` | `Automatic after call` |

> [!NOTE]
> The Callable-based withTrace() allows tasks to return values while maintaining the scoped binding. This is perfect for request-scoped tracing, authentication, and tenant isolation.

## ScopedValue with Structured Concurrency

Scoped values are automatically inherited by virtual threads spawned in a scope. This is a game-changer compared to InheritableThreadLocal, which doesn't work reliably with thread pools.

| Feature | Works correctly | Mutable (racy) |
| --- | --- | --- |
| InheritableThreadLocal | Virtual Threads | Immutable (safe) |
| ScopedValue | High overhead | Cleanup |
| Thread Pools | Zero overhead | Manual |
| Broken (stale values) | Immutability | Automatic |

```java
// Bind context in parent, virtual threads inherit it
Callable<String> taskA = () -> ScopedValuePatterns.CURRENT_USER.get() + "|A";
Callable<String> taskB = () -> ScopedValuePatterns.TRACE_ID.get() + "|B";

var results = ScopedValuePatterns.concurrentWithContext("alice", "trace-xyz", taskA, taskB);

// Both virtual threads see the scoped values
assertThat(results).containsExactlyInAnyOrder("alice|A", "trace-xyz|B");
```

| Key | Value |
| --- | --- |
| `Thread Type` | `Virtual threads` |
| `Inheritance` | `Automatic` |
| `Task B Result` | `trace-xyz|B` |
| `Task A Result` | `alice|A` |
| `Parent Scope` | `user=alice, trace=trace-xyz` |

> [!NOTE]
> This automatic inheritance makes distributed tracing and authentication context propagation trivial. No more passing context through every method signature!

## Accessing Unbound Scoped Values

Attempting to get() an unbound ScopedValue throws NoSuchElementException. This is a feature, not a bug — it ensures that missing context is detected immediately rather silently using defaults.

```java
// Throws NoSuchElementException if not bound
assertThatThrownBy(ScopedValuePatterns::currentUser)
    .isInstanceOf(NoSuchElementException.class);

// Use isBound() to check safely
if (ScopedValuePatterns.CURRENT_USER.isBound()) {
    String user = ScopedValuePatterns.currentUser();
}
```

| Key | Value |
| --- | --- |
| `Design Philosophy` | `Fail fast, explicit context` |
| `Unbound Access` | `Throws NoSuchElementException` |
| `Alternative` | `Optional wrapper or default` |
| `Safe Check` | `isBound() returns false` |

> [!NOTE]
> The fail-fast behavior prevents bugs where context is accidentally missing. Always use isBound() or Optional wrappers when the value might not be set.

## Nested Scoped Values and Shadowing

Inner scopes can shadow outer bindings, similar to lexical variable scoping. The outer binding is temporarily hidden and automatically restored when the inner scope exits.

```java
// Outer scope: bound to "alice"
ScopedValuePatterns.handleAsUser("alice", () -> {
    String outer = ScopedValuePatterns.currentUser(); // "alice"

    // Inner scope: shadows with "SYSTEM"
    ScopedValuePatterns.runAsSystem(() -> {
        String inner = ScopedValuePatterns.currentUser(); // "SYSTEM"
    });

    String restored = ScopedValuePatterns.currentUser(); // "alice" again
});
```

| Key | Value |
| --- | --- |
| `Inner Scope` | `SYSTEM (shadows outer)` |
| `Pattern` | `Lexical scoping` |
| `Outer Scope` | `alice` |
| `After Inner Scope` | `alice (restored)` |

> [!NOTE]
> This shadowing behavior is perfect for privilege escalation (admin vs user) or request context switching in multi-tenant systems.

## Safe Scoped Value Access Patterns

When scoped values might be unbound, use safe access patterns: isBound() checks, Optional wrappers, or default values. Avoid NoSuchElementException in production code.

```java
// Pattern 1: Default value
String user = ScopedValuePatterns.currentUserOrDefault("anonymous");

// Pattern 2: Optional wrapper
Optional<String> user = ScopedValuePatterns.currentUserOptional();

// Pattern 3: Explicit check
if (ScopedValuePatterns.CURRENT_USER.isBound()) {
    String user = ScopedValuePatterns.currentUser();
}
```

| Key | Value |
| --- | --- |
| `Recommendation` | `Use Optional for explicit handling` |
| `Check Pattern` | `isBound() returns false` |
| `Default Pattern` | `Returns 'default' when unbound` |
| `Optional Pattern` | `Returns Optional.empty()` |

> [!NOTE]
> The Optional pattern is most explicit and forces callers to handle the missing case. Default values can hide bugs where context should have been set but wasn't.

## Multiple Scoped Values Bound Together

Real-world applications often need multiple context values: user, trace ID, tenant, locale, etc. ScopedValue supports binding multiple values in a single scope using ScopedValue.where().where().call().

```java
// Bind multiple values together
var ctx = new RequestContext("user-1", "trace-1", "tenant-1");
ScopedValuePatterns.withRequestContext(ctx, () -> {
    String user = ScopedValuePatterns.CURRENT_USER.get();
    String trace = ScopedValuePatterns.TRACE_ID.get();
    String tenant = ScopedValuePatterns.TENANT_ID.get();
    // All three values are bound
});
```

| Key | Value |
| --- | --- |
| `Scope` | `All values share same lifetime` |
| `Binding Pattern` | `Chained where().where().call()` |
| `Tenant ID` | `tenant-1` |
| `Trace ID` | `trace-1` |
| `User` | `user-1` |

> [!NOTE]
> The chained where() calls create a composite scope with all bindings. All values are unbound together when the scope exits, ensuring consistency.

## Java 26 ScopedValue: Immutable Context Passing

ScopedValue provides a way to pass context through call stacks without explicit parameter passing. Unlike ThreadLocal, it's immutable and automatically cleaned up when the scope exits.

| Feature | Cleanup | Inheritance |
| --- | --- | --- |
| ThreadLocal | Manual (memory leak risk) | InheritableThreadLocal needed |
| ScopedValue | Automatic (scope-bound) | Automatic inheritance |
| Mutability | Virtual Threads | Type Safety |
| Mutable (racy) | High overhead | Runtime type checking |
| Immutable (safe) | Zero overhead | Compile-time generics |

```java
// Define a scoped value (usually as a static final field)
static final ScopedValue<String> CURRENT_USER = ScopedValue.newInstance();

// Bind a value for the duration of a lambda
ScopedValuePatterns.handleAsUser("alice", () -> {
    String user = ScopedValuePatterns.currentUser();
    // user = "alice" within this scope
});

// Value is automatically unbound after lambda completes
```

| Key | Value |
| --- | --- |
| `Captured Value` | `alice` |
| `Cleanup` | `Automatic` |
| `Scoped Binding` | `alice` |
| `Scope` | `Lambda lifetime` |

> [!NOTE]
> ScopedValue.where(key, value).run(task) binds the value for the duration of task. After task completes (successfully or exceptionally), the binding is automatically removed.

---
*Generated by [DTR](http://www.dtr.org)*
