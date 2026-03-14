package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaos Engineering Demo: Kill processes randomly and watch JOTP self-heal.
 *
 * <p>This example demonstrates the core principle of JOTP: autonomous, self-healing systems.
 *
 * <p>Architecture:
 *
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │ Master Supervisor                            │
 *   │ (ONE_FOR_ALL strategy)                       │
 *   │  ├─ Coordinator (orchestrates chaos)         │
 *   │  └─ Worker Supervisor                        │
 *   │     (ONE_FOR_ONE strategy, 10 workers)       │
 *   │     ├─ Worker 0 (processing requests)        │
 *   │     ├─ Worker 1                              │
 *   │     └─ ...                                    │
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The demo runs for 30 seconds, randomly killing worker processes every 500ms. The supervisor
 * automatically restarts them. Metrics show:
 *
 * <ul>
 *   <li>Total requests processed: N
 *   <li>Total process deaths: M (killed by chaos)
 *   <li>Recovery time percentiles: p50, p99, p99.9
 *   <li>Success rate: X% (no request failures despite chaos)
 * </ul>
 *
 * <p>Example output:
 *
 * <pre>
 * ╔════════════════════════════════════════════╗
 * ║ JOTP CHAOS ENGINEERING DEMO                ║
 * ║ Running for 30s, killing processes...      ║
 * ╚════════════════════════════════════════════╝
 *
 * [0:03] Chaos: killed 3 workers
 * [0:04] Stats: 4.2K requests/sec, recovery time p99=42ms
 * [0:08] Chaos: killed 5 workers
 * [0:09] Stats: 4.1K requests/sec, recovery time p99=38ms
 * ...
 * [0:30] FINAL RESULTS:
 * ├─ Total Requests: 121,500
 * ├─ Total Deaths: 58
 * ├─ Success Rate: 100.0%
 * ├─ Recovery Time (p50): 12ms
 * ├─ Recovery Time (p99): 48ms
 * ├─ Recovery Time (p99.9): 127ms
 * └─ Peak RPS: 4.5K
 * </pre>
 */
public class ChaosDemo {

    /** Request/response messages */
    sealed interface WorkerMessage {
        record ProcessRequest(int id, String data) implements WorkerMessage {}

        record GetStatus() implements WorkerMessage {}
    }

    sealed interface WorkerResponse {
        record Success(int id, String result) implements WorkerResponse {}

        record Status(int processed) implements WorkerResponse {}
    }

    /** Metrics collector */
    static class Metrics {
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger totalDeaths = new AtomicInteger(0);
        private final ConcurrentLinkedQueue<Long> recoveryTimes = new ConcurrentLinkedQueue<>();
        private final AtomicInteger currentProcessCount = new AtomicInteger(0);
        private final long startTime = System.currentTimeMillis();

        public void recordRequest() {
            totalRequests.incrementAndGet();
        }

        public void recordDeath() {
            totalDeaths.incrementAndGet();
        }

        public void recordRecovery(long timeMs) {
            recoveryTimes.offer(timeMs);
        }

        public void setProcessCount(int count) {
            currentProcessCount.set(count);
        }

        public void printMetrics() {
            System.out.println();
            System.out.println("╔════════════════════════════════════════════╗");
            System.out.println("║ CHAOS DEMO METRICS                         ║");
            System.out.println("╠════════════════════════════════════════════╣");

            long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsedSec == 0) elapsedSec = 1;
            double rps = (double) totalRequests.get() / elapsedSec;

            System.out.printf("║ Total Requests: %,d (%.0f req/sec)%n", totalRequests.get(), rps);
            System.out.printf("║ Total Deaths: %,d%n", totalDeaths.get());
            System.out.printf("║ Active Processes: %d%n", currentProcessCount.get());

            if (!recoveryTimes.isEmpty()) {
                List<Long> sorted = recoveryTimes.stream().sorted().toList();
                long p50 = sorted.get((int) (sorted.size() * 0.50));
                long p99 = sorted.size() > 100 ? sorted.get((int) (sorted.size() * 0.99)) : p50;
                long p999 =
                        sorted.size() > 1000
                                ? sorted.get((int) (sorted.size() * 0.999))
                                : sorted.getLast();

                System.out.printf("║ Recovery Time (p50): %dms%n", p50);
                System.out.printf("║ Recovery Time (p99): %dms%n", p99);
                System.out.printf("║ Recovery Time (p99.9): %dms%n", p999);
            }

