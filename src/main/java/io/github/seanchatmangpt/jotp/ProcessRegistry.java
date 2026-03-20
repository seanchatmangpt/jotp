package io.github.seanchatmangpt.jotp;

import java.util.Set;

/**
 * Process registry — a convenience alias for {@link ProcRegistry} used in routing and integration
 * test contexts.
 *
 * <p>Delegates all operations to {@link ProcRegistry}. Prefer {@link ProcRegistry} for new code.
 */
public final class ProcessRegistry {

    private ProcessRegistry() {}

    /**
     * Returns the set of all currently registered process names.
     *
     * @return set of registered names
     */
    public static Set<String> registered() {
        return ProcRegistry.registered();
    }

    /**
     * Unregisters a process name.
     *
     * @param name the name to unregister
     */
    public static void unregister(String name) {
        ProcRegistry.unregister(name);
    }
}
