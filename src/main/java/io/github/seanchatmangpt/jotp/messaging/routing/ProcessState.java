package io.github.seanchatmangpt.jotp.messaging.routing;

/**
 * Immutable process state for routing slip hop processes.
 *
 * @param name the name of the hop
 * @param count the number of messages processed
 */
public record ProcessState(String name, int count) {}
