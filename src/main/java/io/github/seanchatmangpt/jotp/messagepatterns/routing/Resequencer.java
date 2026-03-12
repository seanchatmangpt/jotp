package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Resequencer pattern: reorders out-of-sequence messages back into their correct order.
 *
 * <p>Enterprise Integration Pattern: <em>Resequencer</em> (EIP §8.7). Buffers out-of-order messages
 * by sequence number and dispatches them in order when a contiguous sequence is available.
 *
 * <p>Erlang analog: a {@code gen_server} maintaining a TreeMap of buffered messages, dispatching
 * contiguous sequences starting from the expected next index.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * ResequencerConsumer} buffers {@code SequencedMessage} instances by correlation ID and dispatches
 * when contiguous.
 *
 * @param <T> message type
 */
public final class Resequencer<T> {

    /** A sequenced message with correlation key and sequence index. */
    public record Sequenced<T>(String correlationId, int index, int total, T payload) {}

    private record State<T>(
            Map<String, TreeMap<Integer, T>> buffers, Map<String, Integer> expectedNext) {}

    private final Consumer<T> consumer;
    private final Proc<State<T>, Sequenced<T>> proc;

    /**
     * Creates a resequencer.
     *
     * @param consumer receives messages in correct order
     */
    public Resequencer(Consumer<T> consumer) {
        this.consumer = consumer;
        this.proc =
                new Proc<>(
                        new State<>(new HashMap<>(), new HashMap<>()),
                        (state, msg) -> {
                            var buffers = new HashMap<>(state.buffers());
                            var expectedNext = new HashMap<>(state.expectedNext());

                            String key = msg.correlationId();
                            buffers.computeIfAbsent(key, k -> new TreeMap<>());
                            buffers.get(key).put(msg.index(), msg.payload());

                            int expected = expectedNext.getOrDefault(key, 0);
                            TreeMap<Integer, T> buffer = buffers.get(key);

                            var dispatched = new ArrayList<T>();
                            while (buffer.containsKey(expected)) {
                                dispatched.add(buffer.remove(expected));
                                expected++;
                            }
                            expectedNext.put(key, expected);

                            for (T item : dispatched) {
                                consumer.accept(item);
                            }

                            if (expected >= msg.total()) {
                                buffers.remove(key);
                                expectedNext.remove(key);
                            }

                            return new State<>(buffers, expectedNext);
                        });
    }

    /** Submit an out-of-order sequenced message. */
    public void submit(Sequenced<T> message) {
        proc.tell(message);
    }

    /** Stop the resequencer. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
