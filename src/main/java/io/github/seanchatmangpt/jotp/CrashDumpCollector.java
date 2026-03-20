package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.DefaultGlobalProcRegistry;
import io.github.seanchatmangpt.jotp.distributed.GlobalProcRef;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton collector for comprehensive crash dumps during JVM shutdown.
 *
 * <p>Registers with {@link JvmShutdownManager} to capture complete runtime state when the JVM is
 * shutting down or crashing. Writes JSON dumps to the {@code ./crash-dumps/} directory for
 * post-mortem analysis.
 *
 * <p><strong>Collected Data:</strong>
 *
 * <ul>
 *   <li>All tracked process states and pending messages
 *   <li>Local and global registry entries
 *   <li>Application controller state
 *   <li>Supervisor tree structure
 *   <li>System metrics (memory, threads, load)
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Automatic registration happens on first getInstance() call
 * CrashDumpCollector collector = CrashDumpCollector.getInstance();
 *
 * // Manually trigger a dump (for testing or debugging)
 * Path dumpFile = collector.collectAndWriteDump();
 *
 * // Register a process for crash dump tracking
 * collector.trackProcess("my-worker", proc);
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> All operations are thread-safe. Process tracking uses a {@link
 * ConcurrentHashMap} for concurrent access.
 *
 * @see CrashDump
 * @see JvmShutdownManager
 */
public final class CrashDumpCollector {

    private static final CrashDumpCollector INSTANCE = new CrashDumpCollector();
    private static final Path CRASH_DUMP_DIR = Paths.get("crash-dumps");
    private static final int MAX_PENDING_MESSAGES_SAMPLE = 100;
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** Tracked processes for dump collection. Key is process name or ID. */
    private final ConcurrentHashMap<String, Proc<?, ?>> trackedProcesses =
            new ConcurrentHashMap<>();

    /** Tracked supervisors for tree dump. */
    private final ConcurrentHashMap<String, Supervisor> trackedSupervisors =
            new ConcurrentHashMap<>();

    private final AtomicBoolean registeredWithShutdownManager = new AtomicBoolean(false);
    private volatile String nodeId = "node-" + ManagementFactory.getRuntimeMXBean().getName();

    private CrashDumpCollector() {
        // Singleton - use getInstance()
    }

    /**
     * Get the singleton instance.
     *
     * <p>Automatically registers with {@link JvmShutdownManager} on first call.
     *
     * @return the singleton collector instance
     */
    public static CrashDumpCollector getInstance() {
        INSTANCE.registerWithShutdownManagerIfNeeded();
        return INSTANCE;
    }

    /**
     * Set the node identifier for crash dumps.
     *
     * @param id the node identifier
     */
    public void setNodeId(String id) {
        this.nodeId = id;
    }

    /**
     * Get the current node identifier.
     *
     * @return the node identifier
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Track a process for crash dump collection.
     *
     * <p>Tracked processes will have their state captured in crash dumps.
     *
     * @param name a unique name for the process
     * @param proc the process to track
     */
    public <S, M> void trackProcess(String name, Proc<S, M> proc) {
        trackedProcesses.put(name, proc);
    }

    /**
     * Stop tracking a process.
     *
     * @param name the process name to untrack
     */
    public void untrackProcess(String name) {
        trackedProcesses.remove(name);
    }

    /**
     * Track a supervisor for crash dump collection.
     *
     * @param name a unique name for the supervisor
     * @param supervisor the supervisor to track
     */
    public void trackSupervisor(String name, Supervisor supervisor) {
        trackedSupervisors.put(name, supervisor);
    }

    /**
     * Stop tracking a supervisor.
     *
     * @param name the supervisor name to untrack
     */
    public void untrackSupervisor(String name) {
        trackedSupervisors.remove(name);
    }

