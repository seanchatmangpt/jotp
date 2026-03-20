package io.github.seanchatmangpt.jotp.reactive;

import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Consumer;

/**
 * Point-to-Point Channel: exactly one consumer receives each message in FIFO order.
 *
 * <p>Enterprise Integration Pattern: <em>Point-to-Point Channel</em> (EIP SS6.1). Messages are
 * delivered via a virtual-thread-backed mailbox ensuring ordered, single-consumer delivery.
 *
 * @param <T> message type
 */
public final class PointToPointChannel<T> implements MessageChannel<T> {

    private final LinkedTransferQueue<T> mailbox = new LinkedTransferQueue<>();
    private final Consumer<T> handler;
    private final Thread worker;
    private volatile boolean running = true;

    /**
     * Creates a point-to-point channel that delivers each message to the given consumer.
     *
     * @param handler the single consumer that will receive every message
     */
    public PointToPointChannel(Consumer<T> handler) {
        this.handler = handler;
        this.worker =
                Thread.ofVirtual()
                        .name("p2p-channel")
                        .start(this::pollLoop);
    }

    private void pollLoop() {
        while (running) {
            try {
                T msg = mailbox.take();
                handler.accept(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // handler crash does not kill the channel
            }
        }
    }

    @Override
    public void send(T message) {
        mailbox.put(message);
    }

    /** Stops the channel worker thread. */
    public void stop() {
        running = false;
        worker.interrupt();
    }
}