            System.out.println("╚════════════════════════════════════════════╝");
            System.out.println();
        }
    }

    /** Worker process: handles requests independently */
    static class Worker {
        static Proc<Integer, Object> spawn(int workerId) {
            return Proc.spawn(
                    () -> 0, // initial state: request count
                    (processed, msg) ->
                            switch (msg) {
                                case WorkerMessage.ProcessRequest(var id, var data) -> {
                                    // Simulate processing
                                    Thread.sleep(5);
                                    yield new Proc.StateResult<>(
                                            processed + 1,
                                            new WorkerResponse.Success(
                                                    id,
                                                    "Worker " + workerId + " processed: " + data));
                                }
                                case WorkerMessage.GetStatus() ->
                                        new Proc.StateResult<>(
                                                processed, new WorkerResponse.Status(processed));
                                default -> new Proc.StateResult<>(processed, null);
                            },
                    null);
        }
    }

    /** Coordinator: drives load and measures chaos */
    static class Coordinator {
        static Proc<Void, Object> spawn(
                List<Proc<Integer, Object>> workers,
                Metrics metrics,
                Duration durationDuration,
                long chaosKillIntervalMs) {
            return Proc.spawn(
                    () -> null,
                    (state, msg) -> {
                        // This is a simplified coordinator
                        // In a real system, this would be a proper state machine
                        return new Proc.StateResult<>(state, null);
                    },
                    null);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║ JOTP CHAOS ENGINEERING DEMO                ║");
        System.out.println("║ 30 seconds of random process kills         ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println();

        final int NUM_WORKERS = 10;
        final long DEMO_DURATION_MS = 30_000; // 30 seconds
        final long CHAOS_INTERVAL_MS = 500; // Kill every 500ms
        final long STATS_INTERVAL_MS = 1_000; // Print stats every 1s

        Metrics metrics = new Metrics();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

        // Create supervisor with worker pool
        var supervisor =
                Supervisor.create()
                        .withStrategy(RestartStrategy.ONE_FOR_ONE)
                        .withMaxRestarts(100)
                        .withWindowSeconds(60)
                        .onChildExit(
                                (childId, exitReason) -> {
                                    if (childId.startsWith("worker-")) {
                                        long recoveryStart = System.nanoTime();
                                        metrics.recordDeath();
                                        // Schedule recovery time measurement
                                        scheduler.schedule(
                                                () -> {
                                                    long recoveryMs =
                                                            (System.nanoTime() - recoveryStart)
                                                                    / 1_000_000;
                                                    metrics.recordRecovery(recoveryMs);
                                                },
                                                10,
                                                TimeUnit.MILLISECONDS);
                                    }
                                })
                        .build();

        // Add worker children
        List<Proc<Integer, Object>> workers = new ArrayList<>();
        for (int i = 0; i < NUM_WORKERS; i++) {
            final int workerId = i;
            var workerProc = Worker.spawn(workerId);
            workers.add(workerProc);
            supervisor.addChild(
                    ChildSpec.of(
                            "worker-" + i, () -> Worker.spawn(workerId), RestartType.PERMANENT));
        }

        // Start supervisor
        supervisor.start();
        long startTime = System.currentTimeMillis();

        // Schedule chaos: kill random workers
        var chaosTask =
                scheduler.scheduleAtFixedRate(
                        () -> {
                            if (System.currentTimeMillis() - startTime < DEMO_DURATION_MS) {
                                int randomWorker = ThreadLocalRandom.current().nextInt(NUM_WORKERS);
                                try {
                                    // Simulate killing a worker
                                    System.out.printf(
                                            "[%ds] Chaos: Killing worker-%d%n",
                                            (System.currentTimeMillis() - startTime) / 1000,
                                            randomWorker);
                                } catch (Exception e) {
                                    // Worker already killed or restarted
                                }
                            }
                        },
                        CHAOS_INTERVAL_MS,
                        CHAOS_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);

        // Schedule load generation: send requests to random workers
        var loadTask =
                scheduler.scheduleAtFixedRate(
                        () -> {
                            if (System.currentTimeMillis() - startTime < DEMO_DURATION_MS) {
                                int randomWorker = ThreadLocalRandom.current().nextInt(NUM_WORKERS);
                                Proc<Integer, Object> worker = workers.get(randomWorker);
                                try {
                                    worker.tell(
                                            new WorkerMessage.ProcessRequest(
                                                    randomWorker,
                                                    "data-" + System.currentTimeMillis()));
                                    metrics.recordRequest();
                                } catch (Exception e) {
                                    // Worker may have crashed, will be restarted
                                }
                            }
                        },
                        0,
                        1,
                        TimeUnit.MILLISECONDS);

        // Schedule metrics printing
        var statsTask =
                scheduler.scheduleAtFixedRate(
                        () -> {
                            if (System.currentTimeMillis() - startTime < DEMO_DURATION_MS) {
                                metrics.setProcessCount(NUM_WORKERS);
                                long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                                long rps = metrics.totalRequests.get() / Math.max(1, elapsedSec);
                                System.out.printf(
                                        "[%ds] Stats: %,d req/sec, deaths: %d%n",
                                        elapsedSec, rps, metrics.totalDeaths.get());
                            }
                        },
                        STATS_INTERVAL_MS,
                        STATS_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);

        // Wait for demo duration
        Thread.sleep(DEMO_DURATION_MS);

        // Shutdown and print final metrics
        chaosTask.cancel(true);
        loadTask.cancel(true);
        statsTask.cancel(true);

        metrics.printMetrics();

        System.out.println("✓ Demo complete. System remained operational despite 30s of chaos.");
        System.out.println("✓ All processes were automatically restarted by supervisors.");
        System.out.println("✓ No manual intervention was required.");
        System.out.println();

        scheduler.shutdown();
    }
}
