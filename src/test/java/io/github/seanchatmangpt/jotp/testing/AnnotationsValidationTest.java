package io.github.seanchatmangpt.jotp.testing;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.seanchatmangpt.jotp.ApplicationController;
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
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
@Timeout(10)
@DisplayName("Annotations Validation")
class AnnotationsValidationTest implements WithAssertions {
    // ── Helper ───────────────────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    private void assertRuntimeRetention(Class<?> annotationType) {
        var retention = annotationType.getAnnotation(Retention.class);
        assertThat(retention)
                .as("@Retention present on %s", annotationType.getSimpleName())
                .isNotNull();
        assertThat(retention.value())
                .as("%s retention policy", annotationType.getSimpleName())
                .isEqualTo(RetentionPolicy.RUNTIME);
    private void assertTargetContains(Class<?> annotationType, ElementType... expected) {
        var target = annotationType.getAnnotation(Target.class);
        assertThat(target).as("@Target present on %s", annotationType.getSimpleName()).isNotNull();
        assertThat(target.value())
                .as("%s @Target elements", annotationType.getSimpleName())
                .contains(expected);
    // ── @JotpTest ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("@JotpTest")
    class JotpTestAnnotation {
        @Test
        @DisplayName("has RUNTIME retention")
        void hasRuntimeRetention() {
            assertRuntimeRetention(JotpTest.class);
        }
        @DisplayName("targets TYPE and METHOD")
        void targetsTypeAndMethod() {
            assertTargetContains(JotpTest.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("default primitive is empty string")
        void defaultPrimitive() throws Exception {
            var method = JotpTest.class.getDeclaredMethod("primitive");
            assertThat(method.getDefaultValue()).isEqualTo("");
        @DisplayName("default primitives is empty array")
        void defaultPrimitives() throws Exception {
            var method = JotpTest.class.getDeclaredMethod("primitives");
            assertThat((String[]) method.getDefaultValue()).isEmpty();
        @DisplayName("boolean defaults are false")
        void booleanDefaults() throws Exception {
            for (var name :
                    new String[] {
                        "testCrashRecovery",
                        "testStateIntrospection",
                        "testProcessLinks",
                        "testProcessMonitors",
                        "testProcessRegistry"
                    }) {
                var m = JotpTest.class.getDeclaredMethod(name);
                assertThat(m.getDefaultValue()).as("JotpTest.%s() default", name).isEqualTo(false);
            }
    // ── @PatternTest ──────────────────────────────────────────────────────────
    @DisplayName("@PatternTest")
    class PatternTestAnnotation {
            assertRuntimeRetention(PatternTest.class);
            assertTargetContains(PatternTest.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("async defaults to true")
        void asyncDefaultTrue() throws Exception {
            var method = PatternTest.class.getDeclaredMethod("async");
            assertThat(method.getDefaultValue()).isEqualTo(true);
        @DisplayName("category and description default to empty string")
        void stringDefaults() throws Exception {
            for (var name : new String[] {"category", "description"}) {
                var m = PatternTest.class.getDeclaredMethod(name);
                assertThat(m.getDefaultValue()).as("PatternTest.%s() default", name).isEqualTo("");
        @DisplayName("pattern attribute has empty string default")
        void patternHasEmptyStringDefault() throws Exception {
            var method = PatternTest.class.getDeclaredMethod("pattern");
            assertThat(method.getDefaultValue()).as("PatternTest.pattern() default").isEqualTo("");
    // ── @AsyncPatternTest ─────────────────────────────────────────────────────
    @DisplayName("@AsyncPatternTest")
    class AsyncPatternTestAnnotation {
            assertRuntimeRetention(AsyncPatternTest.class);
            assertTargetContains(AsyncPatternTest.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("is meta-annotated with @PatternTest(async=true)")
        void isMetaAnnotatedWithPatternTest() {
            var meta = AsyncPatternTest.class.getAnnotation(PatternTest.class);
            assertThat(meta).isNotNull();
            assertThat(meta.async()).isTrue();
        @DisplayName("timeoutValue defaults to 5")
        void timeoutValueDefault() throws Exception {
            var method = AsyncPatternTest.class.getDeclaredMethod("timeoutValue");
            assertThat(method.getDefaultValue()).isEqualTo(5L);
        @DisplayName("timeoutUnit defaults to SECONDS")
        void timeoutUnitDefault() throws Exception {
            var method = AsyncPatternTest.class.getDeclaredMethod("timeoutUnit");
            assertThat(method.getDefaultValue()).isEqualTo(TimeUnit.SECONDS);
        @DisplayName("virtualThreadOnly defaults to false")
        void virtualThreadOnlyDefaultFalse() throws Exception {
            var method = AsyncPatternTest.class.getDeclaredMethod("virtualThreadOnly");
            assertThat(method.getDefaultValue()).isEqualTo(false);
        @DisplayName("trackCorrelationIds defaults to true")
        void trackCorrelationIdsDefaultTrue() throws Exception {
            var method = AsyncPatternTest.class.getDeclaredMethod("trackCorrelationIds");
    // ── @CorrelationTest ──────────────────────────────────────────────────────
    @DisplayName("@CorrelationTest")
    class CorrelationTestAnnotation {
            assertRuntimeRetention(CorrelationTest.class);
            assertTargetContains(CorrelationTest.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("autoGenerate defaults to true")
        void autoGenerateDefaultTrue() throws Exception {
            var method = CorrelationTest.class.getDeclaredMethod("autoGenerate");
        @DisplayName("reportGraphs defaults to false")
        void reportGraphsDefaultFalse() throws Exception {
            var method = CorrelationTest.class.getDeclaredMethod("reportGraphs");
        @DisplayName("maxChainDepth defaults to 0")
        void maxChainDepthDefaultZero() throws Exception {
            var method = CorrelationTest.class.getDeclaredMethod("maxChainDepth");
            assertThat(method.getDefaultValue()).isEqualTo(0);
        @DisplayName("validateDistributedTracing defaults to true")
        void validateDistributedTracingDefaultTrue() throws Exception {
            var method = CorrelationTest.class.getDeclaredMethod("validateDistributedTracing");
    // ── @MessageCapture ───────────────────────────────────────────────────────
    @DisplayName("@MessageCapture")
    class MessageCaptureAnnotation {
            assertRuntimeRetention(MessageCapture.class);
            assertTargetContains(MessageCapture.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("captureAll defaults to true")
        void captureAllDefaultTrue() throws Exception {
            var method = MessageCapture.class.getDeclaredMethod("captureAll");
        @DisplayName("maxMessages defaults to 1000")
        void maxMessagesDefault1000() throws Exception {
            var method = MessageCapture.class.getDeclaredMethod("maxMessages");
            assertThat(method.getDefaultValue()).isEqualTo(1000);
        @DisplayName("onlyTypes and excludeTypes default to empty arrays")
        void arrayDefaults() throws Exception {
            for (var name : new String[] {"onlyTypes", "excludeTypes"}) {
                var m = MessageCapture.class.getDeclaredMethod(name);
                assertThat((String[]) m.getDefaultValue())
                        .as("MessageCapture.%s() default", name)
                        .isEmpty();
        @DisplayName("includePayload defaults to true, traceStackTrace to false")
            assertThat(MessageCapture.class.getDeclaredMethod("includePayload").getDefaultValue())
                    .isEqualTo(true);
            assertThat(MessageCapture.class.getDeclaredMethod("traceStackTrace").getDefaultValue())
                    .isEqualTo(false);
    // ── @VirtualThreaded ──────────────────────────────────────────────────────
    @DisplayName("@VirtualThreaded")
    class VirtualThreadedAnnotation {
            assertRuntimeRetention(VirtualThreaded.class);
            assertTargetContains(VirtualThreaded.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("mode defaults to VIRTUAL_ONLY")
        void modeDefaultVirtualOnly() throws Exception {
            var method = VirtualThreaded.class.getDeclaredMethod("mode");
            assertThat(method.getDefaultValue()).isEqualTo(VirtualThreaded.ThreadMode.VIRTUAL_ONLY);
        @DisplayName("ThreadMode enum has VIRTUAL_ONLY, PLATFORM_ONLY, BOTH")
        void threadModeEnumValues() {
            var values = VirtualThreaded.ThreadMode.values();
            assertThat(values)
                    .extracting(Enum::name)
                    .containsExactlyInAnyOrder("VIRTUAL_ONLY", "PLATFORM_ONLY", "BOTH");
        @DisplayName("numeric defaults are zero, booleans are false")
        void numericAndBooleanDefaults() throws Exception {
            assertThat(
                            VirtualThreaded.class
                                    .getDeclaredMethod("maxPinningMillis")
                                    .getDefaultValue())
                    .isEqualTo(0L);
                                    .getDeclaredMethod("expectedThreadCount")
                    .isEqualTo(0);
            assertThat(VirtualThreaded.class.getDeclaredMethod("noPinning").getDefaultValue())
                                    .getDeclaredMethod("validateContextPropagation")
    // ── @PerformanceBaseline ──────────────────────────────────────────────────
    @DisplayName("@PerformanceBaseline")
    class PerformanceBaselineAnnotation {
            assertRuntimeRetention(PerformanceBaseline.class);
            assertTargetContains(PerformanceBaseline.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("all long thresholds default to 0")
        void longThresholdsDefaultZero() throws Exception {
                        "messagesPerSecond",
                        "p99LatencyMillis",
                        "p95LatencyMillis",
                        "p50LatencyMillis",
                        "maxGcPauseMillis"
                var m = PerformanceBaseline.class.getDeclaredMethod(name);
                assertThat(m.getDefaultValue())
                        .as("PerformanceBaseline.%s() default", name)
                        .isEqualTo(0L);
        @DisplayName("maxMemoryMB defaults to 0")
        void maxMemoryMBDefaultZero() throws Exception {
            var method = PerformanceBaseline.class.getDeclaredMethod("maxMemoryMB");
        @DisplayName("boolean flags default to false")
            for (var name : new String[] {"enableJFR", "reportMetrics"}) {
                        .isEqualTo(false);
    // ── @ProcessFixture ───────────────────────────────────────────────────────
    @DisplayName("@ProcessFixture")
    class ProcessFixtureAnnotation {
            assertRuntimeRetention(ProcessFixture.class);
        @DisplayName("targets TYPE, METHOD, and PARAMETER")
        void targetsTypeMethodParameter() {
            var target = ProcessFixture.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value())
                    .contains(ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER);
        @DisplayName("value attribute has no default (required)")
        void valueHasNoDefault() throws Exception {
            var method = ProcessFixture.class.getDeclaredMethod("value");
            assertThat(method.getDefaultValue())
                    .as("ProcessFixture.value() should have no default")
                    .isNull();
        @DisplayName("instances defaults to 1")
        void instancesDefaultOne() throws Exception {
            var method = ProcessFixture.class.getDeclaredMethod("instances");
            assertThat(method.getDefaultValue()).isEqualTo(1);
        @DisplayName("supervisionStrategy defaults to ONE_FOR_ONE")
        void supervisionStrategyDefault() throws Exception {
            var method = ProcessFixture.class.getDeclaredMethod("supervisionStrategy");
            assertThat(method.getDefaultValue()).isEqualTo("ONE_FOR_ONE");
        @DisplayName("autoCleanup defaults to true")
        void autoCleanupDefaultTrue() throws Exception {
            var method = ProcessFixture.class.getDeclaredMethod("autoCleanup");
        @DisplayName("captureMessages and registerInRegistry default to false")
            assertThat(ProcessFixture.class.getDeclaredMethod("captureMessages").getDefaultValue())
                            ProcessFixture.class
                                    .getDeclaredMethod("registerInRegistry")
        @DisplayName("registryName defaults to empty string")
        void registryNameDefault() throws Exception {
            var method = ProcessFixture.class.getDeclaredMethod("registryName");
    // ── @IntegrationPattern ───────────────────────────────────────────────────
    @DisplayName("@IntegrationPattern")
    class IntegrationPatternAnnotation {
            assertRuntimeRetention(IntegrationPattern.class);
            assertTargetContains(IntegrationPattern.class, ElementType.TYPE, ElementType.METHOD);
        @DisplayName("patterns attribute has no default (required)")
        void patternsHasNoDefault() throws Exception {
            var method = IntegrationPattern.class.getDeclaredMethod("patterns");
                    .as("IntegrationPattern.patterns() should have no default")
        @DisplayName("validateDataIntegrity and validateCausality default to true")
        void trueDefaults() throws Exception {
                            IntegrationPattern.class
                                    .getDeclaredMethod("validateDataIntegrity")
                                    .getDeclaredMethod("validateCausality")
        @DisplayName("numeric thresholds default to 0 or -1")
        void numericDefaults() throws Exception {
                                    .getDeclaredMethod("maxChainDepth")
                                    .getDeclaredMethod("timeoutSeconds")
                                    .getDeclaredMethod("expectedOutputCount")
                    .isEqualTo(-1);
        @DisplayName("enableFullTracing defaults to false")
        void enableFullTracingDefaultFalse() throws Exception {
            var method = IntegrationPattern.class.getDeclaredMethod("enableFullTracing");
}
