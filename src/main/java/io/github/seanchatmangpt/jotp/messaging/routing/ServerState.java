package io.github.seanchatmangpt.jotp.messaging.routing;

/**
 * Immutable server state for scatter-gather echo server processes.
 *
 * @param echoCount the number of messages echoed
 */
public record ServerState(int echoCount) {}
