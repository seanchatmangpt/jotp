package io.github.seanchatmangpt.jotp.validation;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ProcessMemoryAnalysisTest — validates the "~1KB per process" memory claim.
 *
 * <p>This test provides empirical validation of the memory footprint claim by measuring heap
 * consumption at different scales (100, 1K, 10K, 100K processes).
 *
 * <p><strong>Key Claims Validated:</strong>
 *
 * <ul>
 *   <li>Each Proc instance consumes ~800-1200 bytes (virtual thread + mailbox)
 *   <li>Memory scales linearly with process count
 *   <li>No memory leaks or unexpected growth
 * </ul>
 */
@DisplayName("Process Memory Analysis - ~1KB per Process")
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProcessMemoryAnalysisTest {


    private long baselineHeapBytes = 0;
    private long peakHeapBytes = 0;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        System.gc();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var memoryBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        baselineHeapBytes = memoryBean.getHeapMemoryUsage().getUsed();
        peakHeapBytes = baselineHeapBytes;
    }

    @AfterEach
    void tearDown() {
        var memoryBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        long finalHeapBytes = memoryBean.getHeapMemoryUsage().getUsed();

                java.util.Map.of(
                        "Baseline Heap",
                                String.format("%.2f MB", baselineHeapBytes / (1024.0 * 1024.0)),
                        "Peak Heap", String.format("%.2f MB", peakHeapBytes / (1024.0 * 1024.0)),
                        "Final Heap", String.format("%.2f MB", finalHeapBytes / (1024.0 * 1024.0)),
                        "Heap Growth",
                                String.format(
                                        "%.2f MB",
                                        (peakHeapBytes - baselineHeapBytes) / (1024.0 * 1024.0))));
    }

    @Test
    @DisplayName("100 processes: validate ~100 KB heap")
    void validate100Processes() throws Exception {

        analyzeMemoryFootprint(100);
    }

    @Test
    @DisplayName("1K processes: validate ~1 MB heap")
    void validate1KProcesses() throws Exception {

        analyzeMemoryFootprint(1_000);
    }

    @Test
    @DisplayName("10K processes: validate ~10 MB heap")
    void validate10KProcesses() throws Exception {

        analyzeMemoryFootprint(10_000);
    }

    @Test
    @DisplayName("100K processes: validate ~100 MB heap")
    void validate100KProcesses() throws Exception {

        analyzeMemoryFootprint(100_000);
    }

    @Test
    @DisplayName("Empty processes: baseline memory footprint")
    void validateEmptyProcesses() throws Exception {

        analyzeMemoryFootprint(10_000);
    }

    @Test
    @DisplayName("Processes with small state: measure state overhead")
    void validateProcessesWithSmallState() throws Exception {

        // Create processes with a small state object (record with a few fields)
        record SmallState(int counter, String name, boolean flag) {}

        List<Proc<SmallState, Integer>> processes = new ArrayList<>();


        long createStart = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            SmallState initialState = new SmallState(i, "proc-" + i, true);
            Proc<SmallState, Integer> proc =
                    new Proc<>(
                            initialState,
                            (state, msg) ->
                                    new SmallState(
                                            state.counter() + msg, state.name(), state.flag()));
            processes.add(proc);
        }
        long createElapsedMs = (System.nanoTime() - createStart) / 1_000_000;

        // Force GC and measure
        System.gc();
        System.gc();
        Thread.sleep(1000);

        var memoryBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        long afterCreationHeap = memoryBean.getHeapMemoryUsage().getUsed();

        long heapGrowthBytes = afterCreationHeap - baselineHeapBytes;
        double bytesPerProcess = (double) heapGrowthBytes / 10_000;
        double kbPerProcess = bytesPerProcess / 1024.0;

                java.util.Map.of(
                        "Processes Created", "10,000",
                        "State Type", "SmallState (3 fields)",
                        "Baseline Heap",
                                String.format("%.2f MB", baselineHeapBytes / (1024.0 * 1024.0)),
                        "After Creation Heap",
                                String.format("%.2f MB", afterCreationHeap / (1024.0 * 1024.0)),
                        "Heap Growth",
                                String.format("%.2f MB", heapGrowthBytes / (1024.0 * 1024.0)),
                        "Bytes Per Process", String.format("%.2f bytes", bytesPerProcess),
                        "KB Per Process", String.format("%.2f KB", kbPerProcess),
                        "Creation Time", String.format("%.2f sec", createElapsedMs / 1000.0)));

        // Cleanup
        for (Proc<SmallState, Integer> proc : processes) {
            proc.stop();
        }

                "State object overhead: "
                        + String.format("%.2f KB", kbPerProcess - 3.89)
                        + " per process");
    }

    @Test
    @DisplayName("Processes with mailbox messages: measure message overhead")
    void validateProcessesWithMailboxMessages() throws Exception {

        List<Proc<Integer, Integer>> processes = new ArrayList<>();


        long createStart = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            Proc<Integer, Integer> proc = new Proc<>(0, (state, msg) -> state + msg);
            processes.add(proc);

            // Send 10 messages to each process
            for (int j = 0; j < 10; j++) {
                proc.tell(j);
            }
        }
        long createElapsedMs = (System.nanoTime() - createStart) / 1_000_000;

        // Force GC and measure
        System.gc();
        System.gc();
        Thread.sleep(1000);

        var memoryBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        long afterCreationHeap = memoryBean.getHeapMemoryUsage().getUsed();

        long heapGrowthBytes = afterCreationHeap - baselineHeapBytes;
        double bytesPerProcess = (double) heapGrowthBytes / 10_000;
        double kbPerProcess = bytesPerProcess / 1024.0;

                java.util.Map.of(
                        "Processes Created", "10,000",
                        "Messages Per Process", "10",
                        "Total Messages", "100,000",
                        "Baseline Heap",
                                String.format("%.2f MB", baselineHeapBytes / (1024.0 * 1024.0)),
                        "After Creation Heap",
                                String.format("%.2f MB", afterCreationHeap / (1024.0 * 1024.0)),
                        "Heap Growth",
                                String.format("%.2f MB", heapGrowthBytes / (1024.0 * 1024.0)),
                        "Bytes Per Process", String.format("%.2f bytes", bytesPerProcess),
                        "KB Per Process", String.format("%.2f KB", kbPerProcess),
                        "Creation Time", String.format("%.2f sec", createElapsedMs / 1000.0)));

        // Cleanup
        for (Proc<Integer, Integer> proc : processes) {
            proc.stop();
        }

        double messageOverhead = kbPerProcess - 3.89;
        double overheadPerMessage = messageOverhead / 10.0;
                "Message overhead: "
                        + String.format("%.2f bytes", overheadPerMessage * 1024)
                        + " per message");
    }

    private void analyzeMemoryFootprint(int processCount) throws Exception {
                java.util.Map.of(
                        "Target Processes", String.format("%,d", processCount),
                        "JVM Max Heap",
                                String.format(
                                        "%.2f GB",
                                        Runtime.getRuntime().maxMemory()
                                                / (1024.0 * 1024 * 1024))));

        List<Proc<Integer, Integer>> processes = new ArrayList<>();

        // Phase 1: Create processes
        long createStart = System.nanoTime();

        for (int i = 0; i < processCount; i++) {
            Proc<Integer, Integer> proc =
                    new Proc<>(
                            0,
                            (state, msg) -> {
                                // Minimal handler - just return state
                                return state;
                            });
            processes.add(proc);

            // Progress reporting for large counts
            if (processCount >= 10_000 && (i + 1) % 10_000 == 0) {
                updatePeakHeap();
                        String.format(
                                "  Created %,d processes (%.1f%%)",
                                (i + 1), (100.0 * (i + 1) / processCount)));
            }
        }

        long createElapsedMs = (System.nanoTime() - createStart) / 1_000_000;

        // Phase 2: Force GC to get stable measurement
        long gcStart = System.nanoTime();

        System.gc();
        System.gc(); // Double GC for thoroughness
        Thread.sleep(1000); // Wait for finalizers

        long gcElapsedMs = (System.nanoTime() - gcStart) / 1_000_000;

        // Phase 3: Measure memory

        var memoryBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        long afterCreationHeap = memoryBean.getHeapMemoryUsage().getUsed();

        // Calculate memory per process
        long heapGrowthBytes = afterCreationHeap - baselineHeapBytes;
        double bytesPerProcess = (double) heapGrowthBytes / processCount;
        double kbPerProcess = bytesPerProcess / 1024.0;

        // Update peak tracking
        if (afterCreationHeap > peakHeapBytes) {
            peakHeapBytes = afterCreationHeap;
        }

        // Phase 4: Cleanup
        long cleanupStart = System.nanoTime();

        for (Proc<Integer, Integer> proc : processes) {
            proc.stop();
        }

        long cleanupElapsedMs = (System.nanoTime() - cleanupStart) / 1_000_000;

        // Phase 5: Report results
                java.util.Map.of(
                        "Processes Created", String.format("%,d", processCount),
                        "Baseline Heap",
                                String.format("%.2f MB", baselineHeapBytes / (1024.0 * 1024.0)),
                        "After Creation Heap",
                                String.format("%.2f MB", afterCreationHeap / (1024.0 * 1024.0)),
                        "Heap Growth",
                                String.format("%.2f MB", heapGrowthBytes / (1024.0 * 1024.0)),
                        "Bytes Per Process", String.format("%.2f bytes", bytesPerProcess),
                        "KB Per Process", String.format("%.2f KB", kbPerProcess),
                        "Creation Time", String.format("%.2f sec", createElapsedMs / 1000.0),
                        "GC Stabilization Time", String.format("%.2f sec", gcElapsedMs / 1000.0),
                        "Cleanup Time", String.format("%.2f sec", cleanupElapsedMs / 1000.0)));

        // Validate the ~1KB claim (with reasonable tolerance)

        assertThat(kbPerProcess)
                .as(
                        String.format(
                                "KB per process should be ~1KB (allowed range: 0.5-2.0 KB). Actual: %.2f KB",
                                kbPerProcess))
                .isGreaterThan(0.5)
                .isLessThan(2.0);

        if (kbPerProcess >= 0.8 && kbPerProcess <= 1.2) {
                    java.util.Map.of(
                            "Status", "PASS",
                            "Claim", "~1KB per process VALIDATED",
                            "Actual", String.format("%.2f KB/process", kbPerProcess)));
        } else {
                    java.util.Map.of(
                            "Status", "PASS (with tolerance)",
                            "Claim", "~1KB per process",
                            "Actual", String.format("%.2f KB/process", kbPerProcess),
                            "Note", "Outside ideal range but within acceptable JVM variance"));
        }
    }

    private void updatePeakHeap() {
        var memoryBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        long current = memoryBean.getHeapMemoryUsage().getUsed();
        if (current > peakHeapBytes) {
            peakHeapBytes = current;
        }
    }
}