    /**
     * Collect a complete crash dump.
     *
     * <p>Gathers state from all tracked processes, registries, applications, supervisors, and
     * system metrics.
     *
     * @return a comprehensive crash dump
     */
    public CrashDump collectDump() {
        long startTime = System.currentTimeMillis();

        try {
            Map<String, CrashDump.ProcessDump> processes = collectProcessDumps();
            Map<String, CrashDump.RegistryEntryDump> registryEntries = collectRegistryEntries();
            Map<String, CrashDump.GlobalRegistryEntryDump> globalEntries =
                    collectGlobalRegistryEntries();
            Map<String, CrashDump.ApplicationDump> applications = collectApplicationDumps();
            CrashDump.SupervisorTreeDump supervisorTree = collectSupervisorTreeDump();
            CrashDump.SystemMetrics metrics = collectSystemMetrics();

            long jvmUptime = ManagementFactory.getRuntimeMXBean().getUptime();

            return new CrashDump(
                    nodeId,
                    Instant.now(),
                    jvmUptime,
                    processes,
                    registryEntries,
                    globalEntries,
                    applications,
                    supervisorTree,
                    metrics);
        } catch (Exception e) {
            // Return empty dump on failure, but log the error
            System.err.println("[CrashDumpCollector] Failed to collect dump: " + e.getMessage());
            return CrashDump.EMPTY;
        }
    }

    /**
     * Collect and write a crash dump to the crash-dumps directory.
     *
     * <p>Creates the directory if it doesn't exist. The dump file is named with a timestamp for
     * easy identification.
     *
     * @return the path to the written dump file
     * @throws IOException if the dump cannot be written
     */
    public Path collectAndWriteDump() throws IOException {
        CrashDump dump = collectDump();
        return writeDump(dump);
    }

    /**
     * Write a crash dump to the crash-dumps directory.
     *
     * @param dump the dump to write
     * @return the path to the written dump file
     * @throws IOException if the dump cannot be written
     */
    public Path writeDump(CrashDump dump) throws IOException {
        // Ensure directory exists
        if (!Files.exists(CRASH_DUMP_DIR)) {
            Files.createDirectories(CRASH_DUMP_DIR);
        }

        // Generate filename with timestamp
        String timestamp = FILE_TIMESTAMP.format(dump.dumpTime());
        String filename = "crash-dump-" + timestamp + ".json";
        Path dumpFile = CRASH_DUMP_DIR.resolve(filename);

        // Write JSON
        String json = toJson(dump);
        Files.writeString(dumpFile, json);

        System.err.println(
                "[CrashDumpCollector] Crash dump written to: " + dumpFile.toAbsolutePath());
        return dumpFile;
    }

    /**
     * Get the crash dump directory path.
     *
     * @return the directory path
     */
    public Path getCrashDumpDirectory() {
        return CRASH_DUMP_DIR;
    }

    /**
     * Clear all tracked processes and supervisors.
     *
     * <p>For testing only.
     */
    public void reset() {
        trackedProcesses.clear();
        trackedSupervisors.clear();
    }

    // ── Internal Collection Methods ────────────────────────────────────────────────

    private void registerWithShutdownManagerIfNeeded() {
        if (registeredWithShutdownManager.compareAndSet(false, true)) {
            JvmShutdownManager.getInstance()
                    .registerCallback(
                            JvmShutdownManager.Priority.BEST_EFFORT_SAVE,
                            () -> {
                                try {
                                    collectAndWriteDump();
                                } catch (IOException e) {
                                    System.err.println(
                                            "[CrashDumpCollector] Failed to write crash dump: "
                                                    + e.getMessage());
                                }
                            });
        }
    }

