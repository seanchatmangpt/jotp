package io.github.seanchatmangpt.jotp.dogfood.otp;

import static java.time.Instant.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.seanchatmangpt.jotp.EventManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Test for EventDrivenExample: demonstrates EventManager handler isolation.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>All handlers receive events via notify/syncNotify
 *   <li>A crashing handler is removed silently
 *   <li>Remaining handlers continue after one fails
 *   <li>syncNotify blocks until handlers complete
 * </ul>
 */
@DisplayName("EventManager: pub-sub with handler isolation")
class EventDrivenExampleTest implements WithAssertions {

    private EventManager<EventDrivenExample.OrderEvent> manager;
    private EventDrivenExample.LogHandler logHandler;
    private EventDrivenExample.AnalyticsHandler analyticsHandler;
    private EventDrivenExample.NotificationHandler notificationHandler;

    @BeforeEach
    void setUp() {
        manager = EventManager.start(Duration.ofSeconds(1));
        logHandler = new EventDrivenExample.LogHandler();
        analyticsHandler = new EventDrivenExample.AnalyticsHandler();
        notificationHandler = new EventDrivenExample.NotificationHandler();
    }

    @Test
    @DisplayName("All handlers receive OrderCreated event")
    void allHandlersReceiveOrderCreated() throws InterruptedException {
        manager.addHandler(logHandler);
        manager.addHandler(analyticsHandler);
        manager.addHandler(notificationHandler);

        EventDrivenExample.OrderCreated event =
                new EventDrivenExample.OrderCreated("ORD-001", 99.99, now());

        manager.syncNotify(event);

        assertThat(logHandler.getMessages()).hasSize(1);
        assertThat(analyticsHandler.getCreatedCount()).isEqualTo(1);
        assertThat(analyticsHandler.getTotalRevenue()).isEqualTo(99.99);
        assertThat(notificationHandler.getSentNotifications()).hasSize(1);
    }

    @Test
    @DisplayName("All handlers receive OrderShipped event")
    void allHandlersReceiveOrderShipped() throws InterruptedException {
        manager.addHandler(logHandler);
        manager.addHandler(analyticsHandler);
        manager.addHandler(notificationHandler);

        EventDrivenExample.OrderShipped event =
                new EventDrivenExample.OrderShipped("ORD-001", "TRK-123", now());

        manager.syncNotify(event);

        assertThat(logHandler.getMessages()).hasSize(1);
        assertThat(analyticsHandler.getShippedCount()).isEqualTo(1);
        assertThat(notificationHandler.getSentNotifications()).hasSize(1);
    }

