package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GlobalProcRegistry}.
 *
 * <p>Verifies distributed process registry with cross-node registration and lookup.
 */
@DtrTest
@DisplayName("GlobalProcRegistry — OTP distributed process registry")
class GlobalProcRegistryTest {

    @DtrContextField private DtrContext ctx;

    private DefaultGlobalProcRegistry registry;
    private InMemoryGlobalRegistryBackend backend;
    private final java.util.concurrent.atomic.AtomicLong nameCounter =
            new java.util.concurrent.atomic.AtomicLong(0);

    // Use thread ID + counter for unique names across parallel tests
    private final ThreadLocal<java.util.concurrent.atomic.AtomicLong> threadLocalCounter =
            ThreadLocal.withInitial(() -> new java.util.concurrent.atomic.AtomicLong(0));

    @BeforeEach
    void setUp() {
        // Reset the singleton registry first to clear any previous state
        registry = DefaultGlobalProcRegistry.getInstance();
        registry.reset();

        // Create a fresh backend for this test
        backend = new InMemoryGlobalRegistryBackend();
        registry.setBackend(backend);
        registry.setCurrentNodeName("local-node");
    }

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    /**
     * Generate a unique process name for test isolation.
     *
     * @return a unique name like "my-proc-threadId-counter" for parallel test safety
     */
    private String uniqueProcName() {
        long threadId = Thread.currentThread().threadId();
        long counter = threadLocalCounter.get().getAndIncrement();
        return "my-proc-" + threadId + "-" + counter;
    }

    @Test
    @DisplayName("Should register process globally")
    void registerGlobal_registersProcess(DtrContext ctx) {
        ctx.say("Global process registration enables cross-node process lookup.");
        ctx.say("Each registered process has a unique name and is associated with a node.");
        ctx.say(
                "This implements OTP's global process registry (like Erlang's global:register_name/2).");

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        String procName = uniqueProcName();
        registry.registerGlobal(procName, procRef, "local-node");

        Optional<GlobalProcRef> found = registry.findGlobal(procName);

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo(procName);
        assertThat(found.get().nodeName()).isEqualTo("local-node");
    }

    @Test
    @DisplayName("Should find registered process")
    void findGlobal_returnsRegisteredProcess() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        String procName = uniqueProcName();
        registry.registerGlobal(procName, procRef, "local-node");

