package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.testing.util.PerformanceTestHelper;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
@DisplayName("PerformanceTestHelper")
class PerformanceTestHelperTest implements WithAssertions {

    private PerformanceTestHelper helper;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        helper = new PerformanceTestHelper();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("fresh instance returns zero for all metrics")
        void freshInstanceZeros() {
            assertThat(helper.getThroughputPerSecond()).isZero();
            assertThat(helper.getP50LatencyMillis()).isZero();
            assertThat(helper.getP95LatencyMillis()).isZero();
            assertThat(helper.getP99LatencyMillis()).isZero();
            assertThat(helper.getMemoryDeltaMB()).isZero();
        }

        @Test
        @DisplayName("start then stop enables timing")
        void startStopEnablesTiming() {
            helper.start();
            helper.recordLatency(1_000_000L); // 1 ms
            helper.stop();
            assertThat(helper.getP50LatencyMillis()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("reset clears all recorded data")
        void resetClearsData() {
            helper.start();
            helper.recordLatency(5_000_000L);
            helper.stop();
            assertThat(helper.getP50LatencyMillis()).isGreaterThan(0);

            helper.reset();

            assertThat(helper.getThroughputPerSecond()).isZero();
            assertThat(helper.getP50LatencyMillis()).isZero();
            assertThat(helper.getP99LatencyMillis()).isZero();
        }
    }

    // ── Latency percentiles ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Latency percentiles")
    class LatencyPercentiles {

        @Test
        @DisplayName("empty state returns 0 for all percentiles")
        void emptyReturnsZero() {
            helper.start();
            helper.stop();
            assertThat(helper.getP50LatencyMillis()).isZero();
            assertThat(helper.getP95LatencyMillis()).isZero();
            assertThat(helper.getP99LatencyMillis()).isZero();
        }

        @Test
        @DisplayName("single latency returns that value for all percentiles")
        void singleLatency() {
            helper.start();
            helper.recordLatency(10_000_000L); // 10 ms
            helper.stop();

            assertThat(helper.getP50LatencyMillis()).isEqualTo(10L);
            assertThat(helper.getP95LatencyMillis()).isEqualTo(10L);
            assertThat(helper.getP99LatencyMillis()).isEqualTo(10L);
        }

        @Test
        @DisplayName("100 uniform latencies yield correct p50 p95 p99")
        void uniformLatencies() {
            helper.start();
            // Record latencies 1ms through 100ms (as nanos)
            for (int i = 1; i <= 100; i++) {
                helper.recordLatency((long) i * 1_000_000L);
            }
            helper.stop();

            // p50 index = 50 → sorted[50] = 51ms; allow some off-by-one
            assertThat(helper.getP50LatencyMillis()).isBetween(49L, 52L);
            assertThat(helper.getP95LatencyMillis()).isBetween(93L, 97L);
            assertThat(helper.getP99LatencyMillis()).isBetween(97L, 100L);
        }

        @Test
        @DisplayName("getPercentileLatencyMillis converts nanos to millis")
        void nanosToMillisConversion() {
            helper.start();
            helper.recordLatency(42_000_000L); // exactly 42 ms
            helper.stop();

            assertThat(helper.getPercentileLatencyMillis(0.5)).isEqualTo(42L);
        }

        @Test
        @DisplayName("percentile 0.0 returns minimum latency")
        void percentileZeroReturnsMin() {
            helper.start();
            helper.recordLatency(100_000_000L); // 100 ms
            helper.recordLatency(10_000_000L); // 10 ms
            helper.recordLatency(50_000_000L); // 50 ms
            helper.stop();

            assertThat(helper.getPercentileLatencyMillis(0.0)).isEqualTo(10L);
        }

        @Test
        @DisplayName("percentile 1.0 returns maximum latency")
        void percentileOneReturnsMax() {
            helper.start();
            helper.recordLatency(100_000_000L); // 100 ms
            helper.recordLatency(10_000_000L); // 10 ms
            helper.recordLatency(50_000_000L); // 50 ms
            helper.stop();

            assertThat(helper.getPercentileLatencyMillis(1.0)).isEqualTo(100L);
        }
    }

    // ── Throughput ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Throughput")
    class Throughput {

        @Test
        @DisplayName("zero duration returns zero throughput")
        void zeroDurationReturnsZero() {
            // Don't call start/stop — both times are 0
            helper.recordLatency(1_000_000L);
            assertThat(helper.getThroughputPerSecond()).isZero();
        }

        @Test
        @DisplayName("throughput increases with more messages in same time")
        void throughputScalesWithMessageCount() throws InterruptedException {
            helper.start();
            for (int i = 0; i < 1000; i++) {
                helper.recordLatency(1_000_000L);
            }
            helper.stop();

            // With 1000 messages, throughput should be measurable
            assertThat(helper.getThroughputPerSecond()).isGreaterThan(0);
        }
    }

    // ── Assertion methods ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Assertion methods")
    class AssertionMethods {

        @Test
        @DisplayName("assertMinThroughput passes when threshold is zero")
        void assertMinThroughputPassesAtZero() {
            helper.start();
            helper.stop();
            // Should not throw — threshold is 0
            assertThatCode(() -> helper.assertMinThroughput(0)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertMinThroughput throws AssertionError when below threshold")
        void assertMinThroughputThrowsWhenBelow() {
            // No messages recorded → throughput is 0
            helper.start();
            helper.stop();
            assertThatThrownBy(() -> helper.assertMinThroughput(Long.MAX_VALUE))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Throughput below threshold");
        }

        @Test
        @DisplayName("assertP50Latency passes when latency is within threshold")
        void assertP50LatencyPasses() {
            helper.start();
            helper.recordLatency(5_000_000L); // 5 ms
            helper.stop();
            assertThatCode(() -> helper.assertP50Latency(100)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertP50Latency throws when latency exceeds threshold")
        void assertP50LatencyThrows() {
            helper.start();
            helper.recordLatency(100_000_000L); // 100 ms
            helper.stop();
            assertThatThrownBy(() -> helper.assertP50Latency(1))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("P50 latency exceeded");
        }

        @Test
        @DisplayName("assertP95Latency throws when latency exceeds threshold")
        void assertP95LatencyThrows() {
            helper.start();
            for (int i = 1; i <= 100; i++) {
                helper.recordLatency((long) i * 1_000_000L);
            }
            helper.stop();
            assertThatThrownBy(() -> helper.assertP95Latency(1))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("P95 latency exceeded");
        }

        @Test
        @DisplayName("assertP99Latency throws when latency exceeds threshold")
        void assertP99LatencyThrows() {
            helper.start();
            helper.recordLatency(200_000_000L); // 200 ms
            helper.stop();
            assertThatThrownBy(() -> helper.assertP99Latency(10))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("P99 latency exceeded");
        }

        @Test
        @DisplayName("assertMaxMemory passes with large threshold")
        void assertMaxMemoryPasses() {
            helper.start();
            helper.stop();
            // Memory delta is very small or zero for this trivial test
            assertThatCode(() -> helper.assertMaxMemory(1024)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertMaxMemory throws when memory exceeds threshold of -1 MB")
        void assertMaxMemoryThrowsWhenNegativeThreshold() {
            helper.start();
            helper.stop();
            // The delta might be 0 or positive; with -1 threshold it may throw
            // Memory delta of 0 > -1 so assertion throws
            assertThatThrownBy(() -> helper.assertMaxMemory(-1))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Memory usage exceeded");
        }
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reporting")
    class Reporting {

        @Test
        @DisplayName("getSummary returns non-empty string with key fields")
        void getSummaryContainsKeyFields() {
            helper.start();
            helper.recordLatency(1_000_000L);
            helper.stop();

            var summary = helper.getSummary();
            assertThat(summary).isNotEmpty().contains("Throughput").contains("Latency");
        }

        @Test
        @DisplayName("getDetailedReport contains message count and latency sections")
        void getDetailedReportContainsSections() {
            helper.start();
            helper.recordLatency(5_000_000L);
            helper.stop();

            var report = helper.getDetailedReport();
            assertThat(report)
                    .isNotEmpty()
                    .contains("Messages")
                    .contains("Duration")
                    .contains("Throughput")
                    .contains("Latency P50")
                    .contains("Latency P99");
        }

        @Test
        @DisplayName("toString contains summary information")
        void toStringContainsSummary() {
            helper.start();
            helper.stop();
            assertThat(helper.toString()).startsWith("PerformanceTestHelper[");
        }
    }
}
