package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Idempotent Receiver pattern: ensures that duplicate messages are processed only once.
 *
 * <p>Enterprise Integration Pattern: <em>Idempotent Receiver</em> (EIP §10.5). Maintains a set of
 * already-processed message IDs; duplicates are silently dropped.
 *
 * <p>Erlang analog: a {@code gen_server} maintaining a set of processed message references — {@code
 * case sets:is_element(Id, Processed) of true -> skip; false -> handle end}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * Account} stores transaction IDs and ignores duplicate deposits/withdrawals.
 *
 * @param <T> message type
 * @param <K> deduplication key type (usually String or UUID)
 */
public final class IdempotentReceiver<T, K> {

    private final Function<T, K> keyExtractor;
    private final Consumer<T> handler;
    private final Proc<Set<K>, T> proc;

    /**
     * Creates an idempotent receiver.
     *
     * @param keyExtractor extracts a deduplication key from each message
     * @param handler processes non-duplicate messages
     */
    public IdempotentReceiver(Function<T, K> keyExtractor, Consumer<T> handler) {
        this.keyExtractor = keyExtractor;
        this.handler = handler;
        this.proc = new Proc<>(new HashSet<>(), (processed, msg) -> {
            K key = keyExtractor.apply(msg);
            if (processed.contains(key)) {
                return processed;
            }
            handler.accept(msg);
            var updated = new HashSet<>(processed);
            updated.add(key);
            return updated;
        });
    }

    /** Send a message (duplicates will be silently dropped). */
    public void send(T message) {
        proc.tell(message);
    }

    /** Stop the receiver. */
    public void stop() {
        proc.stop();
    }
}
