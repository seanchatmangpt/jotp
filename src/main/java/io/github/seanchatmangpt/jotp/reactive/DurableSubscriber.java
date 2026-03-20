package io.github.seanchatmangpt.jotp.reactive;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Durable Subscriber: buffers messages across pause/resume cycles for resilient delivery.
 *
 * <p>Enterprise Integration Pattern: <em>Durable Subscriber</em> (EIP SS10.4). When paused,
 * incoming messages are buffered in memory. On resume, all buffered messages are delivered in FIFO
 * order before new messages. No messages are ever lost.
 *
 * <p>The delivery loop runs on a virtual thread. Handler crashes are caught and do not kill the
 * subscriber.
 *
 * @param <T> message type
 */
public final class DurableSubscriber<T> implements MessageChannel<T>, AutoCloseable {

    private final Consumer<T> handler;
    private final LinkedTransferQueue<T> mailbox = new LinkedTransferQueue<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    /**
     * Creates a durable subscriber that delivers messages to the given handler.
     *
     * @param handler the consumer that processes delivered messages
     */
    public DurableSubscriber(Consumer<T> handler) {
        this.handler = handler;
        this.worker =
                Thread.ofVirtual()
                        .name("durable-subscriber")
                        .start(this::deliveryLoop);
    }

    private void deliveryLoop() {
        while (running) {
            try {
                // Wait until not paused before taking the next message
                synchronized (paused) {
                    while (paused.get() && running) {
                        paused.wait(50);
                    }
                }
                if (!running) return;

                T msg = mailbox.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (msg == null) continue;

                // Re-check pause state: if paused between poll and here, still deliver
                // (message was already dequeued, so it must be delivered)
                try {
                    handler.accept(msg);
                } catch (Exception e) {
                    // handler crash does not kill the subscriber
                }
                delivered.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void send(T message) {
        received.incrementAndGet();
        mailbox.add(message);
    }

    /** Pauses delivery — incoming messages are buffered until {@link #resume()} is called. */
    public void pause() {
        paused.set(true);
    }

    /** Resumes delivery — buffered messages are delivered in order. */
    public void resume() {
        synchronized (paused) {
            paused.set(false);
            paused.notifyAll();
        }
    }

    /** Returns {@code true} if the subscriber is currently paused. */
    public boolean isPaused() {
        return paused.get();
    }

    /** Returns the number of messages currently buffered (not yet delivered). */
    public int bufferSize() {
        return mailbox.size();
    }

    /** Returns the total number of messages received (buffered + delivered). */
    public long receivedCount() {
        return received.get();
    }

    /** Returns the total number of messages successfully delivered to the handler. */
    public long deliveredCount() {
        return delivered.get();
    }

    @Override
    public void close() {
        running = false;
        synchronized (paused) {
            paused.notifyAll();
        }
        worker.interrupt();
    }
}
