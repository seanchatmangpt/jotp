package io.github.seanchatmangpt.jotp.messaging.system;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Dead Letter Channel — captures and stores undeliverable or failed messages for inspection,
 * replay, or alerting.
 *
 * <p>Implements the Dead Letter Channel EIP pattern (Hohpe &amp; Woolf, ch. 3). When a message
 * cannot be processed successfully, it is routed here with its failure reason and arrival
 * timestamp.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var dlc = DeadLetterChannel.<OrderMsg>create();
 *
 * dlc.onFailure(msg, "Insufficient stock");
 *
 * var dead = dlc.drain();
 * dead.forEach(dl -> log.warn("Dead: {} - {}", dl.message(), dl.reason()));
 * }</pre>
 *
 * @param <M> the message type
 */
public final class DeadLetterChannel<M> {

    private final ConcurrentLinkedDeque<DeadLetter<M>> letters = new ConcurrentLinkedDeque<>();

    private DeadLetterChannel() {}

    /**
     * Creates a new DeadLetterChannel.
     *
     * @param <M> the message type
     * @return a new empty channel
     */
    public static <M> DeadLetterChannel<M> create() {
        return new DeadLetterChannel<>();
    }

    /**
     * Routes a failed message to this channel with the given reason.
     *
     * @param message the failed message
     * @param reason human-readable description of the failure
     */
    public void onFailure(M message, String reason) {
        letters.addLast(new DeadLetter<>(message, reason, Instant.now()));
    }

    /**
     * Alias for {@link #onFailure(Object, String)} — routes a message with a failure reason.
     *
     * @param message the failed message
     * @param reason human-readable description of the failure
     */
    public void send(M message, String reason) {
        onFailure(message, reason);
    }

    /**
     * Returns the number of dead letters currently accumulated.
     *
     * @return dead letter count
     */
    public int size() {
        return letters.size();
    }

    /**
     * Atomically removes and returns all accumulated dead letters.
     *
     * @return all dead letters; empty list if none
     */
    public List<DeadLetter<M>> drain() {
        var result = new ArrayList<DeadLetter<M>>(letters.size());
        DeadLetter<M> letter;
        while ((letter = letters.pollFirst()) != null) {
            result.add(letter);
        }
        return result;
    }

    /**
     * Removes and returns dead letters whose reason contains the given substring, leaving other
     * letters in place.
     *
     * @param reasonSubstring substring to match against each dead letter's reason
     * @return matching dead letters
     */
    public List<DeadLetter<M>> drainByReason(String reasonSubstring) {
        var matched = new ArrayList<DeadLetter<M>>();
        var remaining = new ArrayList<DeadLetter<M>>();
        for (var dl : drain()) {
            if (dl.reason().contains(reasonSubstring)) {
                matched.add(dl);
            } else {
                remaining.add(dl);
            }
        }
        // Re-add non-matching letters in original order
        remaining.forEach(letters::addLast);
        return matched;
    }

    /**
     * Removes and returns dead letters that arrived at or after the given instant, leaving earlier
     * letters in place.
     *
     * @param since the cutoff instant (inclusive)
     * @return dead letters that arrived at or after {@code since}
     */
    public List<DeadLetter<M>> drainSince(Instant since) {
        var matched = new ArrayList<DeadLetter<M>>();
        var remaining = new ArrayList<DeadLetter<M>>();
        for (var dl : drain()) {
            if (!dl.arrivedAt().isBefore(since)) {
                matched.add(dl);
            } else {
                remaining.add(dl);
            }
        }
        remaining.forEach(letters::addLast);
        return matched;
    }

    /**
     * Inspects the oldest dead letter without removing it.
     *
     * @return the oldest dead letter, or {@code null} if the channel is empty
     */
    public DeadLetter<M> peek() {
        return letters.peekFirst();
    }

    /**
     * Discards all accumulated dead letters.
     */
    public void clear() {
        letters.clear();
    }

    /**
     * Returns a message handler {@link BiFunction} that wraps the given consumer. When the consumer
     * throws an exception, the offending message and the exception message are routed to this
     * dead letter channel. The state (this channel) is returned unchanged, making it compatible
     * with {@link io.github.seanchatmangpt.jotp.Supervisor#supervise}.
     *
     * <p>Usage with a supervisor:
     *
     * <pre>{@code
     * var handler = supervisor.supervise("handler", dlc, dlc.withCrashHandler(msg -> process(msg)));
     * }</pre>
     *
     * @param consumer the processing logic; exceptions are caught and routed to DLC
     * @return a BiFunction suitable for passing to {@code Supervisor.supervise}
     */
    public BiFunction<DeadLetterChannel<M>, M, DeadLetterChannel<M>> withCrashHandler(
            Consumer<M> consumer) {
        return (state, msg) -> {
            try {
                consumer.accept(msg);
            } catch (Exception ex) {
                state.onFailure(msg, ex.getMessage() != null ? ex.getMessage() : ex.toString());
            }
            return state;
        };
    }

    /**
     * An envelope holding an undeliverable message together with its failure reason and arrival
     * time.
     *
     * @param message the failed message payload
     * @param reason human-readable failure description
     * @param arrivedAt when this dead letter was created
     * @param <M> the message type
     */
    public record DeadLetter<M>(M message, String reason, Instant arrivedAt) {}
}
