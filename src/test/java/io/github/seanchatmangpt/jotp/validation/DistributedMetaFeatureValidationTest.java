package io.github.seanchatmangpt.jotp.validation;

import io.github.seanchatmangpt.jotp.distributed.*;
import io.github.seanchatmangpt.jotp.testing.*;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive validation test suite for distributed and meta features.
 *
 * <p>This test validates:
 * <ul>
 *   <li>Distributed process registry (GlobalProcRegistry)
 *   <li>Global sequence number generation (GlobalSequenceService)
 *   <li>Node discovery mechanisms
 *   <li>Message recording and replay
 *   <li>Crash simulation and recovery
 *   <li>Cluster membership and health checks
 * </ul>
 */
@DisplayName("Distributed and Meta Feature Validation")
public class DistributedMetaFeatureValidationTest {

    private GlobalProcRegistry globalRegistry;
    private GlobalSequenceService sequenceService;
    private NodeDiscovery nodeDiscovery;
    private MessageRecorder messageRecorder;
    private DeterministicClock clock;

    @BeforeEach
    void setUp() {
        // Reset test environment
        ApplicationController.reset();

        // Initialize components
        globalRegistry = GlobalProcRegistry.getInstance();
        nodeDiscovery = new StaticNodeDiscovery("node1", List.of("node1", "node2", "node3"));
        sequenceService = GlobalSequenceService.create("node1", nodeDiscovery);
        clock = DeterministicClock.create();
        messageRecorder = new MessageRecorder(clock);
    }

    // ===== GLOBAL PROCESS REGISTRY TESTS =====

    @Test
    @DisplayName("Global Process Registry - Basic Registration and Lookup")
    void testGlobalProcRegistryBasicOperations() {
        // Create a process
        ProcRef<String, String> procRef = spawnTestProc("test-proc");

        // Register globally
        globalRegistry.registerGlobal("global.test", procRef, "node1");

        // Verify registration
        Optional<GlobalProcRef> found = globalRegistry.findGlobal("global.test");
        assertThat(found).isPresent();
        assertThat(found.get().nodeName()).isEqualTo("node1");
        assertThat(found.get().localRef()).isEqualTo(procRef);

        // List all registrations
        Map<String, GlobalProcRef> all = globalRegistry.listGlobal();
        assertThat(all).hasSize(1);
        assertThat(all).containsKey("global.test");
    }

    @Test
    @DisplayName("Global Process Registry - Concurrent Registration")
    void testGlobalProcRegistryConcurrentRegistration() {
        // Create multiple processes
        ProcRef<String, String> proc1 = spawnTestProc("proc1");
        ProcRef<String, String> proc2 = spawnTestProc("proc2");

        // Register concurrently
        globalRegistry.registerGlobal("service.db", proc1, "node1");
        boolean success = globalRegistry.registerGlobalIfAbsent("service.db", proc2, "node2");

        // Should fail to register existing name
        assertThat(success).isFalse();

        // Original registration should remain
        Optional<GlobalProcRef> found = globalRegistry.findGlobal("service.db");
        assertThat(found).isPresent();
        assertThat(found.get().nodeName()).isEqualTo("node1");
    }

    @Test
    @DisplayName("Global Process Registry - Failover Transfer")
    void testGlobalProcRegistryFailoverTransfer() {
        ProcRef<String, String> procRef = spawnTestProc("critical-service");
        globalRegistry.registerGlobal("critical.service", procRef, "node1");

        // Simulate node failure and transfer to node2
        globalRegistry.transferGlobal("critical.service", "node2");

        // Should still find the process
        Optional<GlobalProcRef> found = globalRegistry.findGlobal("critical.service");
        assertThat(found).isPresent();
        assertThat(found.get().nodeName()).isEqualTo("node2");
    }

    // ===== GLOBAL SEQUENCE SERVICE TESTS =====

