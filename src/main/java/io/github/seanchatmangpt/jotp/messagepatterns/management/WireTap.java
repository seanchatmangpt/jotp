package io.github.seanchatmangpt.jotp.messagepatterns.management;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Consumer;

/**
 * Wire Tap pattern: observes messages flowing through a channel without affecting the primary flow.
 *
 * <p>Enterprise Integration Pattern: <em>Wire Tap</em> (EIP §11.1). A transparent interceptor that
 * copies each message to a secondary destination (typically a logger or monitor) while the primary
 * message flow continues unaffected.
 *
 * <p>Erlang analog: a process that receives a message, sends a copy to a monitor process, then
 * forwards the original unchanged.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * MessageLogger} logs each message and forwards to the actual processor.
 *
 * @param <T> message type
 */
public final class WireTap<T> {

    private final Consumer<T> primary;
    private final Consumer<T> tap;
    private final Proc<Void, T> proc;
    private volatile boolean tapActive = true;

    /**
     * Creates a wire tap.
     *
     * @param primary the primary message destination
     * @param tap the secondary (tap) destination for observation
     */
    public WireTap(Consumer<T> primary, Consumer<T> tap) {
        this.primary = primary;
        this.tap = tap;
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            if (tapActive) {
                                try {
                                    tap.accept(msg);
                                } catch (Exception e) {
                                    // tap failures must not affect primary flow
                                }
                            }
                            primary.accept(msg);
                            return state;
                        });
    }

    /** Send a message through the wire tap. */
    public void send(T message) {
        proc.tell(message);
    }

    /** Activate the tap. */
    public void activate() {
        tapActive = true;
    }

    /** Deactivate the tap (primary flow continues). */
    public void deactivate() {
        tapActive = false;
    }

    /** Returns whether the tap is currently active. */
    public boolean isActive() {
        return tapActive;
    }

    /** Stop the wire tap. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
