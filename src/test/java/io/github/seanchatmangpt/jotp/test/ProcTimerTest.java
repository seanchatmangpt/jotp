package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcTimer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP timer semantics in Java 26.
 *
 * <p>Armstrong: "Timers are how OTP implements scheduled message delivery. send_after/2 and
 * send_interval/2 are the primitives for all time-based behavior."
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining OTP timer semantics, one-shot delivery, repeating intervals, and cancellation. Run
 * with DTR to see examples with actual output values.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>{@code send_after} delivers exactly one message after the delay
 *   <li>{@code send_interval} delivers messages repeatedly at the period
 *   <li>{@code cancel} prevents delivery of a pending one-shot timer
 *   <li>{@code cancel} stops a repeating interval timer
 * </ol>
 */
@Timeout(10)
class ProcTimerTest implements WithAssertions {


    sealed interface Msg permits Msg.Tick, Msg.Ping {
        record Tick() implements Msg {}

        record Ping() implements Msg {}
    }

    /** Counter process: increments state on Tick, no-op on Ping (used for ask-to-read). */
    static Proc<Integer, Msg> counter() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Tick() -> state + 1;
                            case Msg.Ping() -> state;
                        });
    }

    // -------------------------------------------------------------------------
    // 1. send_after delivers exactly one message after delay
    // -------------------------------------------------------------------------

    @Test
    void sendAfter_deliversOneMessageAfterDelay() throws InterruptedException {
                "sendAfter() delivers a single message after a specified delay. The timer fires once, then auto-cancels.");
                """
            var proc = counter();

            // Send a Tick message after 100ms
            ProcTimer.sendAfter(100, proc, new Msg.Tick());

            // Before delay: state still 0
            Thread.sleep(20);
            assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(0);

            // After delay: state incremented to 1
            await().atMost(Duration.ofSeconds(3))
                .until(() -> proc.ask(new Msg.Ping()).join() == 1);

            // Stays at 1 (one-shot, not repeating)
            Thread.sleep(200);
            assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(1);
            """,
                "java");
                """
            sequenceDiagram
                participant C as Client
                participant T as Timer
                participant P as Proc

                C->>T: sendAfter(100ms, Tick)
                Note over T: Waiting...
                T->>P: Tick (after 100ms)
                P->>P: state = 1

                Note over T: Timer auto-cancels

                style T fill:#ffd43b
            """);
                "sendAfter is the primitive for delayed operations. It's used for timeouts, retries, and any 'do X later' behavior.");

        var proc = counter();

        ProcTimer.sendAfter(100, proc, new Msg.Tick());

        Thread.sleep(20);
        assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(0);

        await().atMost(Duration.ofSeconds(3)).until(() -> proc.ask(new Msg.Ping()).join() == 1);

        Thread.sleep(200);
        assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(1);

                Map.of(
                        "Delay",
                        "100ms",
                        "Messages Delivered",
                        "1 (one-shot)",
                        "State Before",
                        "0",
                        "State After",
                        "1",
                        "Timer Status",
                        "Auto-cancelled"));
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 2. send_interval delivers messages repeatedly
    // -------------------------------------------------------------------------

    @Test
    void sendInterval_deliversRepeatingMessages() throws InterruptedException {
                "sendInterval() delivers messages repeatedly at a fixed period. The timer continues until explicitly cancelled.");
                """
            var proc = counter();

            // Send Tick every 50ms
            var ref = ProcTimer.sendInterval(50, proc, new Msg.Tick());

            // Wait for at least 3 ticks
            await().atMost(Duration.ofSeconds(3))
                .until(() -> proc.ask(new Msg.Ping()).join() >= 3);

            // Cancel to stop the timer
            ProcTimer.cancel(ref);
            """,
                "java");
                """
            sequenceDiagram
                participant C as Client
                participant T as Timer
                participant P as Proc

                C->>T: sendInterval(50ms, Tick)
                loop Every 50ms
                    T->>P: Tick
                    P->>P: state++
                end

                C->>T: cancel(ref)
                Note over T: Stopped

                style T fill:#51cf66
            """);
                "Interval timers are for recurring tasks: heartbeats, polling, metrics collection, periodic cleanup. Always cancel when done to prevent memory leaks.");

        var proc = counter();
        var ref = ProcTimer.sendInterval(50, proc, new Msg.Tick());

        await().atMost(Duration.ofSeconds(3)).until(() -> proc.ask(new Msg.Ping()).join() >= 3);

        ProcTimer.cancel(ref);
        proc.stop();

                Map.of(
                        "Interval",
                        "50ms",
                        "Messages Delivered",
                        "3+ (repeating)",
                        "Timer Status",
                        "Cancelled",
                        "Behavior",
                        "Periodic until cancelled"));
    }

    // -------------------------------------------------------------------------
    // 3. cancel prevents one-shot delivery
    // -------------------------------------------------------------------------

    @Test
    void cancel_preventsOneShot() throws InterruptedException {
                "Cancelling a one-shot timer prevents message delivery. The timer is removed from the scheduler before it fires.");
                """
            var proc = counter();

            // Schedule a Tick after 200ms
            var ref = ProcTimer.sendAfter(200, proc, new Msg.Tick());

            // Cancel immediately
            ProcTimer.cancel(ref);

            // Wait past the original delay
            Thread.sleep(350);

            // Message never delivered - state still 0
            assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(0);
            """,
                "java");
                """
            stateDiagram-v2
                [*] --> Scheduled: sendAfter(200ms)
                Scheduled --> Cancelled: cancel(ref)
                Cancelled --> [*]: Message never sent

                note right of Cancelled
                    Timer removed from scheduler
                    No message delivery
                end note
            """);
                "cancel() is idempotent - calling it multiple times is safe. Returns true if timer was pending, false if already fired/cancelled.");

        var proc = counter();

        var ref = ProcTimer.sendAfter(200, proc, new Msg.Tick());
        ProcTimer.cancel(ref);

        Thread.sleep(350);
        assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(0);

                Map.of(
                        "Original Delay",
                        "200ms",
                        "Cancel Time",
                        "Immediately",
                        "Wait Duration",
                        "350ms (past due)",
                        "Messages Delivered",
                        "0",
                        "Timer Status",
                        "Cancelled"));
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 4. cancel stops interval timer
    // -------------------------------------------------------------------------

    @Test
    void cancel_stopsInterval() throws InterruptedException {
                "Cancelling an interval timer stops all future deliveries. In-flight messages may still arrive, but no new ones are scheduled.");
                """
            var proc = counter();

            // Start interval: Tick every 50ms
            var ref = ProcTimer.sendInterval(50, proc, new Msg.Tick());

            // Wait for a couple of ticks
            await().atMost(Duration.ofSeconds(3))
                .until(() -> proc.ask(new Msg.Ping()).join() >= 2);

            // Cancel the timer
            ProcTimer.cancel(ref);
            var countAfterCancel = proc.ask(new Msg.Ping()).join();

            // Wait well past another period
            Thread.sleep(200);
            var countLater = proc.ask(new Msg.Ping()).join();

            // Should not have grown significantly
            assertThat(countLater).isLessThanOrEqualTo(countAfterCancel + 1);
            """,
                "java");
                "Allow +1 for in-flight messages that were already scheduled when cancel() was called. Cancel prevents new scheduling, doesn't intercept in-flight messages.");

        var proc = counter();
        var ref = ProcTimer.sendInterval(50, proc, new Msg.Tick());

        await().atMost(Duration.ofSeconds(3)).until(() -> proc.ask(new Msg.Ping()).join() >= 2);

        ProcTimer.cancel(ref);
        int countAfterCancel = proc.ask(new Msg.Ping()).join();

        Thread.sleep(200);
        int countLater = proc.ask(new Msg.Ping()).join();

        assertThat(countLater).isLessThanOrEqualTo(countAfterCancel + 1);

                Map.of(
                        "Interval",
                        "50ms",
                        "Count at Cancel",
                        String.valueOf(countAfterCancel),
                        "Count After 200ms",
                        String.valueOf(countLater),
                        "Growth",
                        "≤ 1 (in-flight allowance)",
                        "Timer Status",
                        "Stopped"));
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 5. TimerRef.cancel() returns true when pending, false when already fired
    // -------------------------------------------------------------------------

    @Test
    void timerRef_cancel_returnsTrueWhenPending() throws InterruptedException {
                "cancel() returns true if the timer was pending (cancelled before firing), false if already fired or previously cancelled.");
                """
            var proc = counter();

            // Very long delay - guaranteed pending
            var ref = ProcTimer.sendAfter(5000, proc, new Msg.Tick());

            // First cancel: timer was pending
            assertThat(ref.cancel()).isTrue();

            // Second cancel: already cancelled
            assertThat(ref.cancel()).isFalse();
            """,
                "java");
                new String[][] {
                    {"Cancel Timing", "Return Value", "Meaning"},
                    {"Before fire", "true", "Timer was pending, now cancelled"},
                    {"After fire", "false", "Timer already fired"},
                    {"Already cancelled", "false", "No-op, already cancelled"}
                });
                "Return value helps distinguish successful cancellation from no-ops. Useful for debugging and conditional logic.");

        var proc = counter();
        var ref = ProcTimer.sendAfter(5000, proc, new Msg.Tick());

        assertThat(ref.cancel()).isTrue();
        assertThat(ref.cancel()).isFalse();

                Map.of(
                        "First cancel()",
                        "true (was pending)",
                        "Second cancel()",
                        "false (already cancelled)",
                        "Timer Status",
                        "Cancelled"));
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 6. Multiple concurrent timers all fire independently
    // -------------------------------------------------------------------------

    @Test
    void multipleConcurrentTimers_allFire() throws InterruptedException {
                "Multiple timers targeting the same process fire independently. Each timer maintains its own schedule and delivery.");
                """
            var proc = counter();

            // Three independent timers
            ProcTimer.sendAfter(50, proc, new Msg.Tick());
            ProcTimer.sendAfter(80, proc, new Msg.Tick());
            ProcTimer.sendAfter(110, proc, new Msg.Tick());

            // All three fire independently
            await().atMost(Duration.ofSeconds(3))
                .until(() -> proc.ask(new Msg.Ping()).join() == 3);
            """,
                "java");
                """
            gantt
                title Timer Timeline
                dateFormat X
                axisFormat %Lms

                section Timer 1
                Fire  : 50, 50

                section Timer 2
                Fire  : 80, 80

                section Timer 3
                Fire  : 110, 110
            """);
                "Timers don't interfere with each other. Each one-shot timer delivers exactly one message. This enables complex timed behavior patterns.");

        var ticks = new AtomicInteger(0);
        var proc =
                new Proc<Integer, Msg>(
                        0,
                        (state, msg) -> {
                            ticks.incrementAndGet();
                            return state + 1;
                        });

        ProcTimer.sendAfter(50, proc, new Msg.Tick());
        ProcTimer.sendAfter(80, proc, new Msg.Tick());
        ProcTimer.sendAfter(110, proc, new Msg.Tick());

        await().atMost(Duration.ofSeconds(3)).until(() -> ticks.get() == 3);

                Map.of(
                        "Timers Created",
                        "3",
                        "All Fired",
                        "Yes",
                        "Total Ticks",
                        String.valueOf(ticks.get()),
                        "Independence",
                        "Confirmed"));
        proc.stop();
    }
}
