package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcMonitor;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP process-monitor semantics in Java 26.
 *
 * <p>Armstrong: "Monitors are one-way links. A monitor observes a process without being affected by
 * its death."
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining OTP monitor semantics, unilateral observation, DOWN message delivery, and demonitor
 * behavior. Run with DTR to see examples with actual output values.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>Monitor fires DOWN with non-null reason on abnormal exit
 *   <li>Monitor fires DOWN with null reason on normal exit ({@code stop()})
 *   <li>Monitor does NOT kill the monitoring side (unilateral, unlike link)
 *   <li>{@code demonitor} prevents DOWN from firing after cancellation
 * </ol>
 */
@Timeout(10)
class ProcMonitorTest implements WithAssertions {


    sealed interface Msg permits Msg.Ping, Msg.Crash {
        record Ping() implements Msg {}

        record Crash() implements Msg {}
    }

    static Proc<Integer, Msg> counter() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Ping() -> state + 1;
                            case Msg.Crash() -> throw new RuntimeException("BOOM");
                        });
    }

    // -------------------------------------------------------------------------
    // 1. Abnormal exit fires DOWN with the exception as reason
    // -------------------------------------------------------------------------

    @Test
    void monitor_abnormalExit_firesDownWithReason() {
                "ProcMonitor observes a process and fires a callback when it exits abnormally (crashes). The exception that caused the crash is passed as the reason.");
                """
            var target = counter();
            var downReason = new AtomicReference<Throwable>();
            var downFired = new AtomicBoolean(false);

            ProcMonitor.monitor(target, reason -> {
                downReason.set(reason);
                downFired.set(true);
            });

            // Crash the target
            target.tell(new Msg.Crash());

            // Monitor callback fires with the exception
            await().atMost(Duration.ofSeconds(3)).untilTrue(downFired);
            // downReason.get().getMessage() == "BOOM"
            """,
                "java");
                """
            sequenceDiagram
                participant M as Monitor
                participant T as Target Process

                M->>T: monitor(callback)
                Note over M: Observing T

                T->>T: Crash (RuntimeException)
                T-->>M: DOWN(reason=exception)
                Note over M: Callback invoked

                style T fill:#ff6b6b
            """);
                "Unlike links, monitors are one-way. The monitoring side is NOT affected when the target crashes. The callback is invoked asynchronously.");

        var target = counter();
        var downReason = new AtomicReference<Throwable>();
        var downFired = new AtomicBoolean(false);

        ProcMonitor.monitor(
                target,
                reason -> {
                    downReason.set(reason);
                    downFired.set(true);
                });

        target.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3)).untilTrue(downFired);
        assertThat(downReason.get()).isNotNull().hasMessage("BOOM");

                Map.of(
                        "Target Status",
                        "Crashed",
                        "Monitor Callback",
                        "Invoked",
                        "Reason Type",
                        downReason.get().getClass().getSimpleName(),
                        "Reason Message",
                        downReason.get().getMessage()));
    }

    // -------------------------------------------------------------------------
    // 2. Normal exit fires DOWN with null reason
    // -------------------------------------------------------------------------

    @Test
    void monitor_normalExit_firesDownWithNullReason() throws InterruptedException {
                "Monitors also fire on normal exit (stop()), but with a null reason. This distinguishes graceful shutdown from crashes.");
                """
            var target = counter();
            var downReason = new AtomicReference<Throwable>(new RuntimeException("sentinel"));
            var downFired = new AtomicBoolean(false);

            ProcMonitor.monitor(target, reason -> {
                downReason.set(reason); // should be null for normal exit
                downFired.set(true);
            });

            // Graceful shutdown
            target.stop();

            await().atMost(Duration.ofSeconds(3)).untilTrue(downFired);
            // downReason.get() == null (normal exit)
            """,
                "java");
                new String[][] {
                    {"Exit Type", "Reason Value", "Interpretation"},
                    {"Normal (stop())", "null", "Graceful shutdown"},
                    {"Abnormal (crash)", "Exception", "Process crashed"}
                });
                "This null vs non-null distinction lets monitoring code handle graceful shutdown differently from crashes. You might log shutdown but trigger alerts for crashes.");

        var target = counter();
        var downReason = new AtomicReference<Throwable>(new RuntimeException("sentinel"));
        var downFired = new AtomicBoolean(false);

        ProcMonitor.monitor(
                target,
                reason -> {
                    downReason.set(reason);
                    downFired.set(true);
                });

        target.stop();

        await().atMost(Duration.ofSeconds(3)).untilTrue(downFired);
        assertThat(downReason.get()).isNull();

                Map.of(
                        "Target Exit",
                        "Normal (stop())",
                        "Monitor Callback",
                        "Invoked",
                        "Reason Value",
                        "null",
                        "Interpretation",
                        "Graceful shutdown"));
    }

    // -------------------------------------------------------------------------
    // 3. Monitor does NOT kill the monitoring side (unilateral)
    // -------------------------------------------------------------------------

    @Test
    void monitor_targetCrashes_monitoringSideKeepsRunning() throws InterruptedException {
                "Monitors are unilateral - the monitoring side is NOT affected when the target crashes. Unlike links, crashes don't propagate through monitors.");
                """
            var target = counter();
            var watcher = counter();

            ProcMonitor.monitor(target, reason -> {
                // Watcher still runs — tell it a Ping to confirm
                watcher.tell(new Msg.Ping());
            });

            // Target crashes
            target.tell(new Msg.Crash());

            // Watcher is still alive and responsive
            await().atMost(Duration.ofSeconds(3))
                .until(() -> watcher.ask(new Msg.Ping()).join() >= 1);
            """,
                "java");
                """
            graph LR
                W[Watcher] -->|monitor| T[Target]
                T -->|Crash| X[Exception]
                X -->|DOWN callback| W
                style T fill:#ff6b6b
                style W fill:#51cf66
                style X fill:#ffd43b
            """);
                "This is key difference from links: links are bidirectional (both die), monitors are unilateral (only callback fires). Monitors are for observation, links are for coupling.");

        var target = counter();
        var watcher = counter();
        var aliveAfterCrash = new AtomicBoolean(false);

        ProcMonitor.monitor(
                target,
                reason -> {
                    watcher.tell(new Msg.Ping());
                });

        target.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3)).until(() -> watcher.ask(new Msg.Ping()).join() >= 1);
        aliveAfterCrash.set(true);

        watcher.stop();
        assertThat(aliveAfterCrash.get()).isTrue();

                Map.of(
                        "Target Status",
                        "Crashed",
                        "Watcher Status",
                        "Still Running",
                        "Relationship",
                        "Unilateral (one-way)",
                        "Callback Invoked",
                        "Yes"));
    }

    // -------------------------------------------------------------------------
    // 4. demonitor prevents DOWN from firing
    // -------------------------------------------------------------------------

    @Test
    void demonitor_preventsDownOnSubsequentCrash() throws InterruptedException {
                "demonitor() cancels an active monitor. After cancellation, the DOWN callback will not fire, even if the target crashes later.");
                """
            var target = counter();
            var downFired = new AtomicBoolean(false);

            var ref = ProcMonitor.monitor(target, _ -> downFired.set(true));

            // Cancel the monitor before crash
            ProcMonitor.demonitor(ref);

            // Target crashes - no callback fires
            target.tell(new Msg.Crash());

            // downFired stays false
            Thread.sleep(100);
            assertThat(downFired.get()).isFalse();
            """,
                "java");
                """
            stateDiagram-v2
                [*] --> Monitoring: monitor()
                Monitoring --> Cancelled: demonitor()
                Cancelled --> TargetCrashes: target.crash()
                TargetCrashes --> [*]: NO callback
            """);
                "demonitor is useful for temporary monitoring. Monitor only during specific operations, then cancel when no longer needed.");

        var target = counter();
        var downFired = new AtomicBoolean(false);

        var ref = ProcMonitor.monitor(target, _ -> downFired.set(true));

        ProcMonitor.demonitor(ref);

        target.tell(new Msg.Crash());
        target.thread().join();

        Thread.sleep(100);
        assertThat(downFired.get()).isFalse();

                Map.of(
                        "Monitor Status",
                        "Cancelled (demonitor)",
                        "Target Status",
                        "Crashed",
                        "Callback Fired",
                        "No",
                        "Reason",
                        "Monitor cancelled before crash"));
    }

    // -------------------------------------------------------------------------
    // 5. Multiple monitors on same target all fire
    // -------------------------------------------------------------------------

    @Test
    void monitor_multipleMonitors_allFire() {
                "Multiple processes can monitor the same target. All monitors fire independently when the target exits.");
                """
            var target = counter();
            var fired1 = new AtomicBoolean(false);
            var fired2 = new AtomicBoolean(false);

            // Two independent monitors
            ProcMonitor.monitor(target, _ -> fired1.set(true));
            ProcMonitor.monitor(target, _ -> fired2.set(true));

            // Target crashes - both callbacks fire
            target.tell(new Msg.Crash());

            await().atMost(Duration.ofSeconds(3))
                .until(() -> fired1.get() && fired2.get());
            """,
                "java");
                """
            graph TB
                T[Target] --> M1[Monitor 1]
                T --> M2[Monitor 2]
                T -->|Crash| X[Exception]
                X -->|DOWN| M1
                X -->|DOWN| M2

                style T fill:#ff6b6b
                style M1 fill:#51cf66
                style M2 fill:#51cf66
            """);
                "Each monitor is independent. Canceling one monitor doesn't affect others. This enables multiple observers of the same process.");

        var target = counter();
        var fired1 = new AtomicBoolean(false);
        var fired2 = new AtomicBoolean(false);

        ProcMonitor.monitor(target, _ -> fired1.set(true));
        ProcMonitor.monitor(target, _ -> fired2.set(true));

        target.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3)).until(() -> fired1.get() && fired2.get());

                Map.of(
                        "Monitors Registered",
                        "2",
                        "Target Status",
                        "Crashed",
                        "Monitor 1 Fired",
                        String.valueOf(fired1.get()),
                        "Monitor 2 Fired",
                        String.valueOf(fired2.get()),
                        "Independence",
                        "Yes"));
    }
}
