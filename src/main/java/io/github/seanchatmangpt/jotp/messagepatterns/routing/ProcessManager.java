package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Process Manager pattern: orchestrates a complex multi-step business process by maintaining state
 * for each active process instance.
 *
 * <p>Enterprise Integration Pattern: <em>Process Manager</em> (EIP §8.9). Unlike a Routing Slip
 * (where the message carries the route), the Process Manager maintains routing state centrally and
 * makes routing decisions based on intermediate results.
 *
 * <p>Erlang analog: a {@code gen_server} managing a map of active processes — each process ID maps
 * to a state machine tracking the current step and accumulated results.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * LoanBroker} extends an abstract {@code ProcessManager} base, maintaining a map of active {@code
 * LoanRateQuote} processes.
 *
 * @param <S> process state type
 * @param <M> message type
 */
public final class ProcessManager<S, M> {

    /** A managed process instance. */
    public record ManagedProcess<S>(String processId, S state) {}

    private final Proc<Map<String, ManagedProcess<S>>, M> proc;
    private final BiFunction<S, M, S> processHandler;
    private final java.util.function.Predicate<S> isCompleted;
    private final Consumer<ManagedProcess<S>> onCompleted;

    /**
     * Creates a process manager.
     *
     * @param initialProcessState factory for new process states
     * @param processHandler handles messages and transitions process state
     * @param isCompleted predicate that determines when a process is complete
     * @param onCompleted callback when a process completes
     */
    @SuppressWarnings("unchecked")
    public ProcessManager(
            java.util.function.Supplier<S> initialProcessState,
            BiFunction<S, M, S> processHandler,
            java.util.function.Predicate<S> isCompleted,
            Consumer<ManagedProcess<S>> onCompleted) {
        this.processHandler = processHandler;
        this.isCompleted = isCompleted;
        this.onCompleted = onCompleted;

        this.proc =
                new Proc<>(
                        new HashMap<>(),
                        (processes, msg) -> {
                            if (msg instanceof ProcessManager.StartProcess start) {
                                var updated = new HashMap<>(processes);
                                updated.put(
                                        start.processId(),
                                        new ManagedProcess<>(
                                                start.processId(), initialProcessState.get()));
                                return updated;
                            }
                            if (msg instanceof ProcessManager.ProcessMessage<?> processMsg) {
                                var managed = processes.get(processMsg.processId());
                                if (managed == null) return processes;

                                S newState =
                                        processHandler.apply(
                                                managed.state(), (M) processMsg.message());
                                var updated = new HashMap<>(processes);
                                if (isCompleted.test(newState)) {
                                    var completed =
                                            new ManagedProcess<>(processMsg.processId(), newState);
                                    updated.remove(processMsg.processId());
                                    onCompleted.accept(completed);
                                } else {
                                    updated.put(
                                            processMsg.processId(),
                                            new ManagedProcess<>(processMsg.processId(), newState));
                                }
                                return updated;
                            }
                            return processes;
                        });
    }

    /** Start a new managed process. */
    public record StartProcess(String processId) {}

    /** Deliver a message to a managed process. */
    public record ProcessMessage<M>(String processId, M message) {}

    /**
     * Start a new process instance.
     *
     * @return the process ID
     */
    @SuppressWarnings("unchecked")
    public String startProcess() {
        String processId = UUID.randomUUID().toString();
        proc.tell((M) new StartProcess(processId));
        return processId;
    }

    /**
     * Send a message to a managed process.
     *
     * @param processId the target process ID
     * @param message the message to deliver
     */
    @SuppressWarnings("unchecked")
    public void sendToProcess(String processId, M message) {
        proc.tell((M) new ProcessMessage<>(processId, message));
    }

    /** Stop the process manager. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