        Optional<GlobalProcRef> found = registry.findGlobal(procName);

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo(procName);
        assertThat(found.get().nodeName()).isEqualTo("local-node");
    }

    @Test
    @DisplayName("Should return empty for non-existent process")
    void findGlobal_returnsEmptyForNonExistent() {
        Optional<GlobalProcRef> found = registry.findGlobal("non-existent");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should unregister process")
    void unregisterGlobal_removesProcess(DtrContext ctx) {
        ctx.say("Unregistering removes the process from global discovery.");
        ctx.say("Subsequent lookups return empty, freeing the name for reuse.");
        ctx.say("This supports graceful process shutdown and name release.");

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        String procName = uniqueProcName();
        registry.registerGlobal(procName, procRef, "local-node");
        registry.unregisterGlobal(procName);

        Optional<GlobalProcRef> found = registry.findGlobal(procName);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should handle unregister of non-existent process gracefully")
    void unregisterGlobal_handlesNonExistentProcess() {
        assertThatCode(() -> registry.unregisterGlobal("non-existent")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should list all registered processes")
    void listGlobal_returnsAllProcesses(DtrContext ctx) {
        ctx.say("Listing all globally registered processes enables cluster introspection.");
        ctx.say("Returns a map of process names to their GlobalProcRef metadata.");
        ctx.say("This supports monitoring, debugging, and operational visibility.");

        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc3 = Proc.spawn("initial", (s, m) -> s);

        String procName1 = uniqueProcName();
        String procName2 = uniqueProcName();
        String procName3 = uniqueProcName();

        registry.registerGlobal(procName1, new ProcRef<>(proc1), "local-node");
        registry.registerGlobal(procName2, new ProcRef<>(proc2), "local-node");
        registry.registerGlobal(procName3, new ProcRef<>(proc3), "local-node");

        Map<String, GlobalProcRef> processes = registry.listGlobal();

        // Note: Due to parallel test execution, the registry may contain processes from other tests
        // We verify that our specific processes are registered
        assertThat(processes).containsKey(procName1);
        assertThat(processes).containsKey(procName2);
        assertThat(processes).containsKey(procName3);
        assertThat(processes.values()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Should return empty map when no processes registered")
    void listGlobal_returnsEmptyMapWhenNoProcesses() {
        Map<String, GlobalProcRef> processes = registry.listGlobal();

        assertThat(processes).isEmpty();
    }

    @Test
    @DisplayName("Should register if absent atomically")
    void registerGlobalIfAbsent_registersWhenNotPresent(DtrContext ctx) {
        ctx.say("Atomic register-if-absent prevents race conditions in distributed registration.");
        ctx.say("Returns true if registered, false if name already exists.");
        ctx.say("This implements OTP's conflict-free distributed naming pattern.");

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        String procName = uniqueProcName();
        boolean success = registry.registerGlobalIfAbsent(procName, procRef, "local-node");

        assertThat(success).isTrue();
        assertThat(registry.findGlobal(procName)).isPresent();
    }

    @Test
    @DisplayName("Should not register if absent when already present")
    void registerGlobalIfAbsent_doesNotRegisterWhenPresent() {
        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);

        String procName = uniqueProcName();
        registry.registerGlobal(procName, new ProcRef<>(proc1), "local-node");

        boolean success =
                registry.registerGlobalIfAbsent(procName, new ProcRef<>(proc2), "local-node");

        assertThat(success).isFalse();
        assertThat(registry.findGlobal(procName)).isPresent();
    }

    @Test
    @DisplayName("Should throw on duplicate registration")
    void registerGlobal_throwsOnDuplicate(DtrContext ctx) {
        ctx.say("Duplicate registration throws IllegalStateException.");
        ctx.say("This enforces global uniqueness constraints for process names.");
        ctx.say("Prevents accidental name collisions in distributed deployments.");

        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);

        String procName = uniqueProcName();
        registry.registerGlobal(procName, new ProcRef<>(proc1), "local-node");

        assertThatThrownBy(
                        () -> registry.registerGlobal(procName, new ProcRef<>(proc2), "local-node"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("Should transfer global registration to another node")
    void transferGlobal_movesRegistrationToNewNode(DtrContext ctx) {
        ctx.say("Process transfer moves registration from one node to another.");
        ctx.say("Used during failover and manual process migration.");
        ctx.say("This implements OTP's distributed process relocation semantics.");

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        String procName = uniqueProcName();
        registry.registerGlobal(procName, procRef, "node1");
        registry.transferGlobal(procName, "node2");

        Optional<GlobalProcRef> found = registry.findGlobal(procName);

        assertThat(found).isPresent();
        assertThat(found.get().nodeName()).isEqualTo("node2");
    }

    @Test
    @DisplayName("Should throw on transfer of non-existent process")
    void transferGlobal_throwsOnNonExistent() {
        assertThatThrownBy(() -> registry.transferGlobal("non-existent", "node2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    @DisplayName("Should handle null process name gracefully")
    void registerGlobal_handlesNullProcessName() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);

        assertThatThrownBy(() -> registry.registerGlobal(null, new ProcRef<>(proc), "local-node"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle null node name gracefully")
    void registerGlobal_handlesNullNodeName() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        String procName = uniqueProcName();

        // Note: Current implementation allows null node names
        // This test verifies that registration succeeds with null node name
        assertThatCode(() -> registry.registerGlobal(procName, new ProcRef<>(proc), null))
                .doesNotThrowAnyException();

        // Verify the process was registered
        assertThat(registry.findGlobal(procName)).isPresent();
    }
}
