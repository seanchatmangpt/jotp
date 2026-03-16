# Concurrent Pipelines and Parallel Patterns

## Overview

Sequential processing is simple but slow. Concurrent pipelines let you:
- **Fan-out:** Distribute work to multiple workers in parallel
- **Fan-in:** Collect results from multiple workers
- **Parallel:** Run independent tasks concurrently and wait for all

JOTP provides the `Parallel` utility for structured parallelism, ensuring fail-fast semantics and proper resource cleanup.

## The Parallel Primitive

`Parallel` wraps Java's `StructuredTaskScope` for OTP-style parallelism:

```java
import io.github.seanchatmangpt.jotp.*;

sealed interface Task {
    record Fetch(String url) implements Task {}
    record Parse(String data) implements Task {}
    record Store(String key, String value) implements Task {}
}

// Run three tasks in parallel, wait for all, or fail-fast on any exception
var result = Parallel.run(
    () -> {
        // Task 1: Fetch data
        var fetcher = Proc.spawn("fetcher", (state, msg) -> state);
        return fetcher.ask(new Task.Fetch("http://example.com"), 5000);
    },
    () -> {
        // Task 2: Parse data
        var parser = Proc.spawn("parser", (state, msg) -> state);
        return parser.ask(new Task.Parse(rawData), 5000);
    },
    () -> {
        // Task 3: Store result
        var storage = Proc.spawn("storage", (state, msg) -> state);
        return storage.ask(new Task.Store("key", parsedData), 5000);
    }
);

// All three complete, or all are cancelled on first failure
System.out.println("All tasks done: " + result);
```

## Fan-Out Pattern: Distribute Work

Spawn multiple workers and send each one a task:

```java
sealed interface WorkerMsg {
    record Task(int jobId, String data) implements WorkerMsg {}
}

sealed interface ControllerMsg {
    record Process(List<String> items) implements ControllerMsg {}
    record WorkerDone(int workerId, String result) implements ControllerMsg {}
}

record ControllerState(
    List<Proc<?, WorkerMsg>> workers,
    Map<Integer, String> results
) {}

var controller = Proc.spawn(
    new ControllerState(List.of(), Map.of()),
    (state, msg) -> switch (msg) {
        case ControllerMsg.Process(var items) -> {
            // Spawn workers (fan-out)
            var workers = new ArrayList<Proc<?, WorkerMsg>>();
            for (int i = 0; i < items.size(); i++) {
                var worker = Proc.spawn(
                    "processing",
                    (workerState, workerMsg) -> switch (workerMsg) {
                        case WorkerMsg.Task(var jobId, var data) -> {
                            // Process work
                            System.out.println("Worker processing: " + data);
                            var result = processData(data);

                            // Send result back (fan-in)
                            sender().tell(new ControllerMsg.WorkerDone(jobId, result));
                            return workerState;
                        }
                        default -> workerState;
                    }
                );
                workers.add(worker);
            }

            // Distribute work to workers
            for (int i = 0; i < items.size(); i++) {
                workers.get(i).tell(new WorkerMsg.Task(i, items.get(i)));
            }

            return state.withWorkers(workers);
        }

        case ControllerMsg.WorkerDone(var workerId, var result) -> {
            // Collect results (fan-in)
            var newResults = new HashMap<>(state.results);
            newResults.put(workerId, result);

            if (newResults.size() == state.workers.size()) {
                System.out.println("All workers done: " + newResults);
            }

            return state.withResults(newResults);
        }

        default -> state;
    }
);

controller.tell(new ControllerMsg.Process(
    List.of("data1", "data2", "data3", "data4")
));
```

## Pipeline Pattern: Sequential Processing in Parallel

Chain processes where each stage receives output from the previous:

```
Input → Stage1 → Stage2 → Stage3 → Output
  ↓       ↓       ↓       ↓
 20%    40%     60%     80%    100%
```

```java
sealed interface PipelineMsg {
    record Data(String input) implements PipelineMsg {}
}

// Stage 1: Validate
var validate = Proc.spawn("validate", (state, msg) -> switch (msg) {
    case PipelineMsg.Data(var input) -> {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Empty input");
        }
        // Forward to stage 2
        stage2.tell(new PipelineMsg.Data(input.toUpperCase()));
        return state;
    }
    default -> state;
});

// Stage 2: Parse
var parse = Proc.spawn("parse", (state, msg) -> switch (msg) {
    case PipelineMsg.Data(var input) -> {
        var parsed = input.split(",");
        // Forward to stage 3
        stage3.tell(new PipelineMsg.Data(String.join("|", parsed)));
        return state;
    }
    default -> state;
});

// Stage 3: Transform
var transform = Proc.spawn("transform", (state, msg) -> switch (msg) {
    case PipelineMsg.Data(var input) -> {
        var result = "[" + input + "]";
        System.out.println("Pipeline output: " + result);
        return state;
    }
    default -> state;
});

// Send data through pipeline
validate.tell(new PipelineMsg.Data("hello,world"));
// Output: Pipeline output: [HELLO|WORLD]
```

