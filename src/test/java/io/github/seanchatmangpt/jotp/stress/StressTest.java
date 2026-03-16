package io.github.seanchatmangpt.jotp.stress;

import java.time.Duration;

/**
 * StressTest — main entry point and configuration for stress testing.
 *
 * <p>Provides a fluent API for configuring and running stress tests with different load profiles.
 * Captures performance metrics and generates reports for capacity planning.
 */
public final class StressTest {

    private final String testName;
    private final StressTestBase base;

    private StressTest(String testName) {
        this.testName = testName;
        this.base = new StressTestBase() {};
    }

    /**
     * Create a new stress test with the given name.
     *
     * @param testName descriptive name for the test
     * @return StressTest builder
     */
    public static StressTest create(String testName) {
        return new StressTest(testName);
    }

    /**
     * Run the stress test with a constant load profile.
     *
     * @param load constant load (operations per second)
     * @param duration test duration
     * @param workload workload to execute
     * @return collected metrics
     */
    public MetricsCollector runConstantLoad(
            long load, Duration duration, StressTestBase.WorkloadFunction workload) {
        LoadProfile profile = new LoadProfile.ConstantLoad(load, duration);
        return base.runStressTest(testName, profile, workload);
    }

    /**
     * Run the stress test with a ramp load profile.
     *
     * @param startLoad starting load
     * @param endLoad ending load
     * @param duration test duration
     * @param workload workload to execute
     * @return collected metrics
     */
    public MetricsCollector runRampLoad(
            long startLoad,
            long endLoad,
            Duration duration,
            StressTestBase.WorkloadFunction workload) {
        LoadProfile profile = new LoadProfile.RampLoad(startLoad, endLoad, duration);
        return base.runStressTest(testName, profile, workload);
    }

    /**
     * Run the stress test with a spike load profile.
     *
     * @param baselineLoad baseline load
     * @param spikeLoad spike load
     * @param baselineLoad2 return to baseline
     * @param duration test duration
     * @param workload workload to execute
     * @return collected metrics
     */
    public MetricsCollector runSpikeLoad(
            long baselineLoad,
            long spikeLoad,
            long baselineLoad2,
            Duration duration,
            StressTestBase.WorkloadFunction workload) {
        LoadProfile profile =
                new LoadProfile.SpikeLoad(baselineLoad, spikeLoad, baselineLoad2, duration);
        return base.runStressTest(testName, profile, workload);
    }

    /** Clean up resources after testing. */
    public void cleanup() {
        base.cleanup();
    }
}
