package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.function.Predicate;

/**
 * Selective Consumer (Vernon: "Selective Consumer")
 *
 * <p>Consumer that only accepts messages matching a selection criterion. Others are left in the
 * queue for another consumer.
 *
 * <p>JOTP Implementation: Uses Proc<S,M> with pattern matching predicate and ProcessRegistry for
 * named consumers.
 *
 * <p>Example:
 *
 * <pre>
 * var consumer = SelectiveConsumer.create(
 *     msg -> msg instanceof Message.CommandMsg,
 *     msg -> System.out.println("Processing command: " + msg)
 * );
 * </pre>
 */
public final class SelectiveConsumer {

    /** Consumer state: accepted/rejected message counts. */
    static class ConsumerState {
        String name;
        int accepted = 0;
        int rejected = 0;

        ConsumerState(String name) {
            this.name = name;
        }
    }

    /** Internal map from ProcRef to its underlying Proc, used for state introspection. */
    private static final java.util.Map<
                    ProcRef<ConsumerState, Message>, Proc<ConsumerState, Message>>
            PROC_MAP = new java.util.IdentityHashMap<>();

    private SelectiveConsumer() {}

    /**
     * Creates a selective consumer that filters by predicate.
     *
     * @param selector Predicate: returns true if message should be consumed
     * @param handler Handler function for accepted messages
     * @return ProcRef for the consumer
     */
    public static ProcRef<ConsumerState, Message> create(
            Predicate<Message> selector, java.util.function.Consumer<Message> handler) {

        var proc =
                new Proc<>(
                        new ConsumerState("SELECTIVE_CONSUMER"),
                        (ConsumerState state, Message msg) -> {
                            if (selector.test(msg)) {
                                handler.accept(msg);
                                state.accepted++;
                            } else {
                                state.rejected++;
                                // In real system, would re-queue for other consumers
                            }
                            return state;
                        });
        var consumer = new ProcRef<>(proc);
        synchronized (PROC_MAP) {
            PROC_MAP.put(consumer, proc);
        }
        return consumer;
    }

    /**
     * Creates a selective consumer registered by name in ProcessRegistry. Other processes can look
     * it up with whereis(name).
     *
     * @param consumerName Registry name
     * @param selector Predicate for message selection
     * @param handler Handler function
     * @return Consumer ProcRef (already registered in registry)
     */
    public static ProcRef<ConsumerState, Message> createNamed(
            String consumerName,
            Predicate<Message> selector,
            java.util.function.Consumer<Message> handler) {

        var consumer = create(selector, handler);
        Proc<ConsumerState, Message> proc;
        synchronized (PROC_MAP) {
            proc = PROC_MAP.get(consumer);
        }
        if (proc != null) {
            ProcessRegistry.register(consumerName, proc);
        }
        return consumer;
    }

    /**
     * Gets acceptance statistics.
     *
     * @param consumer The consumer ProcRef
     * @return Formatted stats
     */
    public static String getStats(ProcRef<ConsumerState, Message> consumer) {
        Proc<ConsumerState, Message> proc;
        synchronized (PROC_MAP) {
            proc = PROC_MAP.get(consumer);
        }
        if (proc == null) return "accepted=0, rejected=0, total=0";
        var state = ProcSys.getState(proc).join();
        return String.format(
                "accepted=%d, rejected=%d, total=%d",
                state.accepted, state.rejected, state.accepted + state.rejected);
    }

    /**
     * Creates a type-filtered consumer (only accepts specific message type).
     *
     * @param messageType Type class to filter for
     * @param handler Handler for matching messages
     * @return ProcRef for type-selective consumer
     */
    public static ProcRef<ConsumerState, Message> createTyped(
            Class<? extends Message> messageType, java.util.function.Consumer<Message> handler) {

        return create(msg -> messageType.isInstance(msg), handler);
    }

    /**
     * Creates a route-filtered consumer (matches by event/command type).
     *
     * @param routeKey Route name to match (from Message.eventType or commandType)
     * @param handler Handler for matching messages
     * @return ProcRef for route-selective consumer
     */
    public static ProcRef<ConsumerState, Message> createByRoute(
            String routeKey, java.util.function.Consumer<Message> handler) {

        return create(
                msg -> {
                    if (msg instanceof Message.EventMsg evt) {
                        return routeKey.equals(evt.eventType());
                    } else if (msg instanceof Message.CommandMsg cmd) {
                        return routeKey.equals(cmd.commandType());
                    }
                    return false;
                },
                handler);
    }
}
