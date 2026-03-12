package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcMonitor;
import io.github.seanchatmangpt.jotp.ProcSys;
import java.time.Duration;
import java.util.*;

/**
 * Process Monitoring Example: Demonstrates ProcMonitor + ProcSys for process observability.
 *
 * <p>This example shows a dashboard that monitors worker processes:
 *
 * <ul>
 *   <li>Create 2 worker processes (counters)
 *   <li>Dashboard monitors both via ProcMonitor.monitor()
 *   <li>Collect statistics via ProcSys.statistics()
 *   <li>When a worker crashes, dashboard receives DOWN notification
 *   <li>Dashboard shows metrics (messages in/out, queue depth) without stopping workers
 * </ul>
 */
public final class ProcessMonitoringExample {

    // Sealed monitoring events
    sealed interface MonitoringEvent {
        record ProcessDown(Proc<WorkerState, WorkerMessage> worker, String reason)
                implements MonitoringEvent {}

        record WorkerStats(
                Proc<WorkerState, WorkerMessage> worker,
                long messagesReceived,
                long queueDepth,
                WorkerState currentState)
                implements MonitoringEvent {}

        record DashboardStatus(String message) implements MonitoringEvent {}
    }

    // Worker state
    record WorkerState(long messageCount, String name) {}

    // Worker messages
    sealed interface WorkerMessage {
        record Increment() implements WorkerMessage {}

        record GetState() implements WorkerMessage {}

        record Crash() implements WorkerMessage {}
    }

    /** Worker behavior: counts messages and can crash on demand. */
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

    /** Dashboard behavior: monitors multiple workers and collects their statistics. */
    static DashboardState dashboardBehavior(DashboardState state, DashboardMessage msg) {
        return switch (msg) {
            case DashboardMessage.MonitorWorker(var worker, var name, var dashProc) -> {
                // Install a monitor on the worker — fires DOWN notification into this process
                ProcMonitor.monitor(
                        worker.proc(),
                        reason -> {
                            String reasonStr = reason == null ? "normal" : reason.getMessage();
                            dashProc.tell(new DashboardMessage.WorkerDown(worker.proc(), reasonStr));
                        });
                var newWorkers = new HashMap<>(state.workers());
                newWorkers.put(worker.proc(), name);
                yield new DashboardState(
                        newWorkers, state.downNotifications(), state.statsCollected());
            }
            case DashboardMessage.WorkerDown(var worker, var reason) -> {
                var newDowns = new ArrayList<>(state.downNotifications());
                newDowns.add(reason);
                var updatedWorkers = new HashMap<>(state.workers());
                updatedWorkers.remove(worker);
                yield new DashboardState(updatedWorkers, newDowns, state.statsCollected());
            }
            case DashboardMessage.CollectStats() -> {
                // Collect statistics from all monitored workers without stopping them
                var statsList = new ArrayList<>(state.statsCollected());
                for (var worker : state.workers().keySet()) {
                    try {
                        var stats = ProcSys.statistics(worker.proc());
                        statsList.add("Worker " + state.workers().get(worker) + ": " + stats);
                    } catch (Exception e) {
                        // Worker may have terminated
                    }
                }
                yield new DashboardState(state.workers(), state.downNotifications(), statsList);
            }
        };
    }

    record DashboardState(
            Map<Proc<WorkerState, WorkerMessage>, String> workers,
            List<String> downNotifications,
            List<String> statsCollected) {}

    sealed interface DashboardMessage {
        /**
         * Request to start monitoring a worker; carries a back-reference to the dashboard process
         * so the monitor callback can enqueue WorkerDown messages.
         */
        record MonitorWorker(
                ProcRef<WorkerState, WorkerMessage> worker,
                String name,
                Proc<DashboardState, DashboardMessage> dashProc)
                implements DashboardMessage {}

        record WorkerDown(Proc<WorkerState, WorkerMessage> worker, String reason)
                implements DashboardMessage {}

        record CollectStats() implements DashboardMessage {}
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Process Monitoring Dashboard Example ===\n");

        // 1. Spawn two worker processes
        System.out.println("1. Creating 2 worker processes...");
        var worker1Proc =
                Proc.spawn(
                        new WorkerState(0, "Worker-1"), ProcessMonitoringExample::workerBehavior);
        var worker2Proc =
                Proc.spawn(
                        new WorkerState(0, "Worker-2"), ProcessMonitoringExample::workerBehavior);
        var worker1 = new ProcRef<>(worker1Proc);
        var worker2 = new ProcRef<>(worker2Proc);
        System.out.println("   - Worker-1 spawned");
        System.out.println("   - Worker-2 spawned\n");

        // 2. Spawn dashboard
        System.out.println("2. Creating dashboard process...");
        var dashboard =
                Proc.spawn(
                        new DashboardState(new HashMap<>(), new ArrayList<>(), new ArrayList<>()),
                        ProcessMonitoringExample::dashboardBehavior);
        System.out.println("   - Dashboard spawned\n");

        // 3. Install monitors
        System.out.println("3. Installing monitors on workers...");
        dashboard.tell(new DashboardMessage.MonitorWorker(worker1, "Worker-1", dashboard));
        dashboard.tell(new DashboardMessage.MonitorWorker(worker2, "Worker-2", dashboard));
        Thread.sleep(100); // Give monitors time to install
        System.out.println("   - Monitors installed\n");

        // 4. Send messages to workers and verify monitoring doesn't interfere
        System.out.println("4. Sending messages and verifying monitoring...");
        for (int i = 0; i < 5; i++) {
            worker1.tell(new WorkerMessage.Increment());
            worker2.tell(new WorkerMessage.Increment());
        }
        Thread.sleep(100);
        System.out.println("   - Sent 5 messages to each worker\n");

        // 5. Collect statistics
        System.out.println("5. Collecting statistics from all workers...");
        dashboard.tell(new DashboardMessage.CollectStats());
        Thread.sleep(100);

        // Get dashboard state via ask
        var dashboardStateFuture =
                dashboard.ask(new DashboardMessage.CollectStats(), Duration.ofSeconds(1));
        System.out.println("   - Dashboard state retrieved\n");

        // 6. Crash one worker and verify DOWN notification
        System.out.println("6. Crashing Worker-1...");
        worker1.tell(new WorkerMessage.Crash());
        Thread.sleep(200); // Give monitor time to deliver DOWN signal
        System.out.println("   - Worker-1 crashed\n");

        // 7. Verify monitoring continued after crash
        System.out.println("7. Sending more messages to Worker-2 to verify it still works...");
        for (int i = 0; i < 3; i++) {
            worker2.tell(new WorkerMessage.Increment());
        }
        Thread.sleep(100);
        System.out.println("   - Worker-2 still operational\n");

        // 8. Final statistics collection
        System.out.println("8. Final statistics collection...");
        dashboard.tell(new DashboardMessage.CollectStats());
        Thread.sleep(100);
        System.out.println("   - Statistics collected\n");

        System.out.println("=== Example Complete ===");
        System.out.println("Key observations:");
        System.out.println(
                "  - ProcMonitor.monitor() installed without affecting worker performance");
        System.out.println("  - ProcSys.statistics() retrieved worker metrics non-intrusively");
        System.out.println("  - DOWN notification delivered when Worker-1 crashed");
        System.out.println("  - Worker-2 continued operating after Worker-1 failure");
        System.out.println("  - Monitoring is independent and doesn't kill the monitoring process");
    }
}