    @Test
    @DisplayName("Global Sequence Service - Basic Sequence Generation")
    void testGlobalSequenceServiceBasic() {
        // Generate sequence numbers
        long seq1 = sequenceService.nextGlobalSeq();
        long seq2 = sequenceService.nextGlobalSeq();
        long seq3 = sequenceService.nextGlobalSeq();

        // Verify monotonicity
        assertThat(seq2).isGreaterThan(seq1);
        assertThat(seq3).isGreaterThan(seq2);

        // Verify high-water mark
        long highWater = sequenceService.currentHighWaterMark();
        assertThat(highWater).isEqualTo(seq3);
    }

    @Test
    @DisplayName("Global Sequence Service - Cross-Node Ordering")
    void testGlobalSequenceServiceCrossNodeOrdering() {
        // Create sequence service on different nodes
        GlobalSequenceService service2 = GlobalSequenceService.create("node2", nodeDiscovery);

        // Generate on node1
        long seq1 = sequenceService.nextGlobalSeq();

        // Generate on node2
        long seq2 = service2.nextGlobalSeq();

        // Synchronize
        sequenceService.synchronizeWithPeers(List.of("node2"));
        service2.synchronizeWithPeers(List.of("node1"));

        // After sync, high-water mark should include both
        assertThat(sequenceService.currentHighWaterMark()).isGreaterThan(seq1);
        assertThat(service2.currentHighWaterMark()).isGreaterThan(seq2);
    }

    // ===== NODE DISCOVERY TESTS =====

    @Test
    @DisplayName("Node Discovery - Static Configuration")
    void testNodeDiscoveryStatic() {
        StaticNodeDiscovery discovery = new StaticNodeDiscovery("test", List.of("node1", "node2", "node3"));

        List<String> nodes = discovery.getHealthyNodes();
        assertThat(nodes).containsExactlyInAnyOrder("node1", "node2", "node3");

        // Simulate node failure
        discovery.markNodeUnhealthy("node2");
        nodes = discovery.getHealthyNodes();
        assertThat(nodes).containsExactlyInAnyOrder("node1", "node3");
    }

    @Test
    @DisplayName("Node Discovery - Dynamic Membership")
    void testNodeDiscoveryDynamic() {
        InMemoryDiscoveryProvider provider = new InMemoryDiscoveryProvider();
        DynamicNodeDiscovery discovery = new DynamicNodeDiscovery("node1", provider);

        // Initial state
        assertThat(discovery.getHealthyNodes()).isEmpty();

        // Add nodes
        provider.addNode("node2", "192.168.1.2:9100");
        provider.addNode("node3", "192.168.1.3:9100");

        assertThat(discovery.getHealthyNodes()).containsExactlyInAnyOrder("node2", "node3");
    }

    // ===== MESSAGE RECORDING TESTS =====

