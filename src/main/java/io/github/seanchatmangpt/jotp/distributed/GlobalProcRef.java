package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Instant;

/**
 * Reference to a globally registered process with idempotent sequence tracking.
 *
 * <p>Contains the local {@link ProcRef} and metadata about where the process is hosted. The {@code
 * localRef} can be used directly for messaging via {@link ProcRef#tell(Object)} or {@link
 * ProcRef#ask(Object)}.
 *
 * <p><strong>Idempotence:</strong> The {@code sequenceNumber} enables crash-safe atomic writes.
 * Each registration/update increments the sequence number. On recovery, if the stored sequence
 * number doesn't match the ACK sequence number, the system can detect a partial write and recover
 * idempotently using the lower value.
 *
 * @param name the global name under which the process is registered
 * @param localRef stable reference to the process (survives supervisor restarts)
 * @param nodeName name of the node hosting this process
 * @param sequenceNumber monotonically increasing sequence number for idempotence
 * @param registeredAt timestamp when the registration was created
 */
public record GlobalProcRef(
        String name,
        ProcRef<?, ?> localRef,
        String nodeName,
        long sequenceNumber,
        Instant registeredAt)
        implements SequencedState {

    /**
     * Create a new GlobalProcRef with an updated sequence number.
     *
     * @param newSequenceNumber the new sequence number
     * @return a new GlobalProcRef with the updated sequence number
     */
    public GlobalProcRef withSequenceNumber(long newSequenceNumber) {
        return new GlobalProcRef(name, localRef, nodeName, newSequenceNumber, registeredAt);
    }

    /**
     * Create a new GlobalProcRef with an updated local reference.
     *
     * @param newLocalRef the new local reference
     * @return a new GlobalProcRef with the updated local reference
     */
    public GlobalProcRef withLocalRef(ProcRef<?, ?> newLocalRef) {
        return new GlobalProcRef(name, newLocalRef, nodeName, sequenceNumber, registeredAt);
    }

    /**
     * Create a new GlobalProcRef with an updated node name.
     *
     * @param newNodeName the new node name
     * @return a new GlobalProcRef with the updated node name
     */
    public GlobalProcRef withNodeName(String newNodeName) {
        return new GlobalProcRef(name, localRef, newNodeName, sequenceNumber, registeredAt);
    }

    @Override
    public long lastProcessedSeq() {
        return sequenceNumber;
    }
}
