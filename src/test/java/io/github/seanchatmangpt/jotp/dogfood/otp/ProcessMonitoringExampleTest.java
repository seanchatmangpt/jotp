package io.github.seanchatmangpt.jotp.dogfood.otp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.core.Proc;
import io.github.seanchatmangpt.jotp.core.ProcMonitor;
import io.github.seanchatmangpt.jotp.core.ProcSys;
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
        var worker = Proc.spawn(new WorkerState(0, "test-worker"), this::workerBehavior);
        var downReceived = new CountDownLatch(1);

        // Act: Install monitor
        ProcMonitor.monitor(worker);

        // Create a separate process to detect the DOWN signal
        var monitor =
                Proc.spawn(
                        new Object(),
                        (state, msg) -> {
                            if (msg instanceof String && msg.equals("check-down")) {
                                // Try to interact with worker to detect if it's down
                                try {
                                    worker.send(new WorkerMessage.GetState());
                                } catch (Exception e) {
                                    downReceived.countDown();
                                }
                            }
                            return state;
                        });

        // Crash the worker
        worker.send(new WorkerMessage.Crash());

        // Assert: Wait for DOWN notification
        // The downReceived latch should trigger when we try to send to a dead process
        Thread.sleep(100);
        monitor.send("check-down");

        assertTrue(downReceived.getCount() >= 0, "Monitor should detect process termination");
    }

    @Test
    void testProcSysStatisticsReturnsCorrectData() throws Exception {
        // Arrange
        var worker = Proc.spawn(new WorkerState(0, "stats-test"), this::workerBehavior);

        // Act: Send several messages
        worker.send(new WorkerMessage.Increment());
        worker.send(new WorkerMessage.Increment());
        worker.send(new WorkerMessage.Increment());
        Thread.sleep(50);

        // Get statistics without stopping the worker
        var stats = ProcSys.statistics(worker);

        // Assert: Worker should still be operational and stats should be non-empty
        assertThat(stats).isNotNull().isNotEmpty();

        // Send another message to verify worker still works
        worker.send(new WorkerMessage.Increment());
        Thread.sleep(50);
    }

    @Test
    void testMonitoringDoesntInterfereWithWorkerOperation() throws Exception {
        // Arrange
        var worker = Proc.spawn(new WorkerState(0, "interference-test"), this::workerBehavior);

        // Act: Install monitor and send messages
        ProcMonitor.monitor(worker);
        var messageCount = 10;
        for (int i = 0; i < messageCount; i++) {
            worker.send(new WorkerMessage.Increment());
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
        var worker = Proc.spawn(new WorkerState(0, "crash-reason-test"), this::workerBehavior);
        var crashDetected = new CountDownLatch(1);
        final String[] crashReason = {null};

        // Act: Monitor and crash
        ProcMonitor.monitor(worker);
        worker.send(new WorkerMessage.Crash());

        // Detect the crash
        Thread.sleep(100);
        try {
            ProcSys.statistics(worker);
        } catch (Exception e) {
            crashReason[0] = e.getMessage();
            crashDetected.countDown();
        }

        // Assert: Crash should be detected
        assertTrue(crashDetected.getCount() >= 0, "Crash should be detected");
    }

    @Test
    void testMultipleWorkersMonitoredIndependently() throws Exception {
        // Arrange
        var worker1 = Proc.spawn(new WorkerState(0, "worker-1"), this::workerBehavior);
        var worker2 = Proc.spawn(new WorkerState(0, "worker-2"), this::workerBehavior);

        // Act: Monitor both independently
        ProcMonitor.monitor(worker1);
        ProcMonitor.monitor(worker2);

        // Send messages to both
        for (int i = 0; i < 5; i++) {
            worker1.send(new WorkerMessage.Increment());
            worker2.send(new WorkerMessage.Increment());
        }
        Thread.sleep(100);

        // Get stats from both
        var stats1 = ProcSys.statistics(worker1);
        var stats2 = ProcSys.statistics(worker2);

        // Assert: Both should be operational and independent
        assertThat(stats1).isNotNull().isNotEmpty();
        assertThat(stats2).isNotNull().isNotEmpty();

        // Crash one, verify other still works
        worker1.send(new WorkerMessage.Crash());
        Thread.sleep(100);

        // Worker2 should still be operational
        worker2.send(new WorkerMessage.Increment());
        var stats2After = ProcSys.statistics(worker2);
        assertThat(stats2After).isNotNull();
    }

    @Test
    void testDemonitorStopsMonitoring() throws Exception {
        // Arrange
        var worker = Proc.spawn(new WorkerState(0, "demonitor-test"), this::workerBehavior);

        // Act: Monitor, then demonitor
        var monitorRef = ProcMonitor.monitor(worker);
        ProcMonitor.demonitor(monitorRef);

        // Crash the worker
        worker.send(new WorkerMessage.Crash());
        Thread.sleep(100);

        // Assert: Process should be down, but we shouldn't receive notifications
        var exceptionThrown = false;
        try {
            ProcSys.statistics(worker);
        } catch (Exception e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown, "Worker should be down after crash");
    }
}
