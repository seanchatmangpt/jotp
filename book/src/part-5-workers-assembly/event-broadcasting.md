# Pattern 25: Event Broadcasting

## The Problem

Your fleet tracker needs to notify multiple subsystems when something happens. An alert service, an analytics pipeline, a maintenance scheduler -- they all care about the same events but for different reasons. Hardcoding those dependencies means every new subscriber requires changing the publisher. And if the analytics handler throws an exception, you definitely do not want it to take down the alert service.

## The Solution

`EventManager<E>` decouples event producers from consumers. The producer calls `notify`; it has no idea who is listening. Handlers register themselves independently and can be added or removed at any time.

Create a manager with `EventManager.start()`:

```java
EventManager<FleetEvent> events = EventManager.start();
```

Register handlers that implement the `Handler<E>` interface:

```java
public interface Handler<E> {
    void handleEvent(E event);
    default void terminate(Throwable reason) {}
}
```

The `handleEvent` method receives every broadcast event. The `terminate` method is called when the handler is removed -- either explicitly via `deleteHandler` or because it crashed. If `reason` is null, it was a clean removal. If non-null, the handler threw an exception and was evicted.

## Fleet Events Example

Define your event type:

```java
sealed interface FleetEvent permits VehicleAlert, TripCompleted, MaintenanceDue {}
record VehicleAlert(String vehicleId, String message) implements FleetEvent {}
record TripCompleted(String vehicleId, int miles) implements FleetEvent {}
record MaintenanceDue(String vehicleId, int milesSinceService) implements FleetEvent {}
```

Create three independent handlers:

```java
var alertHandler = new EventManager.Handler<FleetEvent>() {
    public void handleEvent(FleetEvent event) {
        if (event instanceof VehicleAlert a) {
            notificationService.sendAlert(a.vehicleId(), a.message());
        }
    }
};

var analyticsHandler = new EventManager.Handler<FleetEvent>() {
    public void handleEvent(FleetEvent event) {
        if (event instanceof TripCompleted t) {
            metricsDb.recordTrip(t.vehicleId(), t.miles());
        }
    }
};

var maintenanceHandler = new EventManager.Handler<FleetEvent>() {
    public void handleEvent(FleetEvent event) {
        if (event instanceof MaintenanceDue m) {
            workOrderSystem.schedule(m.vehicleId());
        }
    }
};
```

Wire them up:

```java
EventManager<FleetEvent> events = EventManager.start();
events.addHandler(alertHandler);
events.addHandler(analyticsHandler);
events.addHandler(maintenanceHandler);
```

Now any service can publish without knowing who listens:

```java
events.notify(new VehicleAlert("V-1001", "Engine temperature high"));
events.notify(new TripCompleted("V-1001", 47));
```

The `notify` call is asynchronous -- it enqueues the event and returns immediately. If you need confirmation that all handlers have processed the event, use `syncNotify`:

```java
events.syncNotify(new MaintenanceDue("V-1001", 15000));
// Returns only after all handlers have processed the event
```

## Fault Isolation

Here is the critical property: if `analyticsHandler` throws a `RuntimeException`, the EventManager catches it, calls `analyticsHandler.terminate(theException)`, removes that handler from the list, and keeps going. The `alertHandler` and `maintenanceHandler` continue receiving events as if nothing happened.

This is exactly how OTP's `gen_event` works. A crashing handler is evicted, not propagated.

## Lifecycle

Remove a handler explicitly with `deleteHandler`:

```java
boolean removed = events.deleteHandler(analyticsHandler);
// analyticsHandler.terminate(null) is called
```

Shut down the entire manager with `stop`:

```java
events.stop();
// terminate(null) called on every remaining handler, then the manager process stops
```

## When to Use This

Use `EventManager` when you have a one-to-many relationship between event source and event consumers, and you want to add or remove consumers without touching the source. Audit logging, metrics collection, notification fanout -- anywhere the producer should not know about the consumers.

Do not use it for request-reply. That is what `Proc.ask` is for.
