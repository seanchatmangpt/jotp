package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Polling Consumer (Vernon: "Polling Consumer")
 *
 * <p>Periodically polls a message source to check for new messages. Useful for integrating with
 * systems that don't support push notifications.
 *
 * <p>JOTP Implementation: Uses ProcTimer for periodic polling, Proc<S,M> for stateful consumer.
 *
 * <p>Example:
 *
 * <pre>
 * var source = () -> Message.event("DATA", fetchFromDatabase());
 * var consumer = PollingConsumer.create(source, 1000); // poll every 1s
 * // Messages automatically pulled and queued
 * </pre>
 */
public final class PollingConsumer {

    public interface MessageSource {
        Message poll();
    }

    /** Consumer state: queue of polled messages. */
    static class PollerState {
        final Queue<Message> messages = new ConcurrentLinkedQueue<>();
        long lastPoll = System.currentTimeMillis();
        int pollCount = 0;

        void addMessage(Message msg) {
            if (msg != null) {
                messages.offer(msg);
            }
        }

        Message next() {
            return messages.poll();
        }

        int size() {
            return messages.size();
        }
    }

    /** Internal map from ProcRef to its underlying Proc, for state introspection. */
    private static final Map<ProcRef<PollerState, String>, Proc<PollerState, String>> PROC_MAP =
            new IdentityHashMap<>();

    /** Internal map from ProcRef to its TimerRef, for cancellation. */
    private static final Map<ProcRef<PollerState, String>, ProcTimer.TimerRef> TIMER_MAP =
            new IdentityHashMap<>();

    private PollingConsumer() {}

    /**
     * Creates a polling consumer that periodically fetches messages.
     *
     * @param source Message source (typically external system)
     * @param intervalMs Polling interval in milliseconds
     * @return ProcRef for the consumer
     */
    public static ProcRef<PollerState, String> create(MessageSource source, long intervalMs) {

        var state0 = new PollerState();
        var proc =
                new Proc<>(
                        state0,
                        (PollerState state, String msg) -> {
                            if ("POLL".equals(msg)) {
                                var message = source.poll();
                                state.addMessage(message);
                                state.lastPoll = System.currentTimeMillis();
                                state.pollCount++;
                            }
                            return state;
                        });

        // Schedule periodic polling via timer
        var timerRef = ProcTimer.sendInterval(intervalMs, proc, "POLL");

        // Use Supervisor to get a ProcRef (Supervisor creates ProcRef internally)
        var supervisor =
                new Supervisor(
                        Supervisor.Strategy.ONE_FOR_ONE, 5, java.time.Duration.ofSeconds(60));
        ProcRef<PollerState, String> consumer =
                supervisor.supervise(
                        "polling-consumer",
                        state0,
                        (PollerState state, String msg) -> {
                            if ("POLL".equals(msg)) {
                                var message = source.poll();
                                state.addMessage(message);
                                state.lastPoll = System.currentTimeMillis();
                                state.pollCount++;
                            }
                            return state;
                        });

        synchronized (PROC_MAP) {
            PROC_MAP.put(consumer, proc);
        }
        synchronized (TIMER_MAP) {
            TIMER_MAP.put(consumer, timerRef);
        }

        return consumer;
    }

    /**
     * Retrieves next polled message (non-blocking).
     *
     * @param consumer The consumer ProcRef
     * @return Next message or null if queue empty
     */
    public static Message getNext(ProcRef<PollerState, String> consumer) {
        Proc<PollerState, String> proc;
        synchronized (PROC_MAP) {
            proc = PROC_MAP.get(consumer);
        }
        if (proc == null) return null;
        var state = ProcSys.getState(proc).join();
        return state.next();
    }

    /**
     * Gets number of pending messages.
     *
     * @param consumer The consumer ProcRef
     * @return Queue size
     */
    public static int pendingCount(ProcRef<PollerState, String> consumer) {
        Proc<PollerState, String> proc;
        synchronized (PROC_MAP) {
            proc = PROC_MAP.get(consumer);
        }
        if (proc == null) return 0;
        var state = ProcSys.getState(proc).join();
        return state.size();
    }

    /**
     * Gets polling statistics.
     *
     * @param consumer The consumer ProcRef
     * @return Formatted stats string
     */
    public static String getStats(ProcRef<PollerState, String> consumer) {
        Proc<PollerState, String> proc;
        synchronized (PROC_MAP) {
            proc = PROC_MAP.get(consumer);
        }
        if (proc == null) return "polls=0, pending=0, lastPoll=0";
        var state = ProcSys.getState(proc).join();
        return String.format(
                "polls=%d, pending=%d, lastPoll=%d",
                state.pollCount, state.size(), System.currentTimeMillis() - state.lastPoll);
    }

    /**
     * Stops polling.
     *
     * @param consumer The consumer ProcRef
     */
    public static void stop(ProcRef<PollerState, String> consumer) {
        // Cancel the timer
        ProcTimer.TimerRef timerRef;
        synchronized (TIMER_MAP) {
            timerRef = TIMER_MAP.remove(consumer);
        }
        if (timerRef != null) {
            ProcTimer.cancel(timerRef);
        }
    }
}
