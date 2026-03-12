package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Consumer;

/**
 * Competing Consumer pattern: multiple consumers compete for messages from a shared queue.
 *
 * <p>Enterprise Integration Pattern: <em>Competing Consumers</em> (EIP §10.3). N worker processes
 * share a single input queue; each message is processed by exactly one worker. This provides
 * natural load balancing and horizontal scaling.
 *
 * <p>Erlang analog: N spawned workers polling the same message queue — only one worker dequeues
 * each message.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, Akka's
 * {@code SmallestMailboxPool(5)} creates 5 instances of {@code WorkConsumer} sharing the workload.
 *
 * @param <T> message/work item type
 */
public final class CompetingConsumer<T> {

    private final LinkedTransferQueue<T> queue = new LinkedTransferQueue<>();
    private final List<Thread> workers;
    private volatile boolean running = true;

    /**
     * Creates a competing consumer pool.
     *
     * @param workerCount number of competing workers
     * @param handler the message processing function
     */
    public CompetingConsumer(int workerCount, Consumer<T> handler) {
        this.workers = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            String name = "competing-consumer-" + i;
            Thread worker =
                    Thread.ofVirtual()
                            .name(name)
                            .start(
                                    () -> {
                                        while (running) {
                                            try {
                                                T item = queue.take();
                                                handler.accept(item);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                break;
                                            }
                                        }
                                    });
            workers.add(worker);
        }
    }

    /** Submit a message to the shared queue. */
    public void submit(T message) {
        queue.add(message);
    }

    /** Returns the number of workers. */
    public int workerCount() {
        return workers.size();
    }

    /** Returns the current queue depth. */
    public int queueDepth() {
        return queue.size();
    }

    /** Stop all workers. */
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
    }
}
