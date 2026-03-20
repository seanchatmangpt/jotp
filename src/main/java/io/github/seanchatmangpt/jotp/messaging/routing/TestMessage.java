package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;

/**
 * Test message types used in routing pattern tests.
 *
 * <p>Provides sealed message variants for the Routing Slip pattern tests.
 */
public final class TestMessage {

    private TestMessage() {}

    /**
     * A process message carrying a {@link RoutingSlip.MessageWithSlip} for forwarding through
     * routing slip hops.
     *
     * @param <P> payload type
     * @param <S> process state type
     * @param msg the routing slip message
     */
    public record Process<P, S>(RoutingSlip.MessageWithSlip<P, S> msg) {}

    /**
     * A request to retrieve the message count from a hop process.
     */
    public record GetCount() {}
}