    private Map<String, CrashDump.ProcessDump> collectProcessDumps() {
        Map<String, CrashDump.ProcessDump> result = new LinkedHashMap<>();

        for (Map.Entry<String, Proc<?, ?>> entry : trackedProcesses.entrySet()) {
            String name = entry.getKey();
            Proc<?, ?> proc = entry.getValue();

            try {
                CrashDump.ProcessDump procDump = collectProcessDump(name, proc);
                result.put(name, procDump);
            } catch (Exception e) {
                // Skip processes that fail to dump
                System.err.println(
                        "[CrashDumpCollector] Failed to dump process "
                                + name
                                + ": "
                                + e.getMessage());
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private <S, M> CrashDump.ProcessDump collectProcessDump(String name, Proc<S, M> proc) {
        // Get process state via ProcSys
        S currentState = null;
        try {
            currentState = (S) ProcSys.getState(proc).get();
        } catch (Exception e) {
            // State unavailable - continue with null
        }

        String stateClass = currentState != null ? currentState.getClass().getName() : "unknown";

        // Try to serialize state
        byte[] serializedState = trySerialize(currentState);

        // Get pending messages using the new package-private method
        List<CrashDump.MessageDump> pendingMessages = new ArrayList<>();
        int pendingCount = proc.mailboxSize();

        // Sample pending messages (up to max)
        try {
            List<Proc.MessageSnapshot> snapshots =
                    proc.samplePendingMessages(MAX_PENDING_MESSAGES_SAMPLE);
            for (Proc.MessageSnapshot snapshot : snapshots) {
                pendingMessages.add(
                        new CrashDump.MessageDump(
                                snapshot.messageClass(),
                                snapshot.messageString().getBytes("UTF-8"),
                                snapshot.enqueuedAt()));
            }
        } catch (Exception e) {
            // Ignore message sampling failures
        }

        // Get statistics
        long messagesIn = proc.messagesIn.sum();
        long messagesOut = proc.messagesOut.sum();

        return new CrashDump.ProcessDump(
                name,
                stateClass,
                serializedState,
                0L, // lastProcessedSeq - would need to track this
                pendingCount,
                Collections.unmodifiableList(pendingMessages),
                proc.isTrappingExits(),
                proc.isSuspended(),
                messagesIn,
                messagesOut);
    }

    private Map<String, CrashDump.RegistryEntryDump> collectRegistryEntries() {
        Map<String, CrashDump.RegistryEntryDump> result = new LinkedHashMap<>();

        try {
            for (String name : ProcRegistry.registered()) {
                var procOpt = ProcRegistry.whereis(name);
                boolean alive = procOpt.isPresent() && procOpt.get().thread().isAlive();
                result.put(name, new CrashDump.RegistryEntryDump(name, alive, Instant.now()));
            }
        } catch (Exception e) {
            System.err.println(
                    "[CrashDumpCollector] Failed to collect registry entries: " + e.getMessage());
        }

        return result;
    }

    private Map<String, CrashDump.GlobalRegistryEntryDump> collectGlobalRegistryEntries() {
        Map<String, CrashDump.GlobalRegistryEntryDump> result = new LinkedHashMap<>();

        try {
            Map<String, GlobalProcRef> globals =
                    DefaultGlobalProcRegistry.getInstance().listGlobal();
            for (Map.Entry<String, GlobalProcRef> entry : globals.entrySet()) {
                GlobalProcRef ref = entry.getValue();
                result.put(
                        entry.getKey(),
                        new CrashDump.GlobalRegistryEntryDump(
                                ref.name(),
                                ref.nodeName(),
                                ref.sequenceNumber(),
                                ref.registeredAt()));
            }
        } catch (Exception e) {
            System.err.println(
                    "[CrashDumpCollector] Failed to collect global registry entries: "
                            + e.getMessage());
        }

        return result;
    }

    private Map<String, CrashDump.ApplicationDump> collectApplicationDumps() {
        Map<String, CrashDump.ApplicationDump> result = new LinkedHashMap<>();

        try {
            // Get loaded applications
            for (ApplicationInfo info : ApplicationController.loadedApplications()) {
                boolean running =
                        ApplicationController.whichApplications().stream()
                                .anyMatch(r -> r.name().equals(info.name()));

                result.put(
                        info.name(),
                        new CrashDump.ApplicationDump(
                                info.name(), info.vsn(), "UNKNOWN", running, Instant.now()));
            }
        } catch (Exception e) {
            System.err.println(
                    "[CrashDumpCollector] Failed to collect application dumps: " + e.getMessage());
        }

        return result;
    }

    private CrashDump.SupervisorTreeDump collectSupervisorTreeDump() {
        List<CrashDump.SupervisorNode> roots = new ArrayList<>();
        int totalChildren = 0;
        int aliveChildren = 0;

        for (Map.Entry<String, Supervisor> entry : trackedSupervisors.entrySet()) {
            try {
                Supervisor sup = entry.getValue();
                CrashDump.SupervisorNode node = buildSupervisorNode(entry.getKey(), sup);
                roots.add(node);

                for (Supervisor.ChildInfo child : sup.whichChildren()) {
                    totalChildren++;
                    if (child.alive()) aliveChildren++;
                }
            } catch (Exception e) {
                System.err.println(
                        "[CrashDumpCollector] Failed to dump supervisor "
                                + entry.getKey()
                                + ": "
                                + e.getMessage());
            }
        }

        return new CrashDump.SupervisorTreeDump(
                Collections.unmodifiableList(roots), totalChildren, aliveChildren);
    }

    private CrashDump.SupervisorNode buildSupervisorNode(String name, Supervisor sup) {
        List<String> childProcessIds = new ArrayList<>();
        for (Supervisor.ChildInfo child : sup.whichChildren()) {
            childProcessIds.add(child.id());
        }

        String strategy = sup.isRunning() ? "RUNNING" : "STOPPED";

        return new CrashDump.SupervisorNode(
                name, strategy, sup.isRunning(), Collections.emptyList(), childProcessIds);
    }

    private CrashDump.SystemMetrics collectSystemMetrics() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();

        // Virtual thread count is not directly available, estimate from tracked processes
        int virtualThreadCount = trackedProcesses.size() + trackedSupervisors.size();

        int processors = runtime.availableProcessors();
        double loadAverage = getSystemLoadAverage();

        return new CrashDump.SystemMetrics(
                heapUsed, heapMax, threadCount, virtualThreadCount, processors, loadAverage);
    }

    private double getSystemLoadAverage() {
        try {
            return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (Exception e) {
            return -1.0;
        }
    }

    private byte[] trySerialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }

        // For now, return class name as bytes
        // Full serialization would require implementing Serializable on all state types
        try {
            return obj.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ── JSON Serialization ────────────────────────────────────────────────────────

    private String toJson(CrashDump dump) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"nodeId\": \"").append(escapeJson(nodeId)).append("\",\n");
        sb.append("  \"dumpTime\": \"").append(dump.dumpTime()).append("\",\n");
        sb.append("  \"jvmUptimeMillis\": ").append(dump.jvmUptimeMillis()).append(",\n");

        // Processes
        sb.append("  \"processes\": {\n");
        appendProcessDumps(sb, dump.processes());
        sb.append("  },\n");

        // Registry entries
        sb.append("  \"registryEntries\": {\n");
        appendRegistryEntries(sb, dump.registryEntries());
        sb.append("  },\n");

        // Global registry entries
        sb.append("  \"globalRegistryEntries\": {\n");
        appendGlobalRegistryEntries(sb, dump.globalRegistryEntries());
        sb.append("  },\n");

        // Applications
        sb.append("  \"applications\": {\n");
        appendApplications(sb, dump.applications());
        sb.append("  },\n");

        // Supervisor tree
        sb.append("  \"supervisorTree\": {\n");
        appendSupervisorTree(sb, dump.supervisorTree());
        sb.append("  },\n");

        // System metrics
        sb.append("  \"systemMetrics\": {\n");
        appendSystemMetrics(sb, dump.systemMetrics());
        sb.append("  }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private void appendProcessDumps(
            StringBuilder sb, Map<String, CrashDump.ProcessDump> processes) {
        int i = 0;
        for (Map.Entry<String, CrashDump.ProcessDump> entry : processes.entrySet()) {
            CrashDump.ProcessDump p = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {\n");
            sb.append("      \"processName\": \"")
                    .append(escapeJson(p.processName()))
                    .append("\",\n");
            sb.append("      \"stateClass\": \"")
                    .append(escapeJson(p.stateClass()))
                    .append("\",\n");
            sb.append("      \"pendingMessageCount\": ")
                    .append(p.pendingMessageCount())
                    .append(",\n");
            sb.append("      \"isTrappingExits\": ").append(p.isTrappingExits()).append(",\n");
            sb.append("      \"messagesIn\": ").append(p.messagesIn()).append(",\n");
            sb.append("      \"messagesOut\": ").append(p.messagesOut()).append("\n");
            sb.append("    }");
            if (++i < processes.size()) sb.append(",");
            sb.append("\n");
        }
    }

    private void appendRegistryEntries(
            StringBuilder sb, Map<String, CrashDump.RegistryEntryDump> entries) {
        int i = 0;
        for (Map.Entry<String, CrashDump.RegistryEntryDump> entry : entries.entrySet()) {
            CrashDump.RegistryEntryDump r = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {\n");
            sb.append("      \"name\": \"").append(escapeJson(r.name())).append("\",\n");
            sb.append("      \"processAlive\": ").append(r.processAlive()).append(",\n");
            sb.append("      \"registeredAt\": \"").append(r.registeredAt()).append("\"\n");
            sb.append("    }");
            if (++i < entries.size()) sb.append(",");
            sb.append("\n");
        }
    }

    private void appendGlobalRegistryEntries(
            StringBuilder sb, Map<String, CrashDump.GlobalRegistryEntryDump> entries) {
        int i = 0;
        for (Map.Entry<String, CrashDump.GlobalRegistryEntryDump> entry : entries.entrySet()) {
            CrashDump.GlobalRegistryEntryDump g = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {\n");
            sb.append("      \"name\": \"").append(escapeJson(g.name())).append("\",\n");
            sb.append("      \"nodeName\": \"").append(escapeJson(g.nodeName())).append("\",\n");
            sb.append("      \"sequenceNumber\": ").append(g.sequenceNumber()).append(",\n");
            sb.append("      \"registeredAt\": \"").append(g.registeredAt()).append("\"\n");
            sb.append("    }");
            if (++i < entries.size()) sb.append(",");
            sb.append("\n");
        }
    }

    private void appendApplications(
            StringBuilder sb, Map<String, CrashDump.ApplicationDump> applications) {
        int i = 0;
        for (Map.Entry<String, CrashDump.ApplicationDump> entry : applications.entrySet()) {
            CrashDump.ApplicationDump a = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {\n");
            sb.append("      \"name\": \"").append(escapeJson(a.name())).append("\",\n");
            sb.append("      \"vsn\": \"").append(escapeJson(a.vsn())).append("\",\n");
            sb.append("      \"runType\": \"").append(escapeJson(a.runType())).append("\",\n");
            sb.append("      \"isRunning\": ").append(a.isRunning()).append(",\n");
            sb.append("      \"startedAt\": \"").append(a.startedAt()).append("\"\n");
            sb.append("    }");
            if (++i < applications.size()) sb.append(",");
            sb.append("\n");
        }
    }

    private void appendSupervisorTree(StringBuilder sb, CrashDump.SupervisorTreeDump tree) {
        sb.append("    \"totalChildren\": ").append(tree.totalChildren()).append(",\n");
        sb.append("    \"aliveChildren\": ").append(tree.aliveChildren()).append(",\n");
        sb.append("    \"rootSupervisors\": [\n");
        int i = 0;
        for (CrashDump.SupervisorNode node : tree.rootSupervisors()) {
            sb.append("      {\n");
            sb.append("        \"name\": \"").append(escapeJson(node.name())).append("\",\n");
            sb.append("        \"strategy\": \"")
                    .append(escapeJson(node.strategy()))
                    .append("\",\n");
            sb.append("        \"isRunning\": ").append(node.isRunning()).append(",\n");
            sb.append("        \"childProcesses\": [");
            appendStringList(sb, node.childProcesses());
            sb.append("]\n");
            sb.append("      }");
            if (++i < tree.rootSupervisors().size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("    ]\n");
    }

    private void appendSystemMetrics(StringBuilder sb, CrashDump.SystemMetrics metrics) {
        sb.append("    \"heapUsedBytes\": ").append(metrics.heapUsedBytes()).append(",\n");
        sb.append("    \"heapMaxBytes\": ").append(metrics.heapMaxBytes()).append(",\n");
        sb.append("    \"threadCount\": ").append(metrics.threadCount()).append(",\n");
        sb.append("    \"virtualThreadCount\": ")
                .append(metrics.virtualThreadCount())
                .append(",\n");
        sb.append("    \"availableProcessors\": ")
                .append(metrics.availableProcessors())
                .append(",\n");
        sb.append("    \"systemLoadAverage\": ").append(metrics.systemLoadAverage()).append("\n");
    }

    private void appendStringList(StringBuilder sb, List<String> list) {
        for (int i = 0; i < list.size(); i++) {
            sb.append("\"").append(escapeJson(list.get(i))).append("\"");
            if (i < list.size() - 1) sb.append(", ");
        }
    }

    private String escapeJson(String s) {
        if (s == null) s = "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
