package io.github.seanchatmangpt.jotp.messagepatterns.construction;

/**
 * Document Message pattern: a message that carries data for the receiver to process.
 *
 * <p>Enterprise Integration Pattern: <em>Document Message</em> (EIP §6.3). Unlike a {@link
 * CommandMessage} that tells the receiver <em>what to do</em>, a Document Message carries
 * <em>data</em> and leaves processing decisions to the receiver.
 *
 * <p>Erlang analog: a tagged tuple where the atom tag denotes the document type and the payload is
 * the document body — e.g., {@code {order_document, OrderId, Items, Total}}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * sealed interface OrderDocument extends DocumentMessage permits
 *     OrderDetails, InvoiceDetails {}
 *
 * record OrderDetails(String orderId, List<Item> items, double total)
 *     implements OrderDocument {}
 * record InvoiceDetails(String invoiceId, String orderId, double amount)
 *     implements OrderDocument {}
 * }</pre>
 */
public interface DocumentMessage {

    /** Returns a human-readable document type identifier. */
    default String documentType() {
        return getClass().getSimpleName();
    }
}
