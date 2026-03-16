package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrExtension;
import io.github.seanchatmangpt.jotp.EventManager;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies OTP {@code gen_event} semantics — decoupled event routing with fault isolation.
 *
 * <p>Armstrong: "gen_event is how OTP decouples event sources from event sinks. The error logger,
 * alarm handler, and SASL all use gen_event. Without it, you hardcode your event routing."
 *
 * <p>OTP invariants under test:
 *
 * <ol>
 *   <li>{@code notify} broadcasts to all registered handlers
 *   <li>Handlers added dynamically receive subsequent events
 *   <li>{@code deleteHandler} stops a handler from receiving events
 *   <li>A crashing handler is removed but does NOT kill the manager (fault isolation)
 *   <li>{@code call} delivers a sync event to one specific handler only
 * </ol>
 */
@ExtendWith(DtrExtension.class)
@Timeout(10)
class EventManagerTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    sealed interface AppEvent permits AppEvent.UserLogin, AppEvent.OrderPlaced {
        record UserLogin(String userId) implements AppEvent {}

        record OrderPlaced(String orderId, double amount) implements AppEvent {}
    }

    // ── Test 1: notify delivers to all handlers ────────────────────────────

    @Test
    void notify_deliversToAllHandlers() throws Exception {
        ctx.sayNextSection("EventManager: Broadcast to All Handlers");
        ctx.say(
                "EventManager implements OTP gen_event semantics. notify() broadcasts events to all registered handlers.");
        ctx.sayMermaid(
                """
            graph LR
                E[Event] --> EM[EventManager]
                EM --> H1[Handler 1]
                EM --> H2[Handler 2]
                EM --> Hn[Handler N]
                style H1 fill:#90EE90
                style H2 fill:#90EE90
                style Hn fill:#90EE90
                """);
        ctx.say(
                "When syncNotify() is called, the event is delivered to ALL registered handlers. Each handler processes independently.");
        ctx.sayCode(
                """
            var mgr = EventManager.<AppEvent>start();
            var counter1 = new AtomicInteger(0);
            var counter2 = new AtomicInteger(0);

            EventManager.Handler<AppEvent> h1 = event -> counter1.incrementAndGet();
            EventManager.Handler<AppEvent> h2 = event -> counter2.incrementAndGet();

            mgr.addHandler(h1);
            mgr.addHandler(h2);

            mgr.syncNotify(new AppEvent.UserLogin("alice"));
            mgr.syncNotify(new AppEvent.OrderPlaced("order-1", 99.99));

            // Both handlers received both events
            assertThat(counter1.get()).isEqualTo(2);
            assertThat(counter2.get()).isEqualTo(2);
            """,
                "java");

        var mgr = EventManager.<AppEvent>start();

        var counter1 = new AtomicInteger(0);
        var counter2 = new AtomicInteger(0);

        EventManager.Handler<AppEvent> h1 = event -> counter1.incrementAndGet();
        EventManager.Handler<AppEvent> h2 = event -> counter2.incrementAndGet();

        mgr.addHandler(h1);
        mgr.addHandler(h2);

        mgr.syncNotify(new AppEvent.UserLogin("alice"));
        mgr.syncNotify(new AppEvent.OrderPlaced("order-1", 99.99));

        assertThat(counter1.get()).isEqualTo(2);
        assertThat(counter2.get()).isEqualTo(2);

        ctx.sayKeyValue(
                Map.of(
                        "Handler 1 Invocations",
                        String.valueOf(counter1.get()),
                        "Handler 2 Invocations",
                        String.valueOf(counter2.get()),
                        "Events Broadcast",
                        "2"));
        mgr.stop();
    }

    // ── Test 2: handler added dynamically receives subsequent events ───────

    @Test
    void addHandler_dynamically_receivesSubsequentEvents() throws Exception {
        ctx.sayNextSection("EventManager: Dynamic Handler Registration");
        ctx.say(
                "Handlers can be added at runtime. Late-bound handlers only receive events sent after registration.");
        ctx.sayMermaid(
                """
            sequenceDiagram
                participant EM as EventManager
                participant H1 as Early Handler
                participant H2 as Late Handler

                EM->>H1: Event 1
                Note over H1: Received (registered)
                Note over H2: Not registered yet

                EM->>H1: Event 2
                EM->>H2: Event 2
                Note over H1,H2: Both received
                """);
        ctx.sayCode(
                """
            var mgr = EventManager.<AppEvent>start();
            var earlyCount = new AtomicInteger(0);
            var lateCount = new AtomicInteger(0);

            EventManager.Handler<AppEvent> early = event -> earlyCount.incrementAndGet();
            mgr.addHandler(early);

            mgr.syncNotify(new AppEvent.UserLogin("bob")); // early only

            EventManager.Handler<AppEvent> late = event -> lateCount.incrementAndGet();
            mgr.addHandler(late);

            mgr.syncNotify(new AppEvent.UserLogin("carol")); // both

            // early: 2 events, late: 1 event
            """,
                "java");

        var mgr = EventManager.<AppEvent>start();

        var earlyCount = new AtomicInteger(0);
        var lateCount = new AtomicInteger(0);

        EventManager.Handler<AppEvent> early = event -> earlyCount.incrementAndGet();
        mgr.addHandler(early);

        mgr.syncNotify(new AppEvent.UserLogin("bob")); // early only

        EventManager.Handler<AppEvent> late = event -> lateCount.incrementAndGet();
        mgr.addHandler(late);

        mgr.syncNotify(new AppEvent.UserLogin("carol")); // both

        assertThat(earlyCount.get()).isEqualTo(2); // received both events
        assertThat(lateCount.get()).isEqualTo(1); // only received the second

        ctx.sayKeyValue(
                Map.of(
                        "Early Handler Count",
                        String.valueOf(earlyCount.get()),
                        "Late Handler Count",
                        String.valueOf(lateCount.get())));
        mgr.stop();
    }

    // ── Test 3: deleteHandler stops receiving events ───────────────────────

    @Test
    void deleteHandler_stopsReceiving() throws Exception {
        ctx.sayNextSection("EventManager: Handler Removal");
        ctx.say(
                "Handlers can be removed with deleteHandler(). The terminate() callback is invoked for cleanup.");
        ctx.sayMermaid(
                """
            stateDiagram-v2
                [*] --> Registered: addHandler()
                Registered --> Processing: Event Received
                Processing --> Processing: More Events
                Processing --> Terminated: deleteHandler()
                Terminated --> [*]: terminate() called
                Note over Processing, Terminated: Handler removed from registry
                """);
        ctx.sayCode(
                """
            var mgr = EventManager.<AppEvent>start();
            var count = new AtomicInteger(0);
            var terminateCalled = new AtomicBoolean(false);

            EventManager.Handler<AppEvent> h = new EventManager.Handler<>() {
                @Override
                public void handleEvent(AppEvent event) {
                    count.incrementAndGet();
                }
                @Override
                public void terminate(Throwable reason) {
                    terminateCalled.set(true);
                }
            };

            mgr.addHandler(h);
            mgr.syncNotify(new AppEvent.UserLogin("dan")); // received

            boolean removed = mgr.deleteHandler(h);
            assertThat(removed).isTrue();

            mgr.syncNotify(new AppEvent.UserLogin("eve")); // NOT received
            """,
                "java");

        var mgr = EventManager.<AppEvent>start();

        var count = new AtomicInteger(0);
        var terminateCalled = new AtomicBoolean(false);

        EventManager.Handler<AppEvent> h =
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(AppEvent event) {
                        count.incrementAndGet();
                    }

                    @Override
                    public void terminate(Throwable reason) {
                        terminateCalled.set(true);
                    }
                };

        mgr.addHandler(h);
        mgr.syncNotify(new AppEvent.UserLogin("dan")); // received

        boolean removed = mgr.deleteHandler(h);
        assertThat(removed).isTrue();
        assertThat(terminateCalled).isTrue();

        mgr.syncNotify(new AppEvent.UserLogin("eve")); // NOT received

        assertThat(count.get()).isEqualTo(1);

        ctx.sayKeyValue(
                Map.of(
                        "Handler Removed",
                        String.valueOf(removed),
                        "Terminate Called",
                        String.valueOf(terminateCalled.get()),
                        "Events Processed",
                        String.valueOf(count.get())));
        mgr.stop();
    }

    // ── Test 4: crashing handler does NOT kill the manager ────────────────

    @Test
    void crashingHandler_doesNotKillManager() throws Exception {
        ctx.sayNextSection("EventManager: Fault Isolation");
        ctx.say(
                "A crashing handler is automatically removed but does NOT crash the EventManager. This is core OTP fault isolation.");
        ctx.sayMermaid(
                """
            graph TB
                subgraph "Before Crash"
                    E1[Event] --> M1[EventManager]
                    M1 --> C1[Crasher]
                    M1 --> S1[Survivor]
                    style C1 fill:#ff6b6b
                    style S1 fill:#90EE90
                end

                subgraph "After Crash"
                    E2[Event] --> M2[EventManager]
                    M2 -.->|removed| C2[Crasher]
                    M2 --> S2[Survivor]
                    style C2 fill:#d3d3d3
                    style S2 fill:#90EE90
                end

                C1 -->|throws exception| C2
                S1 --> S2
                M1 --> M2
                """);
        ctx.say(
                "When a handler crashes, the EventManager catches the exception, removes the failed handler, and continues processing events with remaining handlers.");
        ctx.sayCode(
                """
            var mgr = EventManager.<AppEvent>start();
            var survivorCount = new AtomicInteger(0);

            EventManager.Handler<AppEvent> crasher = new EventManager.Handler<>() {
                @Override
                public void handleEvent(AppEvent event) {
                    throw new RuntimeException("handler crash");
                }
            };

            EventManager.Handler<AppEvent> survivor = event -> survivorCount.incrementAndGet();

            mgr.addHandler(crasher);
            mgr.addHandler(survivor);

            mgr.syncNotify(new AppEvent.UserLogin("frank")); // crasher throws, survivor handles

            // Manager still alive, survivor still receives
            mgr.syncNotify(new AppEvent.UserLogin("grace"));
            assertThat(survivorCount.get()).isEqualTo(2);
            """,
                "java");

        var mgr = EventManager.<AppEvent>start();

        var survivorCount = new AtomicInteger(0);
        var terminateCalled = new AtomicBoolean(false);

        EventManager.Handler<AppEvent> crasher =
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(AppEvent event) {
                        throw new RuntimeException("handler crash");
                    }

                    @Override
                    public void terminate(Throwable reason) {
                        terminateCalled.set(reason != null);
                    }
                };

        EventManager.Handler<AppEvent> survivor = event -> survivorCount.incrementAndGet();

        mgr.addHandler(crasher);
        mgr.addHandler(survivor);

        // This should NOT kill the manager
        mgr.syncNotify(new AppEvent.UserLogin("frank"));

        // crasher was removed due to crash; terminate called with non-null reason
        assertThat(terminateCalled).isTrue();

        // manager is still alive and survivor still receives events
        mgr.syncNotify(new AppEvent.UserLogin("grace"));

        assertThat(survivorCount.get()).isEqualTo(2);

        ctx.sayKeyValue(
                Map.of(
                        "Crasher Terminated",
                        String.valueOf(terminateCalled.get()),
                        "Survivor Invocations",
                        String.valueOf(survivorCount.get()),
                        "Manager Status",
                        "ALIVE"));
        mgr.stop();
    }

    // ── Test 5: call delivers sync event to one specific handler ──────────

    @Test
    void call_synchronouslyCallsSpecificHandler() throws Exception {
        ctx.sayNextSection("EventManager: Targeted Handler Call");
        ctx.say(
                "call() delivers an event to a specific handler only, not a broadcast. This enables handler-specific queries.");
        ctx.sayMermaid(
                """
            graph LR
                E[Event] --> EM[EventManager]
                EM -->|call(h1, event)| H1[Handler 1]
                EM -.->|no delivery| H2[Handler 2]
                EM -.->|no delivery| Hn[Handler N]
                style H1 fill:#90EE90
                style H2 fill:#d3d3d3
                style Hn fill:#d3d3d3
                """);
        ctx.say(
                "Unlike notify() which broadcasts to all handlers, call() targets a single handler. This is useful for request-response patterns.");
        ctx.sayCode(
                """
            var mgr = EventManager.<AppEvent>start();
            var h1Events = new CopyOnWriteArrayList<AppEvent>();
            var h2Events = new CopyOnWriteArrayList<AppEvent>();

            EventManager.Handler<AppEvent> h1 = h1Events::add;
            EventManager.Handler<AppEvent> h2 = h2Events::add;

            mgr.addHandler(h1);
            mgr.addHandler(h2);

            // call(h1, event) — only h1 should receive it
            mgr.call(h1, new AppEvent.OrderPlaced("call-only", 1.0));

            assertThat(h1Events).hasSize(1);
            assertThat(h2Events).isEmpty();
            """,
                "java");

        var mgr = EventManager.<AppEvent>start();

        var h1Events = new CopyOnWriteArrayList<AppEvent>();
        var h2Events = new CopyOnWriteArrayList<AppEvent>();

        EventManager.Handler<AppEvent> h1 = h1Events::add;
        EventManager.Handler<AppEvent> h2 = h2Events::add;

        mgr.addHandler(h1);
        mgr.addHandler(h2);

        // call(h1, event) — only h1 should receive it
        mgr.call(h1, new AppEvent.OrderPlaced("call-only", 1.0));

        assertThat(h1Events).hasSize(1);
        assertThat(h2Events).isEmpty();

        ctx.sayKeyValue(
                Map.of(
                        "Handler 1 Events",
                        String.valueOf(h1Events.size()),
                        "Handler 2 Events",
                        String.valueOf(h2Events.size())));
        mgr.stop();
    }
}
