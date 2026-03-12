package io.github.seanchatmangpt.jotp.dogfood.otp;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcMonitor;
import io.github.seanchatmangpt.jotp.ProcSys;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests for ProcessMonitoringExample demonstrating ProcMonitor + ProcSys observability. */
@Timeout(10)
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

        // Act: Install monitor with a down handler
        ProcMonitor.monitor(worker, reason -> downReceived.countDown());

        // Crash the worker
        worker.tell(new WorkerMessage.Crash());

        // Assert: DOWN notification should be delivered within 2 seconds
        assertThat(downReceived.await(2, TimeUnit.SECONDS)).isTrue();
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

        worker.stop();
    }

    @Test
    void testMonitoringDoesntInterfereWithWorkerOperation() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "interference-test"),
                        ProcessMonitoringExampleTest::workerBehavior);

        // Act: Install monitor and send messages
        ProcMonitor.monitor(worker, reason -> {});
        var messageCount = 10;
        for (int i = 0; i < messageCount; i++) {
            worker.tell(new WorkerMessage.Increment());
        }
        Thread.sleep(100);

        // Get stats to verify all messages were processed
        var stats = ProcSys.statistics(worker);

        // Assert: Worker should be responsive and stats should reflect monitoring
        assertThat(stats).isNotNull();
        assertThat(stats.messagesIn()).isGreaterThanOrEqualTo(10);

        worker.stop();
    }

    @Test
    void testDownNotificationContainsCrashReason() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "crash-reason-test"),
                        ProcessMonitoringExampleTest::workerBehavior);
        var crashReason = new AtomicReference<Throwable>();
        var downReceived = new CountDownLatch(1);

        // Act: Monitor and crash
        ProcMonitor.monitor(
                worker,
                reason -> {
                    crashReason.set(reason);
                    downReceived.countDown();
                });
        worker.tell(new WorkerMessage.Crash());

        // Assert: Crash should be detected with a non-null reason
        assertThat(downReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(crashReason.get()).isNotNull();
        assertThat(crashReason.get().getMessage()).contains("crashed");
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
        var worker1Down = new CountDownLatch(1);

        // Act: Monitor both independently
        ProcMonitor.monitor(worker1, reason -> worker1Down.countDown());
        ProcMonitor.monitor(worker2, reason -> {});

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

        // Crash worker1, verify worker2 still works
        worker1.tell(new WorkerMessage.Crash());
        assertThat(worker1Down.await(2, TimeUnit.SECONDS)).isTrue();

        // Worker2 should still be operational
        worker2.tell(new WorkerMessage.Increment());
        Thread.sleep(50);
        var stats2After = ProcSys.statistics(worker2);
        assertThat(stats2After.messagesIn()).isGreaterThan(stats2.messagesIn());

        worker2.stop();
    }

    @Test
    void testDemonitorStopsMonitoring() throws Exception {
        // Arrange
        var worker =
                Proc.spawn(
                        new WorkerState(0, "demonitor-test"),
                        ProcessMonitoringExampleTest::workerBehavior);
        var downReceived = new CountDownLatch(1);

        // Act: Monitor, then demonitor
        var monitorRef = ProcMonitor.monitor(worker, reason -> downReceived.countDown());
        ProcMonitor.demonitor(monitorRef);

        // Crash the worker
        worker.tell(new WorkerMessage.Crash());

        // Assert: DOWN notification should NOT be received after demonitor
        // Wait 500ms to confirm latch stays at 1 (not counted down)
        assertThat(downReceived.await(500, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(downReceived.getCount()).isEqualTo(1);

        // Wait for the worker thread to actually die
        await().atMost(Duration.ofSeconds(2)).until(() -> !worker.thread().isAlive());
    }
}
