package io.github.seanchatmangpt.jotp.messaging.routing;

/**
 * Test helper: sealed message types for routing slip tests.
 */
public sealed interface TestMessage {
  /** Signal to process a routing slip message. */
  record Process(RoutingSlip.MessageWithSlip<Payload, ProcessState> msg) implements TestMessage {}

  /** Signal to get current count. */
  record GetCount() implements TestMessage {}
}
