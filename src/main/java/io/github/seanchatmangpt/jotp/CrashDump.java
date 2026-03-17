package io.github.seanchatmangpt.jotp;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive crash dump structure for JVM crash analysis and recovery.
 *
 * <p>Records the complete state of the JOTP runtime at the time of a crash or shutdown, enabling
 * post-mortem analysis and potential state recovery.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * CrashDump dump = CrashDumpCollector.getInstance().collectDump();
 * System.out.println("Crash dump captured at: " + dump.dumpTime());
 * System.out.println("Processes: " + dump.processes().size());
 * }</pre>
 *
 * @see CrashDumpCollector
 * @see JvmShutdownManager
 */
public record CrashDump(
        /** Unique identifier for this node */
        String nodeId,
        /** Timestamp when the dump was created */
        Instant dumpTime,
        /** JVM uptime in milliseconds at dump time */
        long jvmUptimeMillis,
        /** Snapshot of all tracked processes */
        Map<String, ProcessDump> processes,
        /** Local registry entries (ProcRegistry) */
        Map<String, RegistryEntryDump> registryEntries,
        /** Global registry entries (GlobalProcRegistry) */
        Map<String, GlobalRegistryEntryDump> globalRegistryEntries,
        /** Application controller state */
        Map<String, ApplicationDump> applications,
        /** Supervisor tree structure */
        SupervisorTreeDump supervisorTree,
        /** System-level metrics */
        SystemMetrics systemMetrics) {

    /** Empty crash dump for cases where collection fails. */
    public static final CrashDump EMPTY =
            new CrashDump(
                    "unknown",
                    Instant.now(),
                    0,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    SupervisorTreeDump.EMPTY,
                    SystemMetrics.EMPTY);

    /**
     * Snapshot of a single process's state at crash time.
     *
     * @param processName the registered name or generated ID
     * @param stateClass the class name of the process state
     * @param serializedState serialized form of the state (may be empty if not serializable)
     * @param lastProcessedSeq sequence number of last processed message
     * @param pendingMessageCount number of messages waiting in mailbox
     * @param pendingMessages sampled pending messages (up to limit)
     * @param isTrappingExits whether the process is trapping exit signals
     * @param isSuspended whether the process is suspended
     * @param messagesIn total messages received
     * @param messagesOut total messages processed
     */
    public record ProcessDump(
            String processName,
            String stateClass,
            byte[] serializedState,
            long lastProcessedSeq,
            int pendingMessageCount,
            List<MessageDump> pendingMessages,
            boolean isTrappingExits,
            boolean isSuspended,
            long messagesIn,
            long messagesOut) {}

    /**
     * Snapshot of a pending message in a process mailbox.
     *
     * @param messageClass the class name of the message
     * @param serializedMessage serialized form of the message
     * @param enqueuedAt when the message was enqueued
     */
    public record MessageDump(String messageClass, byte[] serializedMessage, Instant enqueuedAt) {}

    /**
     * Snapshot of a local ProcRegistry entry.
     *
     * @param name the registered name
     * @param processAlive whether the process is still running
     * @param registeredAt when the entry was registered (if available)
     */
    public record RegistryEntryDump(String name, boolean processAlive, Instant registeredAt) {}

    /**
     * Snapshot of a global registry entry.
     *
     * @param name the global name
     * @param nodeName the node hosting the process
     * @param sequenceNumber the idempotency sequence number
     * @param registeredAt when the entry was registered
     */
    public record GlobalRegistryEntryDump(
            String name, String nodeName, long sequenceNumber, Instant registeredAt) {}

    /**
     * Snapshot of an application's state.
     *
     * @param name the application name
     * @param vsn the application version
     * @param runType the run type (PERMANENT, TRANSIENT, TEMPORARY)
     * @param isRunning whether the application is currently running
     * @param startedAt when the application was started (if running)
     */
    public record ApplicationDump(
            String name, String vsn, String runType, boolean isRunning, Instant startedAt) {}

    /**
     * Snapshot of the supervisor tree structure.
     *
     * @param rootSupervisors list of top-level supervisors
     * @param totalChildren total number of supervised children
     * @param aliveChildren number of currently alive children
     */
    public record SupervisorTreeDump(
            List<SupervisorNode> rootSupervisors, int totalChildren, int aliveChildren) {

        /** Empty supervisor tree dump. */
        public static final SupervisorTreeDump EMPTY =
                new SupervisorTreeDump(Collections.emptyList(), 0, 0);
    }

    /**
     * Node in the supervisor tree.
     *
     * @param name supervisor name (may be generated)
     * @param strategy the restart strategy
     * @param isRunning whether the supervisor is running
     * @param children child supervisor nodes
     * @param childProcesses list of supervised process IDs
     */
    public record SupervisorNode(
            String name,
            String strategy,
            boolean isRunning,
            List<SupervisorNode> children,
            List<String> childProcesses) {}

    /**
     * System-level metrics at crash time.
     *
     * @param heapUsedBytes used heap memory in bytes
     * @param heapMaxBytes maximum heap memory in bytes
     * @param threadCount total number of threads
     * @param virtualThreadCount number of virtual threads (if available)
     * @param availableProcessors number of available CPU cores
     * @param systemLoadAverage system load average (1-minute)
     */
    public record SystemMetrics(
            long heapUsedBytes,
            long heapMaxBytes,
            int threadCount,
            int virtualThreadCount,
            int availableProcessors,
            double systemLoadAverage) {

        /** Empty system metrics. */
        public static final SystemMetrics EMPTY = new SystemMetrics(0, 0, 0, 0, 0, 0.0);
    }
}
