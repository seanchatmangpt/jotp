import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus.FrameworkEvent.ProcessCreated;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleThroughputTest {

    public static void main(String[] args) throws Exception {
        System.out.println("JOTP Throughput Benchmark - Java 26");
        System.out.println("=====================================\n");

        // Test 1: Baseline Proc throughput
        System.out.println("Test 1: Baseline Proc Message Throughput");
        System.out.println("-----------------------------------------");
        testBaselineProcThroughput();

        // Test 2: EventBus publish throughput
        System.out.println("\nTest 2: FrameworkEventBus Publish Throughput");
        System.out.println("---------------------------------------------");
        testEventBusThroughput();

        // Test 3: Subscriber scalability
        System.out.println("\nTest 3: Subscriber Scalability");
        System.out.println("-------------------------------");
        testSubscriberScalability();

        System.out.println("\n=== BENCHMARK COMPLETE ===");
    }

    private static void testBaselineProcThroughput() throws Exception {
        int messageCount = 1_000_000;
        AtomicLong received = new AtomicLong(0);

        Proc<Integer, Integer> proc = Proc.spawn(
            () -> 0,
            (state, msg) -> {
                received.incrementAndGet();
                return state + 1;
            }
        );

        long start = System.nanoTime();
        for (int i = 0; i < messageCount; i++) {
            proc.tell("msg-" + i);
        }
        long end = System.nanoTime();

        // Wait for processing
        Thread.sleep(100);
        long elapsedMs = (end - start) / 1_000_000;
        double opsPerSec = (messageCount * 1000.0) / elapsedMs;

        System.out.println("Messages sent:     " + messageCount);
        System.out.println("Messages received: " + received.get());
        System.out.println("Elapsed time:      " + elapsedMs + " ms");
        System.out.println("Throughput:        " + String.format("%.2f", opsPerSec) + " ops/sec");
    }

    private static void testEventBusThroughput() throws Exception {
        int eventCount = 100_000;
        AtomicLong received = new AtomicLong(0);

        FrameworkEventBus eventBus = FrameworkEventBus.create();
        eventBus.subscribe(event -> received.incrementAndGet());

        long start = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            eventBus.publish(new ProcessCreated(Instant.now(), "proc-" + i, "test"));
        }
        long end = System.nanoTime();

        // Wait for async processing
        Thread.sleep(500);
        long elapsedMs = (end - start) / 1_000_000;
        double opsPerSec = (eventCount * 1000.0) / elapsedMs;

        System.out.println("Events published:  " + eventCount);
        System.out.println("Events received:   " + received.get());
        System.out.println("Elapsed time:      " + elapsedMs + " ms");
        System.out.println("Throughput:        " + String.format("%.2f", opsPerSec) + " ops/sec");
    }

    private static void testSubscriberScalability() throws Exception {
        int[] subscriberCounts = {1, 10, 50, 100};
        int eventsPerTest = 10_000;

        System.out.println("Testing " + eventsPerTest + " events per subscriber count\n");

        for (int numSubscribers : subscriberCounts) {
            FrameworkEventBus eventBus = FrameworkEventBus.create();
            List<AtomicLong> counters = new ArrayList<>();

            for (int i = 0; i < numSubscribers; i++) {
                AtomicLong counter = new AtomicLong(0);
                counters.add(counter);
                eventBus.subscribe(event -> counter.incrementAndGet());
            }

            long start = System.nanoTime();
            for (int i = 0; i < eventsPerTest; i++) {
                eventBus.publish(new ProcessCreated(Instant.now(), "proc-" + i, "test"));
            }
            long end = System.nanoTime();

            // Wait for processing
            Thread.sleep(500);

            long elapsedMs = (end - start) / 1_000_000;
            double opsPerSec = (eventsPerTest * 1000.0) / elapsedMs;

            long totalReceived = counters.stream().mapToLong(AtomicLong::get).sum();
            double avgReceived = (double) totalReceived / numSubscribers;

            System.out.println(String.format(
                "Subscribers: %3d | Time: %4d ms | Throughput: %8.2f ops/sec | Avg received/sub: %.0f",
                numSubscribers, elapsedMs, opsPerSec, avgReceived
            ));
        }
    }
}
