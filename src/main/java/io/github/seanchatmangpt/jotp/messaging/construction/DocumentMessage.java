package io.github.seanchatmangpt.jotp.messaging.construction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Document Message: carries a serializable domain entity through the messaging pipeline.
 *
 * <p>Enterprise Integration Pattern: <em>Document Message</em> (EIP §6.3). Unlike a Command
 * Message that tells the receiver what to do, a Document Message carries data. The payload is
 * eagerly serialized on creation to produce a self-contained, wire-ready byte array.
 *
 * @param <T> the domain entity type (must implement {@link Serializable})
 */
public final class DocumentMessage<T extends Serializable> {

    private final UUID messageId;
    private final String documentType;
    private final byte[] documentBytes;
    private final long createdAt;

    private DocumentMessage(String documentType, byte[] documentBytes, long createdAt) {
        this.messageId = UUID.randomUUID();
        this.documentType = documentType;
        this.documentBytes = Arrays.copyOf(documentBytes, documentBytes.length);
        this.createdAt = createdAt;
    }

    /**
     * Creates a document message by eagerly serializing the given entity.
     *
     * @param documentType the document type name (must not be null or blank)
     * @param document the domain entity to serialize (must not be null)
     * @param <T> the entity type
     * @return a new document message
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if documentType is blank
     * @throws IOException if serialization fails
     */
    public static <T extends Serializable> DocumentMessage<T> create(
            String documentType, T document) throws IOException {
        Objects.requireNonNull(documentType, "documentType must not be null");
        Objects.requireNonNull(document, "document must not be null");
        if (documentType.isBlank()) {
            throw new IllegalArgumentException("documentType must not be blank");
        }
        byte[] bytes = serialize(document);
        return new DocumentMessage<>(documentType, bytes, System.currentTimeMillis());
    }

    /**
     * Deserializes and returns the stored document, validating the document type.
     *
     * @param expectedType the expected document type name
     * @param clazz the expected class of the document
     * @param <D> the expected document type
     * @return the deserialized document
     * @throws IllegalArgumentException if the document type does not match
     * @throws RuntimeException wrapping any deserialization failure
     */
    public <D> D document(String expectedType, Class<D> clazz) {
        if (!documentType.equals(expectedType)) {
            throw new IllegalArgumentException(
                    "type mismatch: expected '" + expectedType + "' but message has '" + documentType + "'");
        }
        try {
            return deserialize(documentBytes, clazz);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    /**
     * Serializes the given object to a byte array.
     *
     * @param obj the object to serialize (must not be null)
     * @return the serialized bytes
     * @throws NullPointerException if obj is null
     * @throws IOException if serialization fails
     */
    public static byte[] serialize(Serializable obj) throws IOException {
        Objects.requireNonNull(obj, "object to serialize must not be null");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes bytes into an object of the given class.
     *
     * @param bytes the bytes to deserialize (must not be null or empty)
     * @param clazz the expected class (must not be null)
     * @param <D> the expected type
     * @return the deserialized object
     * @throws NullPointerException if bytes or clazz is null
     * @throws IllegalArgumentException if bytes is empty
     * @throws IOException if deserialization fails
     * @throws ClassNotFoundException if the class cannot be found
     */
    @SuppressWarnings("unchecked")
    public static <D> D deserialize(byte[] bytes, Class<D> clazz)
            throws IOException, ClassNotFoundException {
        Objects.requireNonNull(bytes, "bytes to deserialize must not be null");
        Objects.requireNonNull(clazz, "target class must not be null");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (D) ois.readObject();
        }
    }

    /** Returns the unique message ID. */
    public UUID messageId() {
        return messageId;
    }

    /** Returns the document type name. */
    public String documentType() {
        return documentType;
    }

    /** Returns a defensive copy of the serialized document bytes. */
    public byte[] documentBytes() {
        return Arrays.copyOf(documentBytes, documentBytes.length);
    }

    /** Returns the creation timestamp in epoch milliseconds. */
    public long createdAt() {
        return createdAt;
    }
}
