# Pattern 13: Ask with Timeout

## Context

Most communication is fire-and-forget. But sometimes you need the answer. A REST endpoint needs to return the current vehicle state. A checkout service needs the inventory count before it can confirm. You need request-reply.

## Problem

How do you get a response from a process without blocking the entire system, and without waiting forever if the process is slow or dead?

## Solution

Use `ask()`. It sends the message and returns a `CompletableFuture<S>` that completes with the process's state after the message is processed. There are two overloads:

```java
public CompletableFuture<S> ask(M msg)

public CompletableFuture<S> ask(M msg, Duration timeout)
```

The first returns a future with no deadline. The second -- the one you should almost always use -- adds a timeout. If the process does not respond within the duration, the future completes exceptionally with a `TimeoutException`.

## REST Endpoint Querying a Vehicle

Here is the practical case. A fleet dashboard needs the current state of a vehicle:

```java
@GetMapping("/vehicles/{id}")
CompletableFuture<VehicleState> getVehicle(@PathVariable String id) {
    Proc<VehicleState, VehicleMsg> vehicle = fleet.get(id);
    return vehicle.ask(new GetState(), Duration.ofMillis(500));
}
```

The controller calls `ask()` with a 500-millisecond timeout. If the vehicle process responds in time, the future completes with the state and Spring returns it as JSON. If the process is overloaded or crashed, the `TimeoutException` propagates and Spring returns a 503.

## How Ask Works Internally

When you call `ask()`, the Proc creates an envelope with a `CompletableFuture` reply handle:

```java
public CompletableFuture<S> ask(M msg) {
    var future = new CompletableFuture<Object>();
    mailbox.add(new Envelope<>(msg, future));
    return future.thenApply(s -> (S) s);
}
```

The message loop processes the envelope like any other message. After the handler returns the new state, the loop checks if there is a reply handle and completes the future with that state. The timed version delegates to `CompletableFuture.orTimeout()`:

```java
public CompletableFuture<S> ask(M msg, Duration timeout) {
    return ask(msg).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
}
```

Clean and simple. No special reply channels. No correlation IDs. The future is the reply channel.

## Natural Backpressure

Here is the insight Armstrong kept repeating: an unbounded call is a latent deadlock. Every call must have a timeout.

The timeout on `ask()` creates natural backpressure. If a downstream process is slow, callers time out instead of piling up. Consider a payment gateway:

```java
CompletableFuture<PaymentState> result = paymentProc.ask(
    new ChargeCard(amount),
    Duration.ofSeconds(2)
);
```

If the payment process can handle 200 requests per second but 1,000 arrive, 800 of them will time out after 2 seconds. The callers get a clear signal: the system is overloaded. They can retry, queue, or shed load. No explicit rate limiter needed -- the timeout is the rate limiter.

## Timeout Handling

Handle the timeout gracefully rather than letting it propagate as an unhandled exception:

```java
vehicle.ask(new GetState(), Duration.ofMillis(500))
    .thenApply(state -> ResponseEntity.ok(state))
    .exceptionally(ex -> {
        if (ex.getCause() instanceof TimeoutException) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.status(500).build();
    });
```

Or with `Result.of()` if you prefer railway-oriented error handling from Part 2.

## When to Ask vs. Tell

The decision is simple. Do you need the answer right now to proceed? Use `ask()`. Everything else? Use `tell()`. In a well-designed system, `ask()` calls are rare -- mostly at the edges where synchronous protocols (HTTP, gRPC) meet your asynchronous internals.

## Therefore

Use `ask(msg, Duration)` when you need a response, and always set a timeout. The returned `CompletableFuture<S>` completes with the process state or fails with `TimeoutException`. This provides natural backpressure: slow processes cause callers to time out rather than pile up.