## Backpressure Pattern: Rate Limiting

Too many messages can overwhelm workers. Use backpressure (ask-based flow control):

```java
sealed interface TaskMsg {
    record Do(String work) implements TaskMsg {}
}

var worker = Proc.spawn(
    new WorkerState(),
    (state, msg) -> switch (msg) {
        case TaskMsg.Do(var work) -> {
            System.out.println("Processing: " + work);
            Thread.sleep(1000);  // Simulate slow work
            return state;
        }
        default -> state;
    }
);

// Submit tasks with backpressure
for (int i = 0; i < 100; i++) {
    // Use ask() instead of tell() — waits for worker to finish
    worker.ask(new TaskMsg.Do("task-" + i), 10000).join();
    System.out.println("Task " + i + " complete");
}
// Only 1 task is processed at a time — backpressure prevents queue explosion
```

## Scatter-Gather Pattern: Map-Reduce Style

Collect many partial results and aggregate:

```java
sealed interface MapMsg {
    record Chunk(int id, List<Integer> data) implements MapMsg {}
}

sealed interface ReduceMsg {
    record Partial(int id, int sum) implements ReduceMsg {}
}

// Mapper: sums a chunk
var mapper = Proc.spawn("mapper", (state, msg) -> switch (msg) {
    case MapMsg.Chunk(var id, var data) -> {
        var sum = data.stream().mapToInt(Integer::intValue).sum();
        // Send to reducer
        reducer.tell(new ReduceMsg.Partial(id, sum));
        return state;
    }
    default -> state;
});

// Reducer: collects partial results
record ReducerState(Map<Integer, Integer> partials) {}

var reducer = Proc.spawn(
    new ReducerState(Map.of()),
    (state, msg) -> switch (msg) {
        case ReduceMsg.Partial(var id, var sum) -> {
            var newPartials = new HashMap<>(state.partials);
            newPartials.put(id, sum);

            if (newPartials.size() == NUM_CHUNKS) {
                var total = newPartials.values().stream()
                    .mapToInt(Integer::intValue).sum();
                System.out.println("Total: " + total);
            }

            return new ReducerState(newPartials);
        }
        default -> state;
    }
);

// Send chunks to mapper
for (int i = 0; i < NUM_CHUNKS; i++) {
    mapper.tell(new MapMsg.Chunk(i, chunk(i)));
}
```

## Fail-Fast with Parallel

The `Parallel` utility fails immediately if any task fails:

```java
try {
    var results = Parallel.run(
        () -> task1(),  // succeeds
        () -> task2(),  // throws exception
        () -> task3()   // cancelled if task2 fails
    );
} catch (Exception e) {
    System.out.println("At least one task failed: " + e);
    // task3 was cancelled, resources cleaned up
}
```

## Common Patterns

### Pattern: Load Balancer

Distribute incoming requests to multiple workers:

```java
sealed interface Request { record Req(String id) implements Request {} }

var workers = new ArrayList<Proc<?, Request>>();
for (int i = 0; i < 5; i++) {
    workers.add(Proc.spawn("worker-" + i, (state, msg) -> state));
}

var loadBalancer = Proc.spawn(
    new LoadBalancerState(workers, 0),
    (state, msg) -> switch (msg) {
        case Request req -> {
            // Round-robin
            var worker = workers.get(state.nextIndex % workers.size());
            worker.tell(req);
            return state.withNextIndex(state.nextIndex + 1);
        }
        default -> state;
    }
);
```

### Pattern: Timeout on Parallel

Use `Parallel.run()` with a timeout:

```java
var results = Parallel.run(
    () -> withTimeout(task1, 5000),
    () -> withTimeout(task2, 5000),
    () -> withTimeout(task3, 5000)
);

static <T> T withTimeout(Callable<T> task, long ms) throws Exception {
    return task.call();  // Use your own timeout mechanism
}
```

## Troubleshooting

**Q: My pipeline is slow — tasks are running sequentially.**
A: Check if you're using `ask()` (blocking) instead of `tell()` (non-blocking).
- Use `tell()` to send data downstream without waiting
- Use `ask()` only when you need a response

**Q: Parallel.run() canceled my task even though it completed.**
A: `Parallel.run()` waits for ALL tasks. If one fails, others are cancelled. Use:
```java
try {
    // This throws if ANY task fails
    Parallel.run(...);
} catch (Exception e) {
    // Handle partial failures
}
```

**Q: I have too many messages queued in my worker.**
A: Use backpressure: replace `tell()` with `ask()` to wait for worker to be ready:
```java
worker.ask(message, 10000).join();  // Blocks until worker processes it
```

## Next Steps

- **[Creating Your First Process](creating-your-first-process.md)** — Process basics
- **[State Machine Workflow](state-machine-workflow.md)** — Complex workflows
- **API Reference** — `Parallel`, `Proc.tell()`, `Proc.ask()`
