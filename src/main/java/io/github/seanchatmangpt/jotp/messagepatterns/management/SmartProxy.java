package io.github.seanchatmangpt.jotp.messagepatterns.management;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.messagepatterns.construction.CorrelationIdentifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Smart Proxy pattern: a proxy that tracks outstanding request-reply exchanges using correlation
 * identifiers.
 *
 * <p>Enterprise Integration Pattern: <em>Smart Proxy</em> (EIP §10.6). The proxy sits between
 * requesters and a service provider, maintaining a map of outstanding requests so replies can be
 * routed back to the correct requester.
 *
 * <p>Erlang analog: a proxy {@code gen_server} maintaining a map of {@code {Ref, From}} entries —
 * on reply, it looks up the original requester by reference and forwards the reply.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * ServiceProviderProxy} maintains a map of requestId → requester ActorRef for routing replies.
 *
 * @param <REQ> request type
 * @param <REP> reply type
 */
public final class SmartProxy<REQ, REP> {

    private final Function<REQ, CorrelationIdentifier> requestIdExtractor;
    private final Function<REP, CorrelationIdentifier> replyIdExtractor;
    private final Consumer<REQ> serviceProvider;
    private final Proc<Map<CorrelationIdentifier, Consumer<REP>>, Object> proc;

    /**
     * Creates a smart proxy.
     *
     * @param requestIdExtractor extracts correlation ID from requests
     * @param replyIdExtractor extracts correlation ID from replies
     * @param serviceProvider the downstream service
     */
    @SuppressWarnings("unchecked")
    public SmartProxy(
            Function<REQ, CorrelationIdentifier> requestIdExtractor,
            Function<REP, CorrelationIdentifier> replyIdExtractor,
            Consumer<REQ> serviceProvider) {
        this.requestIdExtractor = requestIdExtractor;
        this.replyIdExtractor = replyIdExtractor;
        this.serviceProvider = serviceProvider;
        this.proc = new Proc<>(new HashMap<>(), (state, msg) -> state);
    }

    private final Map<CorrelationIdentifier, Consumer<REP>> pendingRequests =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Send a request through the proxy.
     *
     * @param request the request
     * @param replyConsumer the callback for the reply
     */
    public void sendRequest(REQ request, Consumer<REP> replyConsumer) {
        CorrelationIdentifier id = requestIdExtractor.apply(request);
        pendingRequests.put(id, replyConsumer);
        serviceProvider.accept(request);
    }

    /**
     * Deliver a reply through the proxy, routing it to the original requester.
     *
     * @param reply the reply from the service
     * @return true if the reply was matched to a pending request
     */
    public boolean deliverReply(REP reply) {
        CorrelationIdentifier id = replyIdExtractor.apply(reply);
        Consumer<REP> requester = pendingRequests.remove(id);
        if (requester != null) {
            requester.accept(reply);
            return true;
        }
        return false;
    }

    /** Returns the number of outstanding requests. */
    public int pendingCount() {
        return pendingRequests.size();
    }

    /** Stop the proxy. */
    public void stop() {
        proc.stop();
    }
}
