package io.github.seanchatmangpt.jotp.distributed;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Extended distributed application specification with message log replay support.
 */
public record DistributedAppSpecWithReplay(
        String name,
        List<List<NodeId>> nodes,
        Duration failoverTimeout,
        String logDirectory,
        long lastReplayedSeq) {

    public DistributedAppSpecWithReplay {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nodes, "nodes must not be null");
        Objects.requireNonNull(failoverTimeout, "failoverTimeout must not be null");
        Objects.requireNonNull(logDirectory, "logDirectory must not be null");
        if (nodes.isEmpty()) throw new IllegalArgumentException("nodes must not be empty");
        if (failoverTimeout.isNegative())
            throw new IllegalArgumentException("failoverTimeout must not be negative");
        if (lastReplayedSeq < -1)
            throw new IllegalArgumentException("lastReplayedSeq must be >= -1");
    }

    /**
     * Create a DistributedAppSpecWithReplay from a base spec.
     */
    public static DistributedAppSpecWithReplay withReplay(
            DistributedAppSpec baseSpec, String logDirectory, long lastReplayedSeq) {
        return new DistributedAppSpecWithReplay(
                baseSpec.name(), baseSpec.nodes(), baseSpec.failoverTimeout(), logDirectory, lastReplayedSeq);
    }

    /**
     * Create a DistributedAppSpecWithReplay with an empty log (no previous messages).
     */
    public static DistributedAppSpecWithReplay fresh(DistributedAppSpec baseSpec, String logDirectory) {
        return new DistributedAppSpecWithReplay(
                baseSpec.name(), baseSpec.nodes(), baseSpec.failoverTimeout(), logDirectory, -1);
    }

    /**
     * Flattens the priority groups into a single ordered list.
     */
    public List<NodeId> priorityList() {
        return nodes.stream().flatMap(List::stream).toList();
    }

    /**
     * Update the last replayed sequence number.
     */
    public DistributedAppSpecWithReplay withLastReplayedSeq(long newLastSeq) {
        return new DistributedAppSpecWithReplay(
                this.name, this.nodes, this.failoverTimeout, this.logDirectory, newLastSeq);
    }
}
