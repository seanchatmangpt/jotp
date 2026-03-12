package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Consumer;

/**
 * Datatype Channel pattern: separate channels for each message type, ensuring type-safe delivery.
 *
 * <p>Enterprise Integration Pattern: <em>Datatype Channel</em> (EIP §6.1). Each channel carries
 * only one message type — the receiver knows exactly what type to expect without runtime checks.
 *
 * <p>Erlang analog: separate registered processes for each message type — e.g., {@code
 * product_queries}, {@code price_quotes}, {@code purchase_orders} — each with a typed receive
 * clause.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original,
 * RabbitMQ queues are typed channels with binary-to-domain translation.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * record ProductQuery(String productId) {}
 * record PriceQuote(String productId, double price) {}
 *
 * var queryChannel = DatatypeChannel.create(ProductQuery.class, q ->
 *     System.out.println("Query: " + q.productId()));
 * var quoteChannel = DatatypeChannel.create(PriceQuote.class, q ->
 *     System.out.println("Quote: " + q.price()));
 *
 * queryChannel.send(new ProductQuery("SKU-001"));
 * quoteChannel.send(new PriceQuote("SKU-001", 29.99));
 * }</pre>
 *
 * @param <T> the specific message type this channel carries
 */
public final class DatatypeChannel<T> {

    private final Class<T> messageType;
    private final Proc<Void, T> proc;

    private DatatypeChannel(Class<T> messageType, Proc<Void, T> proc) {
        this.messageType = messageType;
        this.proc = proc;
    }

    /**
     * Create a typed channel for the given message class.
     *
     * @param messageType the message class
     * @param consumer the handler for messages of this type
     * @param <T> message type
     * @return a new datatype channel
     */
    public static <T> DatatypeChannel<T> create(Class<T> messageType, Consumer<T> consumer) {
        var proc = new Proc<Void, T>(null, (state, msg) -> {
            consumer.accept(msg);
            return state;
        });
        return new DatatypeChannel<>(messageType, proc);
    }

    /** Send a typed message into this channel. */
    public void send(T message) {
        proc.tell(message);
    }

    /** Returns the message type this channel carries. */
    public Class<T> messageType() {
        return messageType;
    }

    /** Stop the channel. */
    public void stop() {
        proc.stop();
    }
}
