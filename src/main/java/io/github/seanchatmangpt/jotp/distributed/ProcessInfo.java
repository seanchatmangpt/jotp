package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Instant;
import java.util.Map;

/**
 * Information about a registered process.
 *
 * <p>Contains metadata about a process registered in the global registry, including its location,
 * reference, and custom metadata.
 *
 * @param name the process name
 * @param nodeId the node hosting the process
 * @param ref reference to the process (may be null for remote processes)
 * @param metadata custom metadata about the process
 * @param registeredAt when the process was registered
 */
public record ProcessInfo(
        String name,
        NodeId nodeId,
        ProcRef<?, ?> ref,
        Map<String, String> metadata,
        Instant registeredAt) {

    /**
     * Create a ProcessInfo without a process reference.
     *
     * @param name the process name
     * @param nodeId the node hosting the process
     * @param metadata custom metadata
     * @return process info
     */
    public static ProcessInfo withoutRef(String name, NodeId nodeId, Map<String, String> metadata) {
        return new ProcessInfo(name, nodeId, null, metadata, Instant.now());
    }

    /**
     * Create a ProcessInfo with a process reference.
     *
     * @param name the process name
     * @param nodeId the node hosting the process
     * @param ref reference to the process
     * @param metadata custom metadata
     * @return process info
     */
    public static ProcessInfo withRef(
            String name, NodeId nodeId, ProcRef<?, ?> ref, Map<String, String> metadata) {
        return new ProcessInfo(name, nodeId, ref, metadata, Instant.now());
    }
}
