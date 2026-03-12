package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Recipient List pattern: routes a message to a dynamically computed list of recipients.
 *
 * <p>Enterprise Integration Pattern: <em>Recipient List</em> (EIP §8.4). Unlike a static router,
 * the recipient list is computed per-message based on message content.
 *
 * <p>Erlang analog: dynamic fan-out — compute a list of Pids from message content, then send to
 * each: {@code [Pid ! Msg || Pid <- compute_recipients(Msg)]}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * MountaineeringSuppliesOrderProcessor} computes recipient quote providers based on total retail
 * price falling within each provider's range.
 *
 * @param <T> message type
 */
public final class RecipientList<T> {

    /** A registered recipient with its interest predicate. */
    public record Recipient<T>(String id, Consumer<T> consumer,
            java.util.function.Predicate<T> interested) {}

    private final List<Recipient<T>> recipients = new ArrayList<>();

    /**
     * Register a recipient interested in messages matching the predicate.
     *
     * @param id recipient identifier
     * @param consumer the message handler
     * @param interested predicate determining interest
     */
    public void register(
            String id,
            Consumer<T> consumer,
            java.util.function.Predicate<T> interested) {
        recipients.add(new Recipient<>(id, consumer, interested));
    }

    /**
     * Route a message to all interested recipients.
     *
     * @param message the message to route
     * @return the number of recipients that received the message
     */
    public int route(T message) {
        int count = 0;
        for (Recipient<T> recipient : recipients) {
            if (recipient.interested().test(message)) {
                recipient.consumer().accept(message);
                count++;
            }
        }
        return count;
    }

    /** Returns the total number of registered recipients. */
    public int recipientCount() {
        return recipients.size();
    }
}