    @Test
    @DisplayName("Message Recorder - Recording and Replay")
    void testMessageRecorderReplay() throws Exception {
        // Start recording
        try (var recorder = MessageRecorder.create().startRecording()) {
            // Create processes and exchange messages
            ProcRef<String, String> proc1 = spawnTestProc("sender");
            ProcRef<String, String> proc2 = spawnTestProc("receiver");

            // Send messages
            proc1.tell(proc2, "hello");
            proc2.tell(proc1, "world");

            // Allow message processing
            Thread.sleep(100);
        }

        // Verify recording
        List<RecordedMessage> messages = messageRecorder.getRecordedMessages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).message()).isEqualTo("hello");
        assertThat(messages.get(1).message()).isEqualTo("world");
    }

    @Test
    @DisplayName("Message Recorder - Deterministic Replay")
    void testMessageRecorderDeterministicReplay() throws Exception {
        // First run - record
        try (var recorder = MessageRecorder.create().startRecording()) {
            exchangeMessages();
        }

        // Get sequence
        List<RecordedMessage> original = messageRecorder.getRecordedMessages();
        long finalTime = original.get(original.size() - 1).logicalTime();

        // Second run - replay with same timing
        try (var replay = MessageRecorder.create()
                .loadRecording(messageRecorder.getRecordingPath())
                .startReplay()) {

            exchangeMessages();
        }

        // Should produce same result
        List<RecordedMessage> replayed = messageRecorder.getRecordedMessages();
        assertThat(replayed).hasSameSizeAs(original);
        assertThat(replayed.get(0).logicalTime()).isEqualTo(original.get(0).logicalTime());
    }

    // ===== CRASH SIMULATION TESTS =====

    @Test
    @DisplayName("Crash Simulation - Process Crash Recovery")
    void testCrashSimulationProcessCrash() {
        // Create a supervised process
        Supervisor supervisor = Supervisor.create("test-supervisor", Supervisor.Strategy.ONE_FOR_ONE);

        // Create process under supervision
        ProcRef<String, String> procRef = spawnSupervisedProc(supervisor, "crash-prone");

        // Crash the process
        CrashSimulation.simulateCrash(procRef);

        // Should be restarted by supervisor
        assertThat(supervisor.getChildCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Crash Simulation - Network Partition")
    void testCrashSimulationNetworkPartition() {
        // Create nodes
        NodeDiscovery discovery1 = new StaticNodeDiscovery("node1", List.of("node1", "node2"));
        NodeDiscovery discovery2 = new StaticNodeDiscovery("node2", List.of("node1", "node2"));

        // Simulate partition
        discovery1.markNodeUnhealthy("node2");
        discovery2.markNodeUnhealthy("node1");

        // Each node should operate independently
        GlobalSequenceService service1 = GlobalSequenceService.create("node1", discovery1);
        GlobalSequenceService service2 = GlobalSequenceService.create("node2", discovery2);

        // Both should still generate sequences
        assertThat(service1.nextGlobalSeq()).isGreaterThan(0);
        assertThat(service2.nextGlobalSeq()).isGreaterThan(0);
    }

    // ===== STRESS TESTS =====

    @ParameterizedTest
    @ValueSource(ints = {100, 1000, 10000})
    @DisplayName("Global Registry Stress Test")
    void testGlobalRegistryStress(int operations) {
        // Concurrent registrations
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < operations / 10; j++) {
                    ProcRef<String, String> procRef = spawnTestProc("stress-" + Thread.currentThread().getName() + "-" + j);
                    globalRegistry.registerGlobalIfAbsent("stress.test", procRef, "node1");
                }
            });
            threads.add(t);
        }

        // Start all threads
        threads.forEach(Thread::start);

        // Wait for completion
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Should have exactly one registration
        Map<String, GlobalProcRef> all = globalRegistry.listGlobal();
        assertThat(all).hasSize(1);
    }

    @Test
    @DisplayName("Sequence Service Performance Test")
    void testSequenceServicePerformance() {
        // Measure sequence generation performance
        long start = System.nanoTime();

        for (int i = 0; i < 100000; i++) {
            sequenceService.nextGlobalSeq();
        }

        long duration = System.nanoTime() - start;
        double opsPerMs = 100000.0 / (duration / 1_000_000.0);

        System.out.printf("Sequence generation: %.2f ops/ms%n", opsPerMs);
        assertThat(opsPerMs).isGreaterThan(1000); // Should be > 1K ops/ms
    }

    // ===== UTILITY METHODS =====

    private ProcRef<String, String> spawnTestProc(String name) {
        return spawn(Proc.<String, String>create((state, msg) -> state + ":" + msg, name));
    }

    private ProcRef<String, String> spawnSupervisedProc(Supervisor supervisor, String name) {
        return supervisor.spawn(Proc.create((state, msg) -> state + ":" + msg, name));
    }

    private void exchangeMessages() throws InterruptedException {
        ProcRef<String, String> proc1 = spawnTestProc("test1");
        ProcRef<String, String> proc2 = spawnTestProc("test2");

        proc1.tell(proc2, "message1");
        proc2.tell(proc1, "message2");

        Thread.sleep(50); // Allow processing
    }
}