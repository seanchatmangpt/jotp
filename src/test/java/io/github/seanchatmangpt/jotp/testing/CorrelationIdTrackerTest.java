package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.testing.util.CorrelationIdTracker;
import java.util.Set;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
@DisplayName("CorrelationIdTracker")
class CorrelationIdTrackerTest implements WithAssertions {

  private CorrelationIdTracker tracker;

  @BeforeEach
  void setUp() {
    tracker = new CorrelationIdTracker();
  }

  // ── recordStep ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("recordStep")
  class RecordStep {

    @Test
    @DisplayName("creates a new chain for unknown correlation ID")
    void createsNewChain() {
      tracker.recordStep("corr-1", "received");
      assertThat(tracker.getChainCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reuses existing chain for same correlation ID")
    void reusesSameChain() {
      tracker.recordStep("corr-1", "step1");
      tracker.recordStep("corr-1", "step2");
      assertThat(tracker.getChainCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("separate correlation IDs produce separate chains")
    void separateChainsForDifferentIds() {
      tracker.recordStep("corr-1", "step");
      tracker.recordStep("corr-2", "step");
      assertThat(tracker.getChainCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("step count increments with each recordStep call")
    void stepCountIncrements() {
      tracker.recordStep("corr-1", "a");
      tracker.recordStep("corr-1", "b");
      tracker.recordStep("corr-1", "c");
      assertThat(tracker.getStepCount("corr-1")).isEqualTo(3);
    }

    @Test
    @DisplayName("missing chain returns step count of 0")
    void missingChainStepCountZero() {
      assertThat(tracker.getStepCount("nonexistent")).isZero();
    }

    @Test
    @DisplayName("recordStep with processRef stores step reference")
    void recordStepWithRef() {
      var processRef = new Object();
      tracker.recordStep("corr-1", "routed", processRef);

      var chain = tracker.getChain("corr-1");
      assertThat(chain).isNotNull();
      assertThat(chain.steps).hasSize(1);
      assertThat(chain.steps.get(0).stepName).isEqualTo("routed");
      assertThat(chain.steps.get(0).processRef).isSameAs(processRef);
    }
  }

  // ── assertCausalityChain ──────────────────────────────────────────────────

  @Nested
  @DisplayName("assertCausalityChain")
  class AssertCausalityChain {

    @Test
    @DisplayName("exact step sequence passes without throwing")
    void exactSequencePasses() {
      tracker.recordStep("id-1", "received");
      tracker.recordStep("id-1", "routed");
      tracker.recordStep("id-1", "aggregated");

      assertThatCode(() ->
              tracker.assertCausalityChain("id-1", "received", "routed", "aggregated"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("wrong order throws AssertionError")
    void wrongOrderThrows() {
      tracker.recordStep("id-1", "routed");
      tracker.recordStep("id-1", "received");

      assertThatThrownBy(() ->
              tracker.assertCausalityChain("id-1", "received", "routed"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Causality chain mismatch");
    }

    @Test
    @DisplayName("missing step throws AssertionError")
    void missingStepThrows() {
      tracker.recordStep("id-1", "received");

      assertThatThrownBy(() ->
              tracker.assertCausalityChain("id-1", "received", "routed"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Causality chain mismatch");
    }

    @Test
    @DisplayName("extra actual step causes mismatch")
    void extraActualStepCausesMismatch() {
      tracker.recordStep("id-1", "received");
      tracker.recordStep("id-1", "routed");
      tracker.recordStep("id-1", "extra");

      assertThatThrownBy(() ->
              tracker.assertCausalityChain("id-1", "received", "routed"))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("unknown correlation ID throws AssertionError")
    void unknownIdThrows() {
      assertThatThrownBy(() ->
              tracker.assertCausalityChain("nonexistent", "step"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("No correlation chain found");
    }
  }

  // ── assertChainContainsStep ───────────────────────────────────────────────

  @Nested
  @DisplayName("assertChainContainsStep")
  class AssertChainContainsStep {

    @Test
    @DisplayName("present step passes")
    void presentStepPasses() {
      tracker.recordStep("id-2", "processed");

      assertThatCode(() ->
              tracker.assertChainContainsStep("id-2", "processed"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("absent step throws AssertionError")
    void absentStepThrows() {
      tracker.recordStep("id-2", "received");

      assertThatThrownBy(() ->
              tracker.assertChainContainsStep("id-2", "nonexistent"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("does not contain step");
    }

    @Test
    @DisplayName("unknown correlation ID throws AssertionError")
    void unknownIdThrows() {
      assertThatThrownBy(() ->
              tracker.assertChainContainsStep("ghost", "step"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("No correlation chain found");
    }
  }

  // ── assertNoOrphanedMessages ──────────────────────────────────────────────

  @Nested
  @DisplayName("assertNoOrphanedMessages")
  class AssertNoOrphanedMessages {

    @Test
    @DisplayName("all expected IDs tracked passes")
    void allTrackedPasses() {
      tracker.recordStep("id-1", "step");
      tracker.recordStep("id-2", "step");

      assertThatCode(() ->
              tracker.assertNoOrphanedMessages(Set.of("id-1", "id-2")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("missing expected ID throws AssertionError")
    void missingIdThrows() {
      tracker.recordStep("id-1", "step");

      assertThatThrownBy(() ->
              tracker.assertNoOrphanedMessages(Set.of("id-1", "id-missing")))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Orphaned correlation IDs");
    }

    @Test
    @DisplayName("empty expected set passes")
    void emptyExpectedSetPasses() {
      assertThatCode(() ->
              tracker.assertNoOrphanedMessages(Set.of()))
          .doesNotThrowAnyException();
    }
  }

  // ── Distributed tracing headers ───────────────────────────────────────────

  @Nested
  @DisplayName("Tracing headers")
  class TracingHeaders {

    @Test
    @DisplayName("addTracingHeader stores header in chain")
    void addTracingHeaderStored() {
      tracker.recordStep("id-1", "start");
      tracker.addTracingHeader("id-1", "trace-id", "t-abc");

      var chain = tracker.getChain("id-1");
      assertThat(chain.distributedTracingHeaders)
          .containsEntry("trace-id", "t-abc");
    }

    @Test
    @DisplayName("multiple headers stored independently")
    void multipleHeadersStored() {
      tracker.recordStep("id-1", "start");
      tracker.addTracingHeader("id-1", "trace-id", "t1");
      tracker.addTracingHeader("id-1", "span-id", "s1");

      var headers = tracker.getChain("id-1").distributedTracingHeaders;
      assertThat(headers).containsEntry("trace-id", "t1").containsEntry("span-id", "s1");
    }

    @Test
    @DisplayName("addTracingHeader creates chain if not present")
    void createsChainIfAbsent() {
      tracker.addTracingHeader("new-id", "h", "v");
      assertThat(tracker.getChain("new-id")).isNotNull();
    }
  }

  // ── Chain latency ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Chain latency")
  class ChainLatency {

    @Test
    @DisplayName("latency is 0 for unknown chain")
    void unknownChainLatencyZero() {
      assertThat(tracker.getChainLatencyMillis("ghost")).isZero();
    }

    @Test
    @DisplayName("latency is 0 for chain with no steps")
    void emptyChainLatencyZero() {
      tracker.addTracingHeader("id-1", "h", "v"); // creates chain but no steps
      assertThat(tracker.getChainLatencyMillis("id-1")).isZero();
    }

    @Test
    @DisplayName("latency between first and last step is non-negative")
    void latencyBetweenStepsNonNegative() throws InterruptedException {
      tracker.recordStep("id-1", "start");
      Thread.sleep(5); // small delay so timestamps differ
      tracker.recordStep("id-1", "end");

      assertThat(tracker.getChainLatencyMillis("id-1")).isGreaterThanOrEqualTo(0);
    }
  }

  // ── Reporting ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Reporting")
  class Reporting {

    @Test
    @DisplayName("reportGraphs contains chain info")
    void reportGraphsContainsChainInfo() {
      tracker.recordStep("corr-A", "step1");
      tracker.recordStep("corr-A", "step2");

      var report = tracker.reportGraphs();
      assertThat(report).contains("corr-A").contains("step1");
    }

    @Test
    @DisplayName("reportGraphs is empty for no chains")
    void reportGraphsEmptyForNoChains() {
      assertThat(tracker.reportGraphs()).isEmpty();
    }

    @Test
    @DisplayName("toString shows chain count")
    void toStringShowsChainCount() {
      tracker.recordStep("id-1", "s");
      tracker.recordStep("id-2", "s");
      assertThat(tracker.toString()).contains("chains=2");
    }

    @Test
    @DisplayName("getAllChains returns unmodifiable view")
    void getAllChainsUnmodifiable() {
      tracker.recordStep("id-1", "step");
      var chains = tracker.getAllChains();
      assertThatThrownBy(() -> chains.put("new", null))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  // ── clear ─────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("clear()")
  class Clear {

    @Test
    @DisplayName("clear resets chain count to 0")
    void clearResetsChainCount() {
      tracker.recordStep("id-1", "s");
      tracker.recordStep("id-2", "s");
      tracker.clear();
      assertThat(tracker.getChainCount()).isZero();
    }

    @Test
    @DisplayName("getChain returns null after clear")
    void getChainNullAfterClear() {
      tracker.recordStep("id-1", "s");
      tracker.clear();
      assertThat(tracker.getChain("id-1")).isNull();
    }

    @Test
    @DisplayName("can record steps again after clear")
    void canRecordAfterClear() {
      tracker.recordStep("id-1", "s");
      tracker.clear();
      tracker.recordStep("id-1", "new-step");
      assertThat(tracker.getStepCount("id-1")).isEqualTo(1);
    }
  }

  // ── CorrelationChain inner class ──────────────────────────────────────────

  @Nested
  @DisplayName("CorrelationChain")
  class CorrelationChainClass {

    @Test
    @DisplayName("isEmpty returns true for new chain")
    void isEmptyForNewChain() {
      tracker.addTracingHeader("id-1", "h", "v");
      assertThat(tracker.getChain("id-1").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("isEmpty returns false after adding step")
    void isNotEmptyAfterStep() {
      tracker.recordStep("id-1", "step");
      assertThat(tracker.getChain("id-1").isEmpty()).isFalse();
    }

    @Test
    @DisplayName("getStepNames returns step names in order")
    void getStepNamesInOrder() {
      tracker.recordStep("id-1", "alpha");
      tracker.recordStep("id-1", "beta");
      tracker.recordStep("id-1", "gamma");

      assertThat(tracker.getChain("id-1").getStepNames())
          .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    @DisplayName("chain toString contains correlationId")
    void chainToStringContainsId() {
      tracker.recordStep("my-corr", "step");
      assertThat(tracker.getChain("my-corr").toString()).contains("my-corr");
    }
  }
}
