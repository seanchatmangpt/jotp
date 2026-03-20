package io.github.seanchatmangpt.jotp.reactive;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Polling Consumer: pull-based message retrieval at a configurable interval.
 *
 * <p>Enterprise Integration Pattern: <em>Polling Consumer</em> (EIP SS10.2). The consumer controls
 * the retrieval rate by polling a queue at fixed intervals, decoupling the producer's speed from the
 * consumer's processing capacity.
 *
 * <p>The polling loop runs on a virtual thread. Handler crashes are caught and do not stop the loop.
 *
 * @param <T> message type
 */
public final class PollingConsumer<T> implements MessageChannel<T>, AutoCloseable {

    private final BlockingQueue<T> queue;
    private final Consumer<T> handler;
    private final Duration pollInterval;
    private final AtomicLong polled = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    /**
     * Creates a polling consumer with an internal queue.
     *
     * @param handler the message handler invoked for each polled message
     * @param pollInterval the interval between poll attempts
     */
    public PollingConsumer(Consumer<T> handler, Duration pollInterval) {
        this(new LinkedTransferQueue<>(), handler, pollInterval);
    }

    /**
     * Creates a polling consumer backed by an external queue.
     *
     * @param queue the external blocking queue to poll from
     * @param handler the message handler invoked for each polled message
     * @param pollInterval the interval between poll attempts
     */
    public PollingConsumer(BlockingQueue<T> queue, Consumer<T> handler, Duration pollInterval) {
        this.queue = queue;
        this.handler = handler;
        this.pollInterval = pollInterval;
        this.worker =
                Thread.ofVirtual()
                        .name("polling-consumer")
                        .start(this::pollLoop);
    }

    private void pollLoop() {
        while (running) {
            try {
                T msg = queue.poll(pollInterval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (msg != null) {
                    try {
                        handler.accept(msg);
                    } catch (Exception e) {
                        // handler crash does not stop the polling loop
                    }
                    polled.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void send(T message) {
        queue.add(message);
    }

    /** Returns the total number of messages successfully polled (regardless of handler outcome). */
    public long polledCount() {
        return polled.get();
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
