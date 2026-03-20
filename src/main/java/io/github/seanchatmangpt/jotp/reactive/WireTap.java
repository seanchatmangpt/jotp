package io.github.seanchatmangpt.jotp.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Wire Tap: non-intrusively observes messages passing through a channel.
 *
 * <p>Enterprise Integration Pattern: <em>Wire Tap</em> (EIP SS10.6). Each message is forwarded to
 * the primary channel and simultaneously to an observer. The observer receives a copy on a virtual
 * thread so that a slow or crashing observer does not affect primary delivery.
 *
 * <p>Wire taps can be stacked: {@code new WireTap<>(new WireTap<>(primary, tap1), tap2)}.
 *
 * @param <T> message type
 */
public final class WireTap<T> implements MessageChannel<T> {

    private final MessageChannel<T> primary;
    private final Consumer<T> observer;
    private final AtomicBoolean active = new AtomicBoolean(true);

    /**
     * Creates a wire tap that forwards messages to both the primary channel and the observer.
     *
     * @param primary the main channel that always receives the message
     * @param observer the tap consumer that receives a copy (crash-isolated)
     */
    public WireTap(MessageChannel<T> primary, Consumer<T> observer) {
        this.primary = primary;
        this.observer = observer;
    }

    @Override
    public void send(T message) {
        primary.send(message);
        if (active.get()) {
            Thread.ofVirtual()
                    .name("wire-tap")
                    .start(
                            () -> {
                                try {
                                    observer.accept(message);
                                } catch (Exception e) {
                                    // observer crash must not affect primary delivery
                                }
                            });
        }
    }

    /** Deactivates the tap — subsequent messages will not be forwarded to the observer. */
    public void deactivate() {
        active.set(false);
    }

    /** Reactivates the tap after deactivation. */
    public void activate() {
        active.set(true);
    }
}
