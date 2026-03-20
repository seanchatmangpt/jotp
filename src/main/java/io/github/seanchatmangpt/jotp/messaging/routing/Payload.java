package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable payload for routing slip tests, carrying a data string and a processing history.
 *
 * <p>Each call to {@link #withEntry(String)} creates a new {@code Payload} with the entry appended
 * to the history, leaving the original unchanged.
 */
public record Payload(String data, List<String> history) {

    /** Compact constructor that ensures history is immutable. */
    public Payload {
        Objects.requireNonNull(data, "data");
        history = Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Returns a new {@code Payload} with the given entry appended to the history.
     *
     * @param entry the history entry to append
     * @return new Payload with updated history
     */
    public Payload withEntry(String entry) {
        List<String> updated = new ArrayList<>(history);
        updated.add(entry);
        return new Payload(data, updated);
    }
}
