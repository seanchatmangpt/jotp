# Pattern 23: Retry with Fresh State

## Context

Your FleetPulse system needs to send an alert to an external SMS API when a vehicle enters a geofenced zone. The API is unreliable -- it returns 500 errors about 5% of the time due to transient load spikes on the provider's side. You need to retry the call, but you want each attempt to be completely isolated from the previous one.

## Problem

The naive retry loop in Java reuses the same thread and the same local variables:

```java
for (int i = 0; i < 3; i++) {
    try {
        return smsClient.send(alert);
    } catch (IOException e) {
        // The smsClient may have corrupted internal state.
        // The HttpURLConnection buffer may be half-written.
        // Thread-local SSL context may be in a bad state.
        // We retry with all of that baggage.
        continue;
    }
}
```

Each retry carries forward whatever state the previous attempt left behind. If the HTTP client's connection pool has a poisoned socket, retry two hits the same socket. If a thread-local got set during the failed attempt, it is still set on retry three.

## Solution

`CrashRecovery.retry()` runs each attempt in a fresh virtual thread. No state carries over between attempts. Each attempt is a clean process with a clean stack, clean thread-locals, and no inherited corruption.

```java
Result<SmsResponse, Exception> result = CrashRecovery.retry(3, () -> {
    // Each attempt runs in a NEW virtual thread.
    // Fresh thread-locals. Fresh stack. No carried-over state.
    var client = SmsClient.create(apiKey);
    var response = client.send(new SmsAlert(
        vehicle.driverPhone(),
        "Vehicle " + vehicle.id() + " entered restricted zone"
    ));
    if (response.statusCode() >= 500) {
        throw new RuntimeException("SMS API returned " + response.statusCode());
    }
    return response;
});
```

The return type is `Result<T, Exception>` -- JOTP's railway type. Pattern match on it:

```java
switch (result) {
    case Result.Success(var response) ->
        logger.info("Alert sent, SMS ID: {}", response.messageId());
    case Result.Failure(var ex) ->
        logger.error("Alert failed after 3 attempts", ex);
}
```

If the first attempt throws, `CrashRecovery` discards that virtual thread entirely -- stack, locals, everything. Attempt two starts in a brand new virtual thread. If attempt two also fails, same thing. If all three fail, you get a `Failure` containing the last exception.

This is the "let it crash" philosophy (Pattern 17) applied to one-shot operations instead of long-running processes. Where a `Supervisor` restarts a crashed process with fresh state for the next *message*, `CrashRecovery.retry()` restarts a crashed operation with a fresh thread for the next *attempt*.

Here is how FleetPulse uses it in the alert pipeline:

```java
BiFunction<AlertState, AlertMsg, AlertState> alertHandler = (state, msg) ->
    switch (msg) {
        case GeofenceViolation(var vehicle, var zone) -> {
            // Retry the external API call with full isolation.
            var result = CrashRecovery.retry(3, () ->
                smsGateway.sendAlert(vehicle.driverPhone(),
                    "Entered zone: " + zone.name())
            );

            yield switch (result) {
                case Result.Success(var resp) ->
                    state.withDelivered(vehicle.id(), resp.messageId());
                case Result.Failure(var ex) ->
                    state.withFailed(vehicle.id(), ex.getMessage());
            };
        }
    };
```

The alert process itself does not crash -- it handles the `Result` and updates its state accordingly. `CrashRecovery.retry()` contains the failure within the retry boundary. The process stays alive; only the individual operation is retried.

## Forces

- Each attempt gets a fresh virtual thread. Virtual threads are cheap (about 1 KB), so the overhead of spawning three is negligible.
- `maxAttempts` must be at least 1. Passing 0 throws `IllegalArgumentException`.
- There is no delay between attempts. If you need backoff, add a `Thread.sleep()` inside the supplier. Each attempt has its own virtual thread, so sleeping does not block a platform thread.
- Use `CrashRecovery.retry()` for one-shot operations (API calls, file writes, network requests). Use `Supervisor` for long-running processes that need continuous monitoring.
- The `Result` return type composes with the rest of JOTP's railway-oriented error handling. You can `map()`, `flatMap()`, or pattern match on it.

## Therefore

When a one-shot operation might fail due to transient errors, use `CrashRecovery.retry(maxAttempts, supplier)`. Each attempt runs in an isolated virtual thread with no state carried over from previous failures. Handle the `Result` -- `Success` or `Failure` -- in the calling code. This gives you the "crash and restart with fresh state" guarantee of supervision, scoped to a single operation.
