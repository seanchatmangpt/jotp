# Pattern 27: Fan-Out with Fail-Fast

## The Problem

Your fleet dashboard needs to display the status of 50 vehicles. Each status query takes 20-200 ms depending on network conditions. Querying them sequentially takes up to 10 seconds. Querying them in parallel should take only as long as the slowest one -- about 200 ms. But what if vehicle #17 is unreachable and throws an exception? You do not want to wait for all 49 remaining queries to finish before reporting the error.

## The Solution

`Parallel.all` runs a list of tasks concurrently on virtual threads with fail-fast semantics. If every task succeeds, you get back a `Result.Success` containing all results in fork order. If any task fails, the scope cancels all remaining tasks immediately and you get back a `Result.Failure` with the first exception.

```java
Result<List<T>, Exception> result = Parallel.all(tasks);
```

That is the entire API. One method.

## Dashboard Query Example

Build a list of suppliers, one per vehicle:

```java
List<Supplier<VehicleStatus>> queries = vehicleIds.stream()
    .<Supplier<VehicleStatus>>map(id -> () -> queryVehicleStatus(id))
    .toList();

Result<List<VehicleStatus>, Exception> result = Parallel.all(queries);
```

Handle the result with pattern matching:

```java
switch (result) {
    case Result.Success<List<VehicleStatus>, Exception>(var statuses) -> {
        dashboard.updateAll(statuses);
        log.info("Refreshed {} vehicles", statuses.size());
    }
    case Result.Failure<List<VehicleStatus>, Exception>(var ex) -> {
        dashboard.showError("Fleet query failed: " + ex.getMessage());
        log.warn("Parallel query failed", ex);
    }
}
```

If `queryVehicleStatus("V-0017")` throws a `ConnectException`, the scope cancels the remaining 49 queries immediately. You get the failure in milliseconds, not after waiting for all tasks to complete.

## Under the Hood

`Parallel.all` uses Java 26's `StructuredTaskScope` with the `awaitAllSuccessfulOrThrow` joiner:

```java
try (var scope = StructuredTaskScope.open(
        StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {
    var subtasks = tasks.stream()
        .map(t -> scope.fork(t::get))
        .toList();
    scope.join();
    return Result.success(
        subtasks.stream().map(Subtask::get).toList());
} catch (Exception e) {
    return Result.failure(e);
}
```

Each task runs on its own virtual thread. The scope ensures that all threads are joined before `all` returns -- no orphaned threads, no leaked resources. The `try-with-resources` guarantees cleanup even if something goes catastrophically wrong.

## Combining with Supervision

`Parallel.all` is for short-lived fan-out within a request. It is not a replacement for long-lived supervised processes. A common pattern is a supervised process that uses `Parallel.all` to handle individual requests:

```java
var dashboardRef = supervisor.supervise(
    "dashboard",
    DashboardState.INIT,
    (state, msg) -> switch (msg) {
        case RefreshAll _ -> {
            var queries = state.vehicleIds().stream()
                .<Supplier<VehicleStatus>>map(id -> () -> queryVehicleStatus(id))
                .toList();
            var result = Parallel.all(queries);
            yield switch (result) {
                case Result.Success(var statuses) ->
                    state.withStatuses(statuses);
                case Result.Failure(var ex) ->
                    state.withLastError(ex.getMessage());
            };
        }
    }
);
```

The supervisor handles the long-lived lifecycle. `Parallel.all` handles the short-lived fan-out. Each tool does what it is good at.

## When Not to Use This

If you need partial results -- "give me whatever succeeded" -- `Parallel.all` is not the right choice. Its semantics are all-or-nothing. For partial-success scenarios, use individual `CompletableFuture` instances or build a custom joiner. The fail-fast behavior is a feature, not a limitation, when you genuinely need all results to proceed.

## Performance

With 50 tasks, `Parallel.all` spawns 50 virtual threads. Each costs about 1 KB. Total overhead: ~50 KB plus the scope bookkeeping. The wall-clock time equals the slowest successful task (or the time until the first failure). On an 8-core machine with I/O-bound tasks, this scales to thousands of concurrent fan-outs without issue.
