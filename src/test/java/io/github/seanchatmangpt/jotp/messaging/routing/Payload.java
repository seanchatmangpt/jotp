package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test helper: immutable payload with enrichment history.
 */
public final class Payload {
  private final String data;
  private final List<String> history;

  public Payload(String data, List<String> history) {
    this.data = data;
    this.history = new ArrayList<>(history);
  }

  public String data() {
    return data;
  }

  public List<String> history() {
    return Collections.unmodifiableList(history);
  }

  public Payload withEntry(String entry) {
    List<String> newHistory = new ArrayList<>(history);
    newHistory.add(entry);
    return new Payload(data, newHistory);
  }
}
