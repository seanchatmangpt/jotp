package io.github.seanchatmangpt.jotp.messagepatterns.transformation;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Content Filter pattern: removes unwanted fields from a message, producing a lighter version.
 *
 * <p>Enterprise Integration Pattern: <em>Content Filter</em> (EIP §7.2). Strips unnecessary data
 * from a large payload, keeping only the fields the receiver needs.
 *
 * <p>Erlang analog: pattern matching to extract only needed fields — {@code receive {msg, _, B, _,
 * D} -> handle(B, D) end}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * MessageContentFilter} receives an {@code UnfilteredPayload} and extracts only the essential
 * fields into a {@code FilteredMessage}.
 *
 * @param <A> unfiltered (large) message type
 * @param <B> filtered (lean) message type
 */
public final class ContentFilter<A, B> {

    private final Function<A, B> filterFunction;
    private final Proc<Void, A> proc;

    /**
     * Creates a content filter.
     *
     * @param filterFunction extracts essential fields from the unfiltered message
     * @param destination receives the filtered message
     */
    public ContentFilter(Function<A, B> filterFunction, Consumer<B> destination) {
        this.filterFunction = filterFunction;
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            B filtered = filterFunction.apply(msg);
                            destination.accept(filtered);
                            return state;
                        });
    }

    /** Filter and forward a message. */
    public void filter(A message) {
        proc.tell(message);
    }

    /** Apply the filter synchronously without sending. */
    public B apply(A message) {
        return filterFunction.apply(message);
    }

    /** Stop the filter. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
