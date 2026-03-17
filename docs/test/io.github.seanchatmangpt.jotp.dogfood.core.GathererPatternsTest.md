# io.github.seanchatmangpt.jotp.dogfood.core.GathererPatternsTest

## Table of Contents

- [Sliding Window Operations](#slidingwindowoperations)
- [Custom Gatherer: Deduplication](#customgathererdeduplication)
- [Running Scan (Prefix Sums)](#runningscanprefixsums)
- [Gatherer API: Custom Stream Intermediate Operations](#gathererapicustomstreamintermediateoperations)
- [Chaining Multiple Gatherers](#chainingmultiplegatherers)
- [Fold as Intermediate Operation](#foldasintermediateoperation)
- [Concurrent Mapping with Gatherers](#concurrentmappingwithgatherers)


## Sliding Window Operations

Sliding windows create overlapping sequences, useful for moving averages, time-series analysis, and pattern detection. Each window slides by one element.

```java
// Create sliding windows of size 3
var items = List.of(1, 2, 3, 4, 5);
var windows = GathererPatterns.slidingWindow(items, 3);

// Result: [[1, 2, 3], [2, 3, 4], [3, 4, 5]]
// Each window slides by 1 element
```

| Key | Value |
| --- | --- |
| `Overlap` | `2 elements between windows` |
| `Input` | `[1, 2, 3, 4, 5]` |
| `Output` | `3 windows` |
| `Window Size` | `3` |
| `Moving Average` | `[20.0, 30.0, 40.0]` |

> [!NOTE]
> Sliding windows are computationally expensive (O(n*k) for n elements and window size k). Use them judiciously on large datasets or consider windowed aggregations.

## Custom Gatherer: Deduplication

Gatherers can implement arbitrary stateful transformations. Here's a custom gatherer that removes consecutive duplicates while preserving non-consecutive ones.

```java
// Custom gatherer: deduplicate consecutive elements
var items = List.of(1, 1, 2, 2, 2, 3, 2, 2, 4);
var deduped = items.stream()
    .gather(GathererPatterns.deduplicateConsecutive())
    .toList();

// Result: [1, 2, 3, 2, 4]
// Consecutive duplicates removed, non-consecutive preserved
```

| Key | Value |
| --- | --- |
| `Input` | `[1, 1, 2, 2, 2, 3, 2, 2, 4]` |
| `All Same` | `[5, 5, 5, 5] → [5]` |
| `Output` | `[1, 2, 3, 2, 4]` |
| `Consecutive Duplicates` | `Removed` |
| `Non-Consecutive` | `Preserved (2 appears twice)` |

> [!NOTE]
> Custom gatherers maintain internal state (the previous element) to make decisions. This enables sophisticated transformations that would require manual loops or external libraries.

## Running Scan (Prefix Sums)

Scan operations (also called prefix sums) maintain running state across elements. Unlike reduce which returns a single value, scan returns an intermediate value for each input element.

```java
// Calculate running sum
var values = List.of(1, 2, 3, 4, 5);
var sums = GathererPatterns.runningSum(values);

// Result: [1, 3, 6, 10, 15]
// Each element is the sum of all previous elements
```

| Key | Value |
| --- | --- |
| `Input` | `[1, 2, 3, 4, 5]` |
| `Output` | `[1, 3, 6, 10, 15]` |
| `String Concat` | `[a, ab, abc]` |
| `Operation` | `Running sum` |
| `State` | `Accumulated across elements` |

> [!NOTE]
> Scan operations are perfect for cumulative calculations, running totals, and maintaining state across stream elements without manual loops.

## Gatherer API: Custom Stream Intermediate Operations

Java 26 introduces the Gatherer API, enabling custom stream intermediate operations. Unlike collectors (terminal operations), gatherers transform streams mid-pipeline, enabling batching, windowing, and other complex transformations.

| Aspect | Intermediate (middle) | Final result |
| --- | --- | --- |
| Collector | Operations | Per-element state |
| Gatherer | Reduction only | Use Cases |
| Pipeline Position | Transformation + reduction | sum, toList, grouping |
| Terminal (end) | State | batch, window, scan |

```java
// Batch elements into fixed-size groups
var items = List.of(1, 2, 3, 4, 5, 6, 7);
var batches = GathererPatterns.batch(items, 3);

// Result: [[1, 2, 3], [4, 5, 6], [7]]
// Last batch may be smaller
```

| Key | Value |
| --- | --- |
| `Input Size` | `7 elements` |
| `Batch Size` | `3` |
| `Output` | `3 batches` |
| `Last Batch` | `Partial (size 1)` |

> [!NOTE]
> Batching is essential for bulk operations (database inserts, API calls) where you want to process multiple items together. The Gatherer API makes this a one-liner instead of manual iteration.

## Chaining Multiple Gatherers

Gatherers can be chained like any stream operation. Each gatherer transforms the stream, passing results to the next. This enables complex pipelines.

```java
// Chain gatherers: dedupe → batch
var items = List.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
var result = GathererPatterns.batchAndDeduplicate(items, 2);

// Step 1: Dedupe → [1, 2, 3, 4, 5]
// Step 2: Batch → [[1, 2], [3, 4], [5]]
// Result: [[1, 2], [3, 4], [5]]
```

## Fold as Intermediate Operation

Fold is traditionally a terminal operation (Stream.reduce), but Gatherer enables fold as an intermediate operation. This allows folding to be part of a larger pipeline.

```java
// Fold to single value mid-stream
var items = List.of(1, 2, 3, 4, 5);
var sum = GathererPatterns.foldToSingle(items, 0, Integer::sum);

// Result: 15
// Can be chained with other operations
```

| Key | Value |
| --- | --- |
| `Identity` | `0 for sum, "" for concat` |
| `Operation` | `Intermediate (can chain)` |
| `String Fold` | `a+b+c = abc` |
| `Integer Fold` | `1+2+3+4+5 = 15` |

> [!NOTE]
> Fold as an intermediate operation enables complex pipelines: filter → fold → map. This was impossible with traditional Stream.reduce, which is terminal-only.

| Key | Value |
| --- | --- |
| `After Batch` | `[[1, 2], [3, 4], [5]]` |
| `Input` | `[1, 1, 2, 2, 3, 3, 4, 4, 5, 5]` |
| `After Dedupe` | `[1, 2, 3, 4, 5]` |
| `Pipeline` | `stream → dedupe → batch → toList` |

> [!NOTE]
> Gatherer chaining is composable and type-safe. Each gatherer is independent and can be reused in different pipelines. This is the essence of functional programming principles applied to streams.

## Concurrent Mapping with Gatherers

Gatherers can introduce parallelism mid-stream. The mapConcurrent gatherer processes elements with a fixed thread pool, combining the simplicity of streams with the performance of parallel execution.

```java
// Map with controlled parallelism
var items = List.of(1, 2, 3, 4, 5);
var result = GathererPatterns.mapConcurrent(items, 2, i -> i * 2);

// Result: [2, 4, 6, 8, 10]
// Uses 2 threads for mapping
// Order is preserved
```

| Key | Value |
| --- | --- |
| `Input` | `[1, 2, 3, 4, 5]` |
| `Order` | `Preserved` |
| `Parallelism` | `2 threads` |
| `Output` | `[2, 4, 6, 8, 10]` |
| `Operation` | `i → i * 2` |

> [!NOTE]
> Unlike parallel streams which use common ForkJoinPool, gatherers can use custom thread pools. This prevents resource contention and enables fine-tuned parallelism.

---
*Generated by [DTR](http://www.dtr.org)*
