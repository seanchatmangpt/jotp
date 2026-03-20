package io.github.seanchatmangpt.jotp.messaging.routing;

/**
 * Echo message for scatter-gather pattern tests.
 *
 * @param requestId correlation identifier for the request
 * @param payload the message payload string
 */
public record EchoMessage(String requestId, String payload) {}
