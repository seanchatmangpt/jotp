package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcTimer;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Polling Consumer pattern: the consumer actively pulls messages from a source on a schedule,
 * rather than having messages pushed to it.
 *
 * <p>Enterprise Integration Pattern: <em>Polling Consumer</em> (EIP §10.2). The consumer controls
 * the rate of message retrieval, enabling backpressure and flow control.
 *
 * <p>Erlang analog: a process that periodically sends itself a {@code poll} message via {@code
 * timer:send_interval/2}, then fetches work from a provider.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * WorkConsumer} polls a {@code WorkItemsProvider} for batches of work items.
 *
 * @param <T> message/work item type
 */
public final class PollingConsumer<T> {

    private final Supplier<T> source;
    private final Consumer<T> handler;
    private final Proc<Long, String> proc;
    private volatile ProcTimer.TimerRef timerRef;

    /**
     * Creates a polling consumer.
     *
     * @param source supplier of messages (returns null when no work available)
     * @param handler processes each polled message
     * @param pollInterval how often to poll
     */
    public PollingConsumer(Supplier<T> source, Consumer<T> handler, Duration pollInterval) {
        this.source = source;
        this.handler = handler;
        this.proc =
                new Proc<>(
                        0L,
                        (count, msg) -> {
                            T item = source.get();
                            if (item != null) {
                                handler.accept(item);
                                return count + 1;
                            }
                            return count;
                        });
        this.timerRef = ProcTimer.sendInterval(pollInterval.toMillis(), proc, "poll");
    }

    /** Stop polling and shut down the consumer. */
    public void stop() {
        if (timerRef != null) {
            ProcTimer.cancel(timerRef);
        }
        try {
            proc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns the underlying Proc for monitoring. */
    public Proc<Long, String> proc() {
        return proc;
    }
}
