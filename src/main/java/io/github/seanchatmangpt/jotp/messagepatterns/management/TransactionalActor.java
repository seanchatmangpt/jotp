package io.github.seanchatmangpt.jotp.messagepatterns.management;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Transactional Actor pattern: an actor that supports commit/rollback semantics by maintaining a
 * tentative state and an event log.
 *
 * <p>Enterprise Integration Pattern: <em>Transactional Client</em> (EIP §10.6). The actor
 * accumulates state changes as events; on commit, the changes become permanent; on rollback, the
 * state reverts to the last committed snapshot.
 *
 * <p>Erlang analog: an event-sourced {@code gen_server} using {@code persistent_term} for committed
 * state and a local event list for uncommitted changes.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * Order} extends {@code EventsourcedProcessor} and persists domain events ({@code OrderStarted},
 * {@code OrderLineItemAdded}, {@code OrderPlaced}).
 *
 * @param <S> state type
 * @param <E> event type
 */
public final class TransactionalActor<S, E> {

    private S committedState;
    private S tentativeState;
    private final List<E> uncommittedEvents = new ArrayList<>();
    private final BiFunction<S, E, S> eventHandler;
    private final Proc<S, E> proc;

    /**
     * Creates a transactional actor.
     *
     * @param initialState the initial committed state
     * @param eventHandler applies events to produce new state
     */
    public TransactionalActor(S initialState, BiFunction<S, E, S> eventHandler) {
        this.committedState = initialState;
        this.tentativeState = initialState;
        this.eventHandler = eventHandler;
        this.proc =
                new Proc<>(
                        initialState,
                        (state, event) -> {
                            uncommittedEvents.add(event);
                            tentativeState = eventHandler.apply(tentativeState, event);
                            return tentativeState;
                        });
    }

    /** Apply an event (tentatively — not yet committed). */
    public void apply(E event) {
        proc.tell(event);
    }

    /** Commit all tentative changes. */
    public void commit() {
        committedState = tentativeState;
        uncommittedEvents.clear();
    }

    /** Rollback to the last committed state. */
    public void rollback() {
        tentativeState = committedState;
        uncommittedEvents.clear();
    }

    /** Returns the current tentative (uncommitted) state. */
    public S tentativeState() {
        return tentativeState;
    }

    /** Returns the last committed state. */
    public S committedState() {
        return committedState;
    }

    /** Returns the list of uncommitted events. */
    public List<E> uncommittedEvents() {
        return List.copyOf(uncommittedEvents);
    }

    /** Stop the actor. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
