# Pattern 9: Test Without a Framework

## Context

You have written pure state handlers following Pattern 6. Now you need to test them. Your team reaches for `@SpringBootTest` and `@MockBean` out of habit.

## Problem

Framework-heavy tests are slow. A `@SpringBootTest` boots an application context, scans components, initializes connection pools, and configures embedded databases -- all to test a function that takes a state and a message and returns a new state. The test takes 3 seconds to start and 2 milliseconds to run. That ratio is backwards.

Mocks make it worse. `@MockBean` replaces real behavior with scripted responses. Your test passes, but it is testing the mock configuration, not the logic. When the real service changes its contract, the mock keeps returning the old answer and the test stays green while production breaks.

## Therefore

Pure functions need only assertions. Call the function. Check the result. That is the whole test.

JOTP tests use AssertJ via the `WithAssertions` interface and jqwik for property-based testing. No DI container. No mocks. No embedded anything.

### AssertJ with WithAssertions

Implement `WithAssertions` to get fluent assertions without static imports:

```java
class VehicleHandlerTest implements WithAssertions {

    BiFunction<VehicleState, TelemetryMsg, VehicleState> handler =
        (state, msg) -> switch (msg) {
            case GpsUpdate g -> Telemetry.applyGps(state, g);
            case FuelReading f -> Telemetry.applyFuel(state, f);
            case EngineAlert a -> Alerts.evaluate(state, a);
        };

    @Test
    void gpsUpdateChangesCoordinates() {
        var state = new VehicleState(0, 0, 0L, 1.0, List.of());
        var next = handler.apply(state, new GpsUpdate(40.7, -74.0, 1000L));

        assertThat(next.lat()).isEqualTo(40.7);
        assertThat(next.lng()).isEqualTo(-74.0);
        assertThat(next.fuelLevel()).isEqualTo(1.0);
    }
}
```

No context startup. No bean wiring. The test runs in under a millisecond. You get immediate feedback.

### Result assertions

Testing railway pipelines is equally direct:

```java
@Test
void validOrderProducesSuccess() {
    Result<Order, String> result = Result.of(() -> "order-42")
        .map(id -> new Order(id, 10, 99.99))
        .flatMap(order -> order.quantity() > 0
            ? Result.ok(order)
            : Result.err("invalid quantity"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.orElseThrow().quantity()).isEqualTo(10);
}

@Test
void invalidQuantityProducesError() {
    Result<Order, String> result = Result.of(() -> "order-42")
        .map(id -> new Order(id, 0, 99.99))
        .flatMap(order -> order.quantity() > 0
            ? Result.ok(order)
            : Result.err("invalid quantity"));

    assertThat(result.isError()).isTrue();
}
```

### Property-based testing with jqwik

Unit tests check specific examples. Property tests check invariants across thousands of random inputs. Use `@Property` and `@ForAll` to express rules your handler must always satisfy:

```java
@Property
void fuelReadingNeverChangesCoordinates(
        @ForAll @DoubleRange(min = 0.0, max = 1.0) double level,
        @ForAll double lat,
        @ForAll double lng) {
    var state = new VehicleState(lat, lng, 0L, 0.5, List.of());
    var next = handler.apply(state, new FuelReading(level));

    assertThat(next.lat()).isEqualTo(lat);
    assertThat(next.lng()).isEqualTo(lng);
    assertThat(next.fuelLevel()).isEqualTo(level);
}
```

jqwik generates hundreds of random doubles and verifies that fuel readings never corrupt GPS coordinates. This catches edge cases -- NaN, negative zero, MAX_VALUE -- that you would never write by hand.

### Awaitility for async boundaries

When you do need to test the `Proc` itself -- the imperative shell -- use Awaitility instead of `Thread.sleep`:

```java
var proc = new Proc<>(0, handler);
proc.tell(new Increment());

await().atMost(Duration.ofSeconds(3))
    .until(() -> proc.ask(new Get()).get(1, TimeUnit.SECONDS) == 1);

proc.stop();
```

But notice: the handler logic was already tested without any of this. Awaitility is only needed at the boundary where messages enter the mailbox.

## Consequences

Tests for pure handlers run in milliseconds. A suite of 200 handler tests completes in under a second. You run them on every save. You get feedback before you finish reaching for your coffee. And because there are no mocks, when a test passes, you know the logic is correct -- not just that the mock was configured correctly.
