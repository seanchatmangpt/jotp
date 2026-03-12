package io.github.seanchatmangpt.jotp.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.testing.annotations.AsyncPatternTest;
import io.github.seanchatmangpt.jotp.testing.annotations.CorrelationTest;
import io.github.seanchatmangpt.jotp.testing.annotations.IntegrationPattern;
import io.github.seanchatmangpt.jotp.testing.annotations.JotpTest;
import io.github.seanchatmangpt.jotp.testing.annotations.MessageCapture;
import io.github.seanchatmangpt.jotp.testing.annotations.PatternTest;
import io.github.seanchatmangpt.jotp.testing.annotations.PerformanceBaseline;
import io.github.seanchatmangpt.jotp.testing.annotations.ProcessFixture;
import io.github.seanchatmangpt.jotp.testing.annotations.VirtualThreaded;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
@DisplayName("Annotations Validation")
class AnnotationsValidationTest implements WithAssertions {

  // ── Helper ───────────────────────────────────────────────────────────────

  private static void assertRuntimeRetention(Class<?> annotationType) {
    var retention = annotationType.getAnnotation(Retention.class);
    assertThat(retention)
        .as("@Retention present on %s", annotationType.getSimpleName())
        .isNotNull();
    assertThat(retention.value())
        .as("%s retention policy", annotationType.getSimpleName())
        .isEqualTo(RetentionPolicy.RUNTIME);
  }

  private static void assertTargetContains(Class<?> annotationType, ElementType... expected) {
    var target = annotationType.getAnnotation(Target.class);
    assertThat(target)
        .as("@Target present on %s", annotationType.getSimpleName())
        .isNotNull();
    assertThat(target.value())
        .as("%s @Target elements", annotationType.getSimpleName())
        .contains(expected);
  }

