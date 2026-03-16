# Pattern 29: Assemble the Application

## The Problem

You have all the pieces. Processes, supervisors, state machines, event managers, timers, registries. But pieces on a workbench are not a running system. How do you wire them together into an application that starts up, runs, handles failures, and shuts down cleanly?

## The Solution

An OTP application is a supervision tree with an event bus and a name registry. The root supervisor owns everything. Region supervisors own groups of workers. The event manager connects services without coupling them. The registry lets services find each other by name.

## FleetPulse Assembly

Here is the complete wiring for a fleet tracking application:

```java
public class FleetPulseApp {

    public static void main(String[] args) throws InterruptedException {
        // 1. Event bus -- decoupled pub-sub for fleet events
        EventManager<FleetEvent> eventBus = EventManager.start("fleet-events");

        // 2. Root supervisor -- owns everything
        var root = Supervisor.create(
            "fleet-root",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofSeconds(60)
        );

        // 3. Alert service -- listens for events, sends notifications
        var alertRef = root.supervise(
            "alert-service",
            new AlertState(List.of()),
            (state, msg) -> switch (msg) {
                case ProcessAlert(var vehicleId, var message) -> {
                    notifyOpsTeam(vehicleId, message);
                    yield state.withAlert(vehicleId, message);
                }
            }
        );

        // 4. Register alert handler on the event bus
        eventBus.addHandler(new EventManager.Handler<>() {
            public void handleEvent(FleetEvent event) {
                if (event instanceof VehicleAlert a) {
                    alertRef.tell(new ProcessAlert(a.vehicleId(), a.message()));
                }
            }
        });

        // 5. Region supervisor -- groups vehicles by region
        var westRegion = Supervisor.create(
            "region-west",
            Supervisor.Strategy.ONE_FOR_ONE,
            10,
            Duration.ofSeconds(60)
        );

        // 6. Supervised vehicle trackers
        for (String vehicleId : List.of("V-1001", "V-1002", "V-1003")) {
            var ref = westRegion.supervise(
                vehicleId,
                new TrackerState(vehicleId, 0, System.currentTimeMillis()),
                (state, msg) -> switch (msg) {
                    case GpsUpdate(var lat, var lon, var ts) ->
                        state.withTimestamp(ts);
                    case Heartbeat() -> {
                        long elapsed = System.currentTimeMillis() - state.lastTimestamp();
                        if (elapsed > 30_000) {
                            eventBus.notify(new VehicleAlert(
                                state.vehicleId(), "GPS silent"));
                        }
                        yield state;
                    }
                }
            );

            // 7. Register each vehicle by name
            ProcRegistry.register(vehicleId, ref.proc());

            // 8. Start heartbeat timer
            ProcTimer.sendInterval(10_000, ref.proc(), new Heartbeat());
        }

        // Application is running. Supervisor trees handle failures.
        // Ctrl+C or shutdown hook triggers cleanup.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                eventBus.stop();
                westRegion.shutdown();
                root.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }
}
```

## Spring Boot Integration

In a Spring Boot application, expose JOTP components as beans:

```java
@Configuration
public class FleetConfig {

    @Bean
    EventManager<FleetEvent> fleetEventBus() {
        return EventManager.start("fleet-events");
    }

    @Bean
    Supervisor rootSupervisor() {
        return Supervisor.create(
            "fleet-root",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofSeconds(60)
        );
    }

    @Bean
    ProcRef<AlertState, AlertMsg> alertService(
            Supervisor rootSupervisor,
            EventManager<FleetEvent> eventBus) {
        var ref = rootSupervisor.supervise(
            "alert-service", AlertState.INIT, AlertService::handle);
        eventBus.addHandler(new AlertEventHandler(ref));
        return ref;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        fleetEventBus().stop();
        rootSupervisor().shutdown();
    }
}
```

Spring manages the bean lifecycle. JOTP manages the process lifecycle. The supervisor restarts crashed workers. Spring does not need to know about restarts -- the `ProcRef` handles that transparently.

## The Shape of It

The assembled application forms a tree:

```
Root Supervisor (ONE_FOR_ONE)
├── alert-service (Proc, registered)
├── region-west (Supervisor, ONE_FOR_ONE)
│   ├── V-1001 (Proc, registered, heartbeat timer)
│   ├── V-1002 (Proc, registered, heartbeat timer)
│   └── V-1003 (Proc, registered, heartbeat timer)
└── fleet-events (EventManager, registered)
    ├── AlertEventHandler
    └── AnalyticsEventHandler
```

Failures propagate up. Restarts propagate down. Events flow sideways. Names provide discovery. This is the OTP architecture, running on the JVM.
