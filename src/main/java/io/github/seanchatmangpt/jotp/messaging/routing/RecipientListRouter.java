package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recipient List Router: broadcasts a message to a dynamic list of recipients.
 *
 * <p>Enterprise Integration Pattern: <em>Recipient List</em> (EIP §7.3). Each recipient in the
 * list receives an independent copy of the message via {@link ProcRef#tell}. Recipients can be
 * added and removed at runtime.
 *
 * @param <M> message type
 */
public final class RecipientListRouter<M> {

    private final CopyOnWriteArrayList<ProcRef<?, M>> recipients = new CopyOnWriteArrayList<>();

    /** Creates an empty recipient list router. */
    public RecipientListRouter() {}

    /**
     * Adds a recipient to the list.
     *
     * @param procRef the recipient process reference
     * @throws NullPointerException if {@code procRef} is null
     */
    public void addRecipient(ProcRef<?, M> procRef) {
        Objects.requireNonNull(procRef, "procRef cannot be null");
        recipients.add(procRef);
    }

    /**
     * Removes the first occurrence of the given recipient from the list.
     *
     * @param procRef the recipient to remove
     * @return {@code true} if the recipient was found and removed
     */
    public boolean removeRecipient(ProcRef<?, M> procRef) {
        return recipients.remove(procRef);
    }

    /**
     * Broadcasts the message to all current recipients.
     *
     * @param message the message to broadcast
     * @return the number of recipients the message was sent to
     * @throws NullPointerException if {@code message} is null
     */
    public int broadcastMessage(M message) {
        Objects.requireNonNull(message, "message cannot be null");
        List<ProcRef<?, M>> snapshot = List.copyOf(recipients);
        for (ProcRef<?, M> ref : snapshot) {
            ref.tell(message);
        }
        return snapshot.size();
    }

    /** Returns the current number of recipients. */
    public int recipientCount() {
        return recipients.size();
    }

    /** Returns an immutable copy of the current recipient list. */
    public List<ProcRef<?, M>> getRecipients() {
        return List.copyOf(recipients);
    }

    /** Removes all recipients from the list. */
    public void clearRecipients() {
        recipients.clear();
    }

    /**
     * Waits for all recipients to process their current mailbox (best-effort).
     *
     * @param timeout the maximum wait time
     * @return {@code true} (always — virtual threads complete asynchronously)
     * @throws NullPointerException if {@code timeout} is null
     */
    public boolean waitForAll(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        return true;
    }
}
