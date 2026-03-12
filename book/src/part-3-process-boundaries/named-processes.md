# Pattern 15: Named Processes

## Context

You have a fleet coordinator process that other parts of the system need to reach. You could pass the `Proc` reference through every constructor, every factory, every configuration object. Or you could just give it a name and let anyone look it up.

## Problem

How do processes find each other without threading references through the entire call stack?

## Solution

Use `ProcRegistry`. It is a global, JVM-scoped name table -- the Java equivalent of Erlang's `register/2` and `whereis/1`:

```java
public final class ProcRegistry {

    public static void register(String name, Proc<?, ?> proc)
    public static <S, M> Optional<Proc<S, M>> whereis(String name)
    public static void unregister(String name)
    public static Set<String> registered()
    public static void reset()
}
```

Register a process under a name. Look it up from anywhere. That is the entire API.

## Registering a Process

```java
var coordinator = new Proc<>(FleetState.empty(), fleetHandler);
ProcRegistry.register("fleet-coordinator", coordinator);
```

The name must be unique. If you try to register a name that is already taken by a living process, `register()` throws `IllegalStateException`. This is intentional -- duplicate names indicate a design problem, not a runtime condition to handle silently.

## Looking Up a Process

From anywhere in the JVM -- a REST controller, a background job, another process:

```java
Optional<Proc<FleetState, FleetMsg>> coordinator = ProcRegistry.whereis("fleet-coordinator");

coordinator.ifPresent(proc -> proc.tell(new VehicleConnected(vehicleId)));
```

`whereis()` returns an `Optional`. If the name is not registered, or the process has died, you get `Optional.empty()`. The registry eagerly removes dead processes: if the underlying thread is no longer alive, `whereis()` cleans up the stale entry and returns empty.

## Auto-Deregistration

This is the feature that makes the registry safe to use in production. When you call `register()`, the registry attaches a termination callback to the process. When the process dies -- for any reason, normal or abnormal -- the name is automatically removed:

```java
// Inside ProcRegistry.register():
proc.addTerminationCallback(_ -> REGISTRY.remove(name, proc));
```

You never need to manually clean up. A crashed process disappears from the registry. A gracefully stopped process disappears from the registry. The name becomes available for re-registration immediately.

## Listing Registered Names

For debugging and monitoring:

```java
Set<String> names = ProcRegistry.registered();
// Returns: {"fleet-coordinator", "alert-service", "metrics-collector"}
```

This returns a snapshot -- a copy of the key set at the moment of the call. It does not change if processes register or deregister after the call returns.

## When to Use Named Processes

Name a process when it is a singleton service that other parts of the system need to find. Fleet coordinator, alert service, metrics collector -- these are good candidates. They exist once per JVM and many components need to reach them.

Do not name individual entity processes. You would not register "vehicle-42" in the global registry -- you would look it up in a fleet map. The registry is for well-known services, not for entity instances.

```java
// Good: singleton services
ProcRegistry.register("fleet-coordinator", coordinator);
ProcRegistry.register("alert-service", alertProc);

// Bad: entity instances (use a Map instead)
ProcRegistry.register("vehicle-42", vehicle42);  // Don't do this
```

## Testing with reset()

The registry is global state, which means tests can interfere with each other. Call `reset()` in your test teardown:

```java
@AfterEach
void cleanup() {
    ProcRegistry.reset();
}
```

This clears all registrations. It is for tests only -- never call it in production code.

## Therefore

Use `ProcRegistry.register()` for singleton service processes that need to be found by name. Use `whereis()` to look them up. Trust auto-deregistration to clean up after crashes. Keep entity processes in domain-specific maps instead.
