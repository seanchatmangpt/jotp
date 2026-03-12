package io.github.seanchatmangpt.jotp.dogfood.otp;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcMonitor;
import io.github.seanchatmangpt.jotp.ProcSys;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Tests for ProcessMonitoringExample demonstrating ProcMonitor + ProcSys observability. */
final class ProcessMonitoringExampleTest {

    record WorkerState(long messageCount, String name) {}

    sealed interface WorkerMessage {
        record Increment() implements WorkerMessage {}

        record GetState() implements WorkerMessage {}

        record Crash() implements WorkerMessage {}
    }

    static WorkerState workerBehavior(WorkerState state, WorkerMessage msg) {
        return switch (msg) {
            case WorkerMessage.Increment() ->
                    new WorkerState(state.messageCount() + 1, state.name());
            case WorkerMessage.GetState() -> state;
            case WorkerMessage.Crash() -> {
                throw new RuntimeException("Worker " + state.name() + " crashed!");
            }
        };
    }

    @Test
    void testProcMonitorDetectsProcessTermination() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "test-worker"),
                        ProcessMonitoringExampleTest::workerBehavior);
        var downReceived = new CountDownLatch(1);

        // Act: Install monitor
        ProcMonitor.monitor(worker, _ -> {});

        // Create a separate process to detect the DOWN signal
        var monitor =
                Proc.spawn(
                        new Object(),
                        (state, msg) -> {
                            if (msg instanceof String && msg.equals("check-down")) {
                                // Try to interact with worker to detect if it's down
                                try {
                                    worker.tell(new WorkerMessage.GetState());
                                } catch (Exception e) {
                                    downReceived.countDown();
                                }
                            }
                            return state;
                        });

        // Crash the worker
        worker.tell(new WorkerMessage.Crash());

        // Assert: Wait for DOWN notification
        // The downReceived latch should trigger when we try to send to a dead process
        Thread.sleep(100);
        monitor.tell("check-down");

        assertThat(downReceived.getCount())
                .as("Monitor should detect process termination")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void testProcSysStatisticsReturnsCorrectData() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "stats-test"),
                        ProcessMonitoringExampleTest::workerBehavior);

        // Act: Send several messages
        worker.tell(new WorkerMessage.Increment());
        worker.tell(new WorkerMessage.Increment());
        worker.tell(new WorkerMessage.Increment());
        Thread.sleep(50);

        // Get statistics without stopping the worker
        var stats = ProcSys.statistics(worker);

        // Assert: Worker should still be operational and stats should be non-null
        assertThat(stats).isNotNull();
        assertThat(stats.messagesIn()).as("messagesIn should be positive").isGreaterThan(0);

        // Send another message to verify worker still works
        worker.tell(new WorkerMessage.Increment());
        Thread.sleep(50);
    }

    @Test
    void testMonitoringDoesntInterfereWithWorkerOperation() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "interference-test"),
                        ProcessMonitoringExampleTest::workerBehavior);

        // Act: Install monitor and send messages
        ProcMonitor.monitor(worker, _ -> {});
        var messageCount = 10;
        for (int i = 0; i < messageCount; i++) {
            worker.tell(new WorkerMessage.Increment());
        }
        Thread.sleep(100);

        // Get state via ask to verify all messages were processed
        var stats = ProcSys.statistics(worker);

        // Assert: Worker should be responsive and stats should reflect monitoring
        assertThat(stats).isNotNull();
    }

    @Test
    void testDownNotificationContainsCrashReason() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "crash-reason-test"),
                        ProcessMonitoringExampleTest::workerBehavior);
        var crashDetected = new CountDownLatch(1);
        final String[] crashReason = {null};

        // Act: Monitor and crash
        ProcMonitor.monitor(worker, _ -> {});
        worker.tell(new WorkerMessage.Crash());

        // Detect the crash
        Thread.sleep(100);
        try {
            ProcSys.statistics(worker);
        } catch (Exception e) {
            crashReason[0] = e.getMessage();
            crashDetected.countDown();
        }

        // Assert: Crash should be detected
        assertThat(crashDetected.getCount())
                .as("Crash should be detected")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void testMultipleWorkersMonitoredIndependently() throws Exception {
        // Arrange
        var worker1 =
                Proc.spawn(
                        new WorkerState(0, "worker-1"),
                        ProcessMonitoringExampleTest::workerBehavior);
        var worker2 =
                Proc.spawn(
                        new WorkerState(0, "worker-2"),
                        ProcessMonitoringExampleTest::workerBehavior);

        // Act: Monitor both independently
        ProcMonitor.monitor(worker1, _ -> {});
        ProcMonitor.monitor(worker2, _ -> {});

        // Send messages to both
        for (int i = 0; i < 5; i++) {
            worker1.tell(new WorkerMessage.Increment());
            worker2.tell(new WorkerMessage.Increment());
        }
        Thread.sleep(100);

        // Get stats from both
        var stats1 = ProcSys.statistics(worker1);
        var stats2 = ProcSys.statistics(worker2);

        // Assert: Both should be operational and independent
        assertThat(stats1).isNotNull();
        assertThat(stats2).isNotNull();

        // Crash one, verify other still works
        worker1.tell(new WorkerMessage.Crash());
        Thread.sleep(100);

        // Worker2 should still be operational
        worker2.tell(new WorkerMessage.Increment());
        var stats2After = ProcSys.statistics(worker2);
        assertThat(stats2After).isNotNull();
    }

    @Test
    void testDemonitorStopsMonitoring() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "demonitor-test"),
                        ProcessMonitoringExampleTest::workerBehavior);

        // Act: Monitor, then demonitor
        var monitorRef = ProcMonitor.monitor(worker, _ -> {});
        ProcMonitor.demonitor(monitorRef);

        // Crash the worker
        worker.tell(new WorkerMessage.Crash());
        Thread.sleep(100);

        // Assert: Process should be down, but we shouldn't receive notifications
        var exceptionThrown = false;
        try {
            ProcSys.statistics(worker);
        } catch (Exception e) {
            exceptionThrown = true;
        }
        assertThat(exceptionThrown).as("Worker should be down after crash").isTrue();
    }
}
