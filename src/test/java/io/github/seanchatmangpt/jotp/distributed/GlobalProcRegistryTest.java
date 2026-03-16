package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

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
@DisplayName("GlobalProcRegistry Tests")
class GlobalProcRegistryTest {

    private DefaultGlobalProcRegistry registry;
    private InMemoryGlobalRegistryBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryGlobalRegistryBackend();
        registry = DefaultGlobalProcRegistry.getInstance();
        registry.setBackend(backend);
        registry.setCurrentNodeName("local-node");
    }

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    @Test
    @DisplayName("Should register process globally")
    void registerGlobal_registersProcess() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        registry.registerGlobal("my-proc", procRef, "local-node");

        Optional<GlobalProcRef> found = registry.findGlobal("my-proc");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("my-proc");
        assertThat(found.get().nodeName()).isEqualTo("local-node");
    }

    @Test
    @DisplayName("Should find registered process")
    void findGlobal_returnsRegisteredProcess() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        registry.registerGlobal("my-proc", procRef, "local-node");

        Optional<GlobalProcRef> found = registry.findGlobal("my-proc");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("my-proc");
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
    void unregisterGlobal_removesProcess() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        registry.registerGlobal("my-proc", procRef, "local-node");
        registry.unregisterGlobal("my-proc");

        Optional<GlobalProcRef> found = registry.findGlobal("my-proc");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should handle unregister of non-existent process gracefully")
    void unregisterGlobal_handlesNonExistentProcess() {
        assertThatCode(() -> registry.unregisterGlobal("non-existent")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should list all registered processes")
    void listGlobal_returnsAllProcesses() {
        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc3 = Proc.spawn("initial", (s, m) -> s);

        registry.registerGlobal("proc1", new ProcRef<>(proc1), "local-node");
        registry.registerGlobal("proc2", new ProcRef<>(proc2), "local-node");
        registry.registerGlobal("proc3", new ProcRef<>(proc3), "local-node");

        Map<String, GlobalProcRef> processes = registry.listGlobal();

        assertThat(processes).hasSize(3);
        assertThat(processes).containsKey("proc1");
        assertThat(processes).containsKey("proc2");
        assertThat(processes).containsKey("proc3");
    }

    @Test
    @DisplayName("Should return empty map when no processes registered")
    void listGlobal_returnsEmptyMapWhenNoProcesses() {
        Map<String, GlobalProcRef> processes = registry.listGlobal();

        assertThat(processes).isEmpty();
    }

    @Test
    @DisplayName("Should register if absent atomically")
    void registerGlobalIfAbsent_registersWhenNotPresent() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        boolean success = registry.registerGlobalIfAbsent("my-proc", procRef, "local-node");

        assertThat(success).isTrue();
        assertThat(registry.findGlobal("my-proc")).isPresent();
    }

    @Test
    @DisplayName("Should not register if absent when already present")
    void registerGlobalIfAbsent_doesNotRegisterWhenPresent() {
        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);

        registry.registerGlobal("my-proc", new ProcRef<>(proc1), "local-node");

        boolean success =
                registry.registerGlobalIfAbsent("my-proc", new ProcRef<>(proc2), "local-node");

        assertThat(success).isFalse();
        assertThat(registry.findGlobal("my-proc")).isPresent();
    }

    @Test
    @DisplayName("Should throw on duplicate registration")
    void registerGlobal_throwsOnDuplicate() {
        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);

        registry.registerGlobal("my-proc", new ProcRef<>(proc1), "local-node");

        assertThatThrownBy(
                        () ->
                                registry.registerGlobal(
                                        "my-proc", new ProcRef<>(proc2), "local-node"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("Should transfer global registration to another node")
    void transferGlobal_movesRegistrationToNewNode() {
        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        ProcRef<String, String> procRef = new ProcRef<>(proc);

        registry.registerGlobal("my-proc", procRef, "node1");
        registry.transferGlobal("my-proc", "node2");

        Optional<GlobalProcRef> found = registry.findGlobal("my-proc");

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

        assertThatThrownBy(() -> registry.registerGlobal("my-proc", new ProcRef<>(proc), null))
                .isInstanceOf(NullPointerException.class);
    }
}