  // ── @JotpTest ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@JotpTest")
  class JotpTestAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(JotpTest.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(JotpTest.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("default primitive is empty string")
    void defaultPrimitive() throws Exception {
      var method = JotpTest.class.getDeclaredMethod("primitive");
      assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    @DisplayName("default primitives is empty array")
    void defaultPrimitives() throws Exception {
      var method = JotpTest.class.getDeclaredMethod("primitives");
      assertThat((String[]) method.getDefaultValue()).isEmpty();
    }

    @Test
    @DisplayName("boolean defaults are false")
    void booleanDefaults() throws Exception {
      for (var name :
          new String[] {
            "testCrashRecovery", "testStateIntrospection",
            "testProcessLinks", "testProcessMonitors", "testProcessRegistry"
          }) {
        var m = JotpTest.class.getDeclaredMethod(name);
        assertThat(m.getDefaultValue())
            .as("JotpTest.%s() default", name)
            .isEqualTo(false);
      }
    }
  }

  // ── @PatternTest ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@PatternTest")
  class PatternTestAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(PatternTest.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(PatternTest.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("async defaults to true")
    void asyncDefaultTrue() throws Exception {
      var method = PatternTest.class.getDeclaredMethod("async");
      assertThat(method.getDefaultValue()).isEqualTo(true);
    }

    @Test
    @DisplayName("category and description default to empty string")
    void stringDefaults() throws Exception {
      for (var name : new String[] {"category", "description"}) {
        var m = PatternTest.class.getDeclaredMethod(name);
        assertThat(m.getDefaultValue()).as("PatternTest.%s() default", name).isEqualTo("");
      }
    }

    @Test
    @DisplayName("pattern attribute has no default (required)")
    void patternHasNoDefault() throws Exception {
      var method = PatternTest.class.getDeclaredMethod("pattern");
      assertThat(method.getDefaultValue())
          .as("PatternTest.pattern() should have no default")
          .isNull();
    }
  }

  // ── @AsyncPatternTest ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("@AsyncPatternTest")
  class AsyncPatternTestAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(AsyncPatternTest.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(AsyncPatternTest.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("is meta-annotated with @PatternTest(async=true)")
    void isMetaAnnotatedWithPatternTest() {
      var meta = AsyncPatternTest.class.getAnnotation(PatternTest.class);
      assertThat(meta).isNotNull();
      assertThat(meta.async()).isTrue();
    }

    @Test
    @DisplayName("timeoutValue defaults to 5")
    void timeoutValueDefault() throws Exception {
      var method = AsyncPatternTest.class.getDeclaredMethod("timeoutValue");
      assertThat(method.getDefaultValue()).isEqualTo(5L);
    }

    @Test
    @DisplayName("timeoutUnit defaults to SECONDS")
    void timeoutUnitDefault() throws Exception {
      var method = AsyncPatternTest.class.getDeclaredMethod("timeoutUnit");
      assertThat(method.getDefaultValue()).isEqualTo(TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("virtualThreadOnly defaults to false")
    void virtualThreadOnlyDefaultFalse() throws Exception {
      var method = AsyncPatternTest.class.getDeclaredMethod("virtualThreadOnly");
      assertThat(method.getDefaultValue()).isEqualTo(false);
    }

    @Test
    @DisplayName("trackCorrelationIds defaults to true")
    void trackCorrelationIdsDefaultTrue() throws Exception {
      var method = AsyncPatternTest.class.getDeclaredMethod("trackCorrelationIds");
      assertThat(method.getDefaultValue()).isEqualTo(true);
    }
  }

  // ── @CorrelationTest ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("@CorrelationTest")
  class CorrelationTestAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(CorrelationTest.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(CorrelationTest.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("autoGenerate defaults to true")
    void autoGenerateDefaultTrue() throws Exception {
      var method = CorrelationTest.class.getDeclaredMethod("autoGenerate");
      assertThat(method.getDefaultValue()).isEqualTo(true);
    }

    @Test
    @DisplayName("reportGraphs defaults to false")
    void reportGraphsDefaultFalse() throws Exception {
      var method = CorrelationTest.class.getDeclaredMethod("reportGraphs");
      assertThat(method.getDefaultValue()).isEqualTo(false);
    }

    @Test
    @DisplayName("maxChainDepth defaults to 0")
    void maxChainDepthDefaultZero() throws Exception {
      var method = CorrelationTest.class.getDeclaredMethod("maxChainDepth");
      assertThat(method.getDefaultValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("validateDistributedTracing defaults to true")
    void validateDistributedTracingDefaultTrue() throws Exception {
      var method = CorrelationTest.class.getDeclaredMethod("validateDistributedTracing");
      assertThat(method.getDefaultValue()).isEqualTo(true);
    }
  }

  // ── @MessageCapture ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("@MessageCapture")
  class MessageCaptureAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(MessageCapture.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(MessageCapture.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("captureAll defaults to true")
    void captureAllDefaultTrue() throws Exception {
      var method = MessageCapture.class.getDeclaredMethod("captureAll");
      assertThat(method.getDefaultValue()).isEqualTo(true);
    }

    @Test
    @DisplayName("maxMessages defaults to 1000")
    void maxMessagesDefault1000() throws Exception {
      var method = MessageCapture.class.getDeclaredMethod("maxMessages");
      assertThat(method.getDefaultValue()).isEqualTo(1000);
    }

    @Test
    @DisplayName("onlyTypes and excludeTypes default to empty arrays")
    void arrayDefaults() throws Exception {
      for (var name : new String[] {"onlyTypes", "excludeTypes"}) {
        var m = MessageCapture.class.getDeclaredMethod(name);
        assertThat((String[]) m.getDefaultValue())
            .as("MessageCapture.%s() default", name)
            .isEmpty();
      }
    }

    @Test
    @DisplayName("includePayload defaults to true, traceStackTrace to false")
    void booleanDefaults() throws Exception {
      assertThat(MessageCapture.class.getDeclaredMethod("includePayload").getDefaultValue())
          .isEqualTo(true);
      assertThat(MessageCapture.class.getDeclaredMethod("traceStackTrace").getDefaultValue())
          .isEqualTo(false);
    }
  }

  // ── @VirtualThreaded ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("@VirtualThreaded")
  class VirtualThreadedAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(VirtualThreaded.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(VirtualThreaded.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("mode defaults to VIRTUAL_ONLY")
    void modeDefaultVirtualOnly() throws Exception {
      var method = VirtualThreaded.class.getDeclaredMethod("mode");
      assertThat(method.getDefaultValue()).isEqualTo(VirtualThreaded.ThreadMode.VIRTUAL_ONLY);
    }

    @Test
    @DisplayName("ThreadMode enum has VIRTUAL_ONLY, PLATFORM_ONLY, BOTH")
    void threadModeEnumValues() {
      var values = VirtualThreaded.ThreadMode.values();
      assertThat(values)
          .extracting(Enum::name)
          .containsExactlyInAnyOrder("VIRTUAL_ONLY", "PLATFORM_ONLY", "BOTH");
    }

    @Test
    @DisplayName("numeric defaults are zero, booleans are false")
    void numericAndBooleanDefaults() throws Exception {
      assertThat(VirtualThreaded.class.getDeclaredMethod("maxPinningMillis").getDefaultValue())
          .isEqualTo(0L);
      assertThat(VirtualThreaded.class.getDeclaredMethod("expectedThreadCount").getDefaultValue())
          .isEqualTo(0);
      assertThat(VirtualThreaded.class.getDeclaredMethod("noPinning").getDefaultValue())
          .isEqualTo(false);
      assertThat(
              VirtualThreaded.class
                  .getDeclaredMethod("validateContextPropagation")
                  .getDefaultValue())
          .isEqualTo(false);
    }
  }

  // ── @PerformanceBaseline ──────────────────────────────────────────────────

  @Nested
  @DisplayName("@PerformanceBaseline")
  class PerformanceBaselineAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(PerformanceBaseline.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(PerformanceBaseline.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("all long thresholds default to 0")
    void longThresholdsDefaultZero() throws Exception {
      for (var name :
          new String[] {"messagesPerSecond", "p99LatencyMillis", "p95LatencyMillis",
              "p50LatencyMillis", "maxGcPauseMillis"}) {
        var m = PerformanceBaseline.class.getDeclaredMethod(name);
        assertThat(m.getDefaultValue())
            .as("PerformanceBaseline.%s() default", name)
            .isEqualTo(0L);
      }
    }

    @Test
    @DisplayName("maxMemoryMB defaults to 0")
    void maxMemoryMBDefaultZero() throws Exception {
      var method = PerformanceBaseline.class.getDeclaredMethod("maxMemoryMB");
      assertThat(method.getDefaultValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("boolean flags default to false")
    void booleanDefaults() throws Exception {
      for (var name : new String[] {"enableJFR", "reportMetrics"}) {
        var m = PerformanceBaseline.class.getDeclaredMethod(name);
        assertThat(m.getDefaultValue())
            .as("PerformanceBaseline.%s() default", name)
            .isEqualTo(false);
      }
    }
  }

  // ── @ProcessFixture ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("@ProcessFixture")
  class ProcessFixtureAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(ProcessFixture.class);
    }

    @Test
    @DisplayName("targets TYPE, METHOD, and PARAMETER")
    void targetsTypeMethodParameter() {
      var target = ProcessFixture.class.getAnnotation(Target.class);
      assertThat(target).isNotNull();
      assertThat(target.value())
          .contains(ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER);
    }

    @Test
    @DisplayName("value attribute has no default (required)")
    void valueHasNoDefault() throws Exception {
      var method = ProcessFixture.class.getDeclaredMethod("value");
      assertThat(method.getDefaultValue())
          .as("ProcessFixture.value() should have no default")
          .isNull();
    }

    @Test
    @DisplayName("instances defaults to 1")
    void instancesDefaultOne() throws Exception {
      var method = ProcessFixture.class.getDeclaredMethod("instances");
      assertThat(method.getDefaultValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("supervisionStrategy defaults to ONE_FOR_ONE")
    void supervisionStrategyDefault() throws Exception {
      var method = ProcessFixture.class.getDeclaredMethod("supervisionStrategy");
      assertThat(method.getDefaultValue()).isEqualTo("ONE_FOR_ONE");
    }

    @Test
    @DisplayName("autoCleanup defaults to true")
    void autoCleanupDefaultTrue() throws Exception {
      var method = ProcessFixture.class.getDeclaredMethod("autoCleanup");
      assertThat(method.getDefaultValue()).isEqualTo(true);
    }

    @Test
    @DisplayName("captureMessages and registerInRegistry default to false")
    void booleanDefaults() throws Exception {
      assertThat(
              ProcessFixture.class.getDeclaredMethod("captureMessages").getDefaultValue())
          .isEqualTo(false);
      assertThat(
              ProcessFixture.class.getDeclaredMethod("registerInRegistry").getDefaultValue())
          .isEqualTo(false);
    }

    @Test
    @DisplayName("registryName defaults to empty string")
    void registryNameDefault() throws Exception {
      var method = ProcessFixture.class.getDeclaredMethod("registryName");
      assertThat(method.getDefaultValue()).isEqualTo("");
    }
  }

  // ── @IntegrationPattern ───────────────────────────────────────────────────

  @Nested
  @DisplayName("@IntegrationPattern")
  class IntegrationPatternAnnotation {

    @Test
    @DisplayName("has RUNTIME retention")
    void hasRuntimeRetention() {
      assertRuntimeRetention(IntegrationPattern.class);
    }

    @Test
    @DisplayName("targets TYPE and METHOD")
    void targetsTypeAndMethod() {
      assertTargetContains(IntegrationPattern.class, ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("patterns attribute has no default (required)")
    void patternsHasNoDefault() throws Exception {
      var method = IntegrationPattern.class.getDeclaredMethod("patterns");
      assertThat(method.getDefaultValue())
          .as("IntegrationPattern.patterns() should have no default")
          .isNull();
    }

    @Test
    @DisplayName("validateDataIntegrity and validateCausality default to true")
    void trueDefaults() throws Exception {
      assertThat(
              IntegrationPattern.class.getDeclaredMethod("validateDataIntegrity").getDefaultValue())
          .isEqualTo(true);
      assertThat(
              IntegrationPattern.class.getDeclaredMethod("validateCausality").getDefaultValue())
          .isEqualTo(true);
    }

    @Test
    @DisplayName("numeric thresholds default to 0 or -1")
    void numericDefaults() throws Exception {
      assertThat(
              IntegrationPattern.class.getDeclaredMethod("maxChainDepth").getDefaultValue())
          .isEqualTo(0);
      assertThat(
              IntegrationPattern.class.getDeclaredMethod("timeoutSeconds").getDefaultValue())
          .isEqualTo(0L);
      assertThat(
              IntegrationPattern.class.getDeclaredMethod("expectedOutputCount").getDefaultValue())
          .isEqualTo(-1);
    }

    @Test
    @DisplayName("enableFullTracing defaults to false")
    void enableFullTracingDefaultFalse() throws Exception {
      var method = IntegrationPattern.class.getDeclaredMethod("enableFullTracing");
      assertThat(method.getDefaultValue()).isEqualTo(false);
    }
  }
}