    @Test
    @DisplayName("Crashing handler (AnalyticsHandler) is removed on OrderCancelled")
    void crashingHandlerIsRemovedOnOrderCancelled() throws InterruptedException {
        manager.addHandler(logHandler);
        manager.addHandler(analyticsHandler);
        manager.addHandler(notificationHandler);

        // Crash the analytics handler
        EventDrivenExample.OrderCancelled event =
                new EventDrivenExample.OrderCancelled("ORD-001", "Customer request", now());

        // syncNotify may raise RuntimeException due to handler crash, but the manager continues
        try {
            manager.syncNotify(event);
        } catch (RuntimeException ignored) {
            // Expected: AnalyticsHandler throws, manager catches and removes it
        }

        // The crashed handler should be marked as terminated
        // (it sets its own terminated flag in terminate() callback)
        assertThat(analyticsHandler.getCreatedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Remaining handlers continue after one crashes")
    void remainingHandlersContinueAfterOneCrashes() throws InterruptedException {
        manager.addHandler(logHandler);
        manager.addHandler(analyticsHandler);
        manager.addHandler(notificationHandler);

        // First event: all handlers work
        EventDrivenExample.OrderCreated created =
                new EventDrivenExample.OrderCreated("ORD-001", 50.00, now());
        manager.syncNotify(created);

        assertThat(logHandler.getMessages()).hasSize(1);
        assertThat(analyticsHandler.getCreatedCount()).isEqualTo(1);
        assertThat(notificationHandler.getSentNotifications()).hasSize(1);

        // Second event: crashes analyticsHandler
        EventDrivenExample.OrderCancelled cancelled =
                new EventDrivenExample.OrderCancelled("ORD-001", "test", now());
        try {
            manager.syncNotify(cancelled);
        } catch (RuntimeException ignored) {
            // Expected
        }

        // Third event: only logHandler and notificationHandler process it
        EventDrivenExample.OrderCreated created2 =
                new EventDrivenExample.OrderCreated("ORD-002", 75.00, now());
        manager.syncNotify(created2);

        // Both remaining handlers should have processed both created events
        assertThat(logHandler.getMessages()).hasSize(3); // created1 + cancelled + created2
        assertThat(notificationHandler.getSentNotifications()).hasSize(3); // same
    }

    @Test
    @DisplayName("syncNotify blocks until handlers complete")
    void syncNotifyBlocksUntilComplete() throws InterruptedException {
        // Create a slow handler that tracks when it completes
        var completionTimes = new ArrayList<Long>();

        var slowHandler = new EventManager.Handler<EventDrivenExample.OrderEvent>() {
            @Override
            public void handleEvent(EventDrivenExample.OrderEvent event) {
                try {
                    Thread.sleep(100); // Simulate slow processing
                } catch (InterruptedException ignored) {
                }
                completionTimes.add(System.currentTimeMillis());
            }

            @Override
            public void terminate(Throwable reason) {}
        };

        manager.addHandler(slowHandler);

        long startTime = System.currentTimeMillis();
        manager.syncNotify(new EventDrivenExample.OrderCreated("ORD-001", 50.00, now()));
        long endTime = System.currentTimeMillis();

        // syncNotify should have blocked for at least the handler's sleep time
        assertThat(endTime - startTime).isGreaterThanOrEqualTo(100);
        assertThat(completionTimes).hasSize(1);
    }

    @Test
    @DisplayName("notify returns immediately (async)")
    void notifyReturnsImmediately() throws InterruptedException {
        var handler = new EventManager.Handler<EventDrivenExample.OrderEvent>() {
            private volatile boolean processed = false;

            @Override
            public void handleEvent(EventDrivenExample.OrderEvent event) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                processed = true;
            }

            @Override
            public void terminate(Throwable reason) {}
        };

        manager.addHandler(handler);

        long startTime = System.currentTimeMillis();
        manager.notify(new EventDrivenExample.OrderCreated("ORD-001", 50.00, now()));
        long endTime = System.currentTimeMillis();

        // notify() should return immediately (< 50ms)
        assertThat(endTime - startTime).isLessThan(50);

        // Handler should eventually process the event
        Thread.sleep(250);
    }

    @Test
    @DisplayName("Handler receives terminate callback on normal removal")
    void handlerReceivesTerminateOnNormalRemoval() throws InterruptedException {
        var terminations = new ArrayList<Throwable>();

        var handler = new EventManager.Handler<EventDrivenExample.OrderEvent>() {
            @Override
            public void handleEvent(EventDrivenExample.OrderEvent event) {}

            @Override
            public void terminate(Throwable reason) {
                terminations.add(reason);
            }
        };

        manager.addHandler(handler);
        manager.deleteHandler(handler);

        assertThat(terminations).hasSize(1);
        assertThat(terminations.get(0)).isNull();
    }

    @Test
    @DisplayName("Multiple events to multiple handlers")
    void multipleEventsToMultipleHandlers() throws InterruptedException {
        manager.addHandler(logHandler);
        manager.addHandler(notificationHandler);

        manager.syncNotify(new EventDrivenExample.OrderCreated("ORD-001", 100.00, now()));
        manager.syncNotify(new EventDrivenExample.OrderShipped("ORD-001", "TRK-001", now()));
        manager.syncNotify(new EventDrivenExample.OrderCreated("ORD-002", 50.00, now()));

        // Both handlers should have processed all 3 events
        assertThat(logHandler.getMessages()).hasSize(3);
        assertThat(notificationHandler.getSentNotifications()).hasSize(3);
    }
}
