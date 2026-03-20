package io.github.seanchatmangpt.jotp.messaging.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedTransferQueue;

/**
 * Resequencer pattern (EIP): reorders messages that arrive out of sequence.
 *
 * <p>Messages are buffered until the expected sequence number arrives. When the expected message
 * arrives, it — and any consecutive buffered messages — are released in order.
 *
 * <p>A timeout-based mechanism prevents the buffer from blocking indefinitely when a gap exists.
 * When a new message arrives after the timeout has elapsed since the last in-order delivery, the
 * buffer is flushed (gaps are skipped) and the expected sequence advanced.
 *
 * @param <M> the message payload type
 */
public final class Resequencer<M> {

    /** A message with an associated sequence number. */
    public record SequencedMessage<M>(long sequenceNumber, M payload) {}

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final Duration timeout;

    private long nextExpected;
    private Instant lastDelivery = Instant.now();

    private final TreeMap<Long, M> buffer = new TreeMap<>();

    /** Virtual thread processing incoming messages. */
    private final Thread processorThread;

    private final LinkedTransferQueue<Object> inbox = new LinkedTransferQueue<>();

    private volatile boolean stopped = false;

    private record OfferRequest<M>(SequencedMessage<M> msg, CompletableFuture<Optional<M>> reply) {}

    private Resequencer(long startSequence, Duration timeout, int capacity) {
        this.nextExpected = startSequence;
        this.timeout = timeout;
        this.capacity = capacity;
        this.processorThread = Thread.ofVirtual().name("resequencer").start(this::loop);
    }

    /**
     * Create a Resequencer with default timeout and capacity.
     *
     * @param startSequence the first expected sequence number
     * @param <M> message payload type
     * @return new Resequencer
     */
    public static <M> Resequencer<M> create(long startSequence) {
        return new Resequencer<>(startSequence, DEFAULT_TIMEOUT, DEFAULT_CAPACITY);
    }

    /**
     * Create a Resequencer with custom timeout and capacity.
     *
     * @param startSequence the first expected sequence number
     * @param timeout maximum wait time for a missing sequence number
     * @param capacity maximum number of buffered (out-of-order) messages
     * @param <M> message payload type
     * @return new Resequencer
     */
    public static <M> Resequencer<M> create(long startSequence, Duration timeout, int capacity) {
        return new Resequencer<>(startSequence, timeout, capacity);
    }

    /**
     * Offer a sequenced message for resequencing.
     *
     * <p>Returns a future that resolves to:
     *
     * <ul>
     *   <li>{@code Optional.of(payload)} if the message was delivered in-order
     *   <li>{@code Optional.empty()} if the message was buffered awaiting earlier messages, or
     *       rejected due to buffer capacity
     * </ul>
     *
     * @param msg the sequenced message
     * @return a future resolving to the delivery result
     */
    @SuppressWarnings("unchecked")
    public <T extends M> CompletableFuture<Optional<T>> offer(SequencedMessage<T> msg) {
        CompletableFuture<Optional<M>> future = new CompletableFuture<>();
        inbox.add(new OfferRequest<>(msg, future));
        return future.thenApply(opt -> (Optional<T>) opt);
    }

    /** Stop the resequencer and release the virtual thread. */
    public void stop() throws InterruptedException {
        stopped = true;
        processorThread.interrupt();
        processorThread.join();
    }

    @SuppressWarnings("unchecked")
    private void loop() {
        while (!stopped) {
            try {
                Object item = inbox.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (item == null) {
                    continue;
                }
                if (item instanceof OfferRequest<?> req) {
                    processOffer((OfferRequest<M>) req);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processOffer(OfferRequest<M> req) {
        SequencedMessage<M> msg = (SequencedMessage<M>) req.msg();
        CompletableFuture<Optional<M>> reply = req.reply();
        long seq = msg.sequenceNumber();

        // Timeout check: if timeout elapsed since last delivery, advance next expected past gaps
        Duration elapsed = Duration.between(lastDelivery, Instant.now());
        if (elapsed.compareTo(timeout) > 0 && !buffer.isEmpty()) {
            // Flush buffer: skip to next available in buffer
            Long firstBuffered = buffer.firstKey();
            nextExpected = firstBuffered;
            lastDelivery = Instant.now();
        }

        if (seq == nextExpected) {
            // In-order delivery
            nextExpected++;
            lastDelivery = Instant.now();
            reply.complete(Optional.of(msg.payload()));
            // Release consecutive buffered messages (but don't reply to them — they already got empty)
            while (buffer.containsKey(nextExpected)) {
                buffer.remove(nextExpected);
                nextExpected++;
            }
        } else if (seq < nextExpected) {
            // Already delivered (duplicate) — return empty
            reply.complete(Optional.empty());
        } else {
            // Out of order — buffer if capacity allows
            if (buffer.size() < capacity) {
                buffer.put(seq, msg.payload());
            }
            // Return empty (buffered or rejected)
            reply.complete(Optional.empty());
        }
    }
}
