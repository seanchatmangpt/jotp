package io.github.seanchatmangpt.jotp.distributed;

import java.util.Optional;

/**
 * Event notification for registry changes.
 *
 * <p>Delivered to listeners registered via {@link
 * GlobalRegistryBackend#watch(java.util.function.Consumer)}.
 *
 * @param type the type of event
 * @param name the global name affected
 * @param ref the reference involved (present for REGISTERED and TRANSFERRED, empty for
 *     UNREGISTERED)
 */
public record RegistryEvent(Type type, String name, Optional<GlobalProcRef> ref) {
    /** Type of registry change event. */
    public enum Type {
        /** A new registration was created. */
        REGISTERED,

        /** An existing registration was removed. */
        UNREGISTERED,

        /** A registration was moved to a different node. */
        TRANSFERRED
    }
}
