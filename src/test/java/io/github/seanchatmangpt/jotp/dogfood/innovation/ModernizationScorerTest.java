package io.github.seanchatmangpt.jotp.dogfood.innovation;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.CategoryScore;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.CodebaseScore;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.Finding;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.Recommendation;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
@DisplayName("ModernizationScorer")
class ModernizationScorerTest implements WithAssertions {
    private ModernizationScorer scorer;
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        scorer = new ModernizationScorer();
    }
    // ── Sample sources ────────────────────────────────────────────────────────
    private static final String LEGACY_SOURCE =
            """
            package com.example;
            import java.util.Date;
            import java.text.SimpleDateFormat;
            import org.junit.Test;
            import org.junit.Before;
            public class LegacyService {
                private StringBuffer buffer = new StringBuffer();
                private List results;
                private Vector items = new Vector();
                public void process(Object obj) {
                    if (obj instanceof String) {
                        String s = (String) obj;
                        buffer.append(s);
                    }
                    Date now = new Date();
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
                    synchronized (buffer) {
                        buffer.append(fmt.format(now));
                }
                public void runTask() {
                    new Thread(() -> System.out.println("running")).start();
                    Executors.newFixedThreadPool(4);
                @Test
                public void testSomething() {
                    assertEquals("expected", "actual");
            }
            """;
    private static final String MODERN_SOURCE =
            import java.time.LocalDateTime;
            import java.time.Instant;
            public sealed interface Shape permits Shape.Circle, Shape.Rect {
                record Circle(double radius) implements Shape {}
                record Rect(double w, double h) implements Shape {}
            public final class ModernService {
                public String describe(Object obj) {
                    return switch (obj) {
                        case String s -> "string: " + s;
                        case Integer i -> "int: " + i;
                        default -> "unknown";
                    };
                public void process() {
                    var now = Instant.now();
                    var date = LocalDateTime.now();
                    var client = HttpClient.newHttpClient();
                    Thread.ofVirtual().start(() -> System.out.println("virtual"));
                void test() {
                    assertThat(result).isEqualTo("expected");
    private static final String MINIMAL_SOURCE =
            public class Empty {
    private static final String SECURITY_LEGACY_SOURCE =
            import java.security.MessageDigest;
            import javax.crypto.Cipher;
            public class OldCrypto {
                public byte[] hash(byte[] data) throws Exception {
                    var md = MessageDigest.getInstance("MD5");
                    return md.digest(data);
                public byte[] encrypt(byte[] data) throws Exception {
                    var cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
                    return cipher.doFinal(data);
    // ── Tests ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CodebaseScore record")
    class CodebaseScoreValidation {
        @Test
        @DisplayName("rejects score below 0")
        void rejectsScoreBelowZero() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CodebaseScore(-1, List.of(), List.of()));
        }
        @DisplayName("rejects score above 100")
        void rejectsScoreAbove100() {
                    .isThrownBy(() -> new CodebaseScore(101, List.of(), List.of()));
        @DisplayName("creates valid score")
        void createsValidScore() {
            var score = new CodebaseScore(75, List.of(), List.of());
            assertThat(score.overallScore()).isEqualTo(75);
    @DisplayName("Recommendation record")
    class RecommendationValidation {
        @DisplayName("computes ROI correctly")
        void computesRoi() {
            var rec = new Recommendation("title", "desc", 8, 2, "ref");
            assertThat(rec.roi()).isEqualTo(4.0);
        @DisplayName("ROI with zero effort returns impact")
        void roiZeroEffort() {
            var rec = new Recommendation("title", "desc", 5, 0, "ref");
            assertThat(rec.roi()).isEqualTo(5.0);
        @DisplayName("rejects invalid impact score")
        void rejectsInvalidImpact() {
                    .isThrownBy(() -> new Recommendation("t", "d", 11, 2, "r"));
    @DisplayName("analyze()")
    class Analyze {
        @DisplayName("legacy code scores lower than modern code")
        void legacyScoresLowerThanModern() {
            var legacyScore = scorer.analyze(LEGACY_SOURCE);
            var modernScore = scorer.analyze(MODERN_SOURCE);
            assertThat(legacyScore.overallScore()).isLessThan(modernScore.overallScore());
        @DisplayName("legacy code produces recommendations")
        void legacyCodeProducesRecommendations() {
            var result = scorer.analyze(LEGACY_SOURCE);
            assertThat(result.recommendations()).isNotEmpty();
            assertThat(result.recommendations())
                    .allSatisfy(
                            rec -> {
                                assertThat(rec.title()).startsWith("Migrate:");
                                assertThat(rec.description()).isNotBlank();
                                assertThat(rec.templateRef()).isNotBlank();
                            });
        @DisplayName("modern code produces no recommendations")
        void modernCodeProducesNoRecommendations() {
            var result = scorer.analyze(MODERN_SOURCE);
            assertThat(result.recommendations()).isEmpty();
        @DisplayName("overall score is between 0 and 100")
        void overallScoreInRange() {
            assertThat(result.overallScore()).isBetween(0, 100);
        @DisplayName("minimal code gets baseline score around 50")
        void minimalCodeGetsBaselineScore() {
            var result = scorer.analyze(MINIMAL_SOURCE);
            // No modern or legacy patterns -> half of max per category -> ~50
            assertThat(result.overallScore()).isBetween(40, 60);
        @DisplayName("has all five categories")
        void hasFiveCategories() {
            assertThat(result.categories()).hasSize(5);
            assertThat(result.categories())
                    .extracting(CategoryScore::category)
                    .containsExactlyInAnyOrder(
                            "language", "concurrency", "api", "testing", "security");
        @DisplayName("category scores do not exceed their max")
        void categoryScoresDoNotExceedMax() {
                            cat -> {
                                assertThat(cat.score()).isBetween(0, cat.maxScore());
    @DisplayName("legacy pattern detection")
    class LegacyPatternDetection {
        @DisplayName("detects java.util.Date usage")
        void detectsLegacyDate() {
            var apiFindings = findingsForCategory(result, "api");
            assertThat(apiFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("Legacy java.util.Date");
        @DisplayName("detects StringBuffer usage")
        void detectsStringBuffer() {
                    .contains("StringBuffer (synchronized)");
        @DisplayName("detects synchronized blocks")
        void detectsSynchronized() {
            var concurrencyFindings = findingsForCategory(result, "concurrency");
            assertThat(concurrencyFindings)
                    .contains("synchronized block");
        @DisplayName("detects manual Thread creation")
        void detectsManualThread() {
                    .contains("Manual Thread creation");
        @DisplayName("detects JUnit 4 annotations")
        void detectsJunit4() {
            var testFindings = findingsForCategory(result, "testing");
            assertThat(testFindings)
                    .contains("JUnit 4 annotations");
        @DisplayName("detects Vector usage")
        void detectsVector() {
                    .contains("Legacy synchronized collection");
        @DisplayName("detects weak hash algorithms")
        void detectsWeakHash() {
            var result = scorer.analyze(SECURITY_LEGACY_SOURCE);
            var securityFindings = findingsForCategory(result, "security");
            assertThat(securityFindings)
                    .contains("Weak hash algorithm");
        @DisplayName("detects weak cipher algorithms")
        void detectsWeakCipher() {
                    .contains("Weak cipher algorithm");
    @DisplayName("modern pattern detection")
    class ModernPatternDetection {
        @DisplayName("detects record types")
        void detectsRecords() {
            var langFindings = findingsForCategory(result, "language");
            assertThat(langFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("Record type");
        @DisplayName("detects sealed types")
        void detectsSealedTypes() {
                    .contains("Sealed type");
        @DisplayName("detects var usage")
        void detectsVar() {
                    .contains("Local variable type inference (var)");
        @DisplayName("detects virtual threads")
        void detectsVirtualThreads() {
                    .contains("Virtual thread usage");
        @DisplayName("detects java.time API")
        void detectsJavaTime() {
                    .contains("java.time API");
        @DisplayName("detects HttpClient")
        void detectsHttpClient() {
                    .contains("Modern HttpClient");
        @DisplayName("detects AssertJ assertions")
        void detectsAssertJ() {
                    .contains("AssertJ assertions");
    @DisplayName("recommendations")
    class Recommendations {
        @DisplayName("are sorted by ROI descending")
        void sortedByRoiDescending() {
            var rois = result.recommendations().stream().map(Recommendation::roi).toList();
            for (int i = 0; i < rois.size() - 1; i++) {
                assertThat(rois.get(i)).isGreaterThanOrEqualTo(rois.get(i + 1));
        @DisplayName("include template references")
        void includeTemplateReferences() {
                    .allSatisfy(rec -> assertThat(rec.templateRef()).contains("/"));
        @DisplayName("include occurrence counts in description")
        void includeOccurrenceCounts() {
                            rec ->
                                    assertThat(rec.description())
                                            .containsPattern("\\d+ occurrence"));
    @DisplayName("Finding sealed interface")
    class FindingTypes {
        @DisplayName("LegacyUsage carries suggestion")
        void legacyUsageCarriesSuggestion() {
            var finding = new Finding.LegacyUsage("pattern", "line 1: code", "use modern API");
            assertThat(finding.pattern()).isEqualTo("pattern");
            assertThat(finding.suggestion()).isEqualTo("use modern API");
        @DisplayName("ModernUsage carries pattern name")
        void modernUsageCarriesPatternName() {
            var finding = new Finding.ModernUsage("Record type", "line 5: record Foo()");
            assertThat(finding.pattern()).isEqualTo("Record type");
            assertThat(finding.lineHint()).contains("line 5");
        @DisplayName("Finding is sealed with exactly two variants")
        void findingIsSealedWithTwoVariants() {
            assertThat(Finding.class.isSealed()).isTrue();
            assertThat(Finding.class.getPermittedSubclasses()).hasSize(2);
    // ── Enterprise patterns (GoNoGo rules) ───────────────────────────────────
    @DisplayName("Enterprise legacy patterns")
    class EnterprisePatterns {
        @DisplayName("ThreadLocal detected as legacy concurrency pattern")
        void threadLocalIsLegacy() {
            var source =
                    """
                    package com.example;
                    public class RequestContext {
                        private static final ThreadLocal<String> current = new ThreadLocal<>();
                        public static void set(String v) { current.set(v); }
                    """;
            var score = scorer.analyze(source);
            var concurrencyFindings = findingsForCategory(score, "concurrency");
                    .anyMatch(
                            f ->
                                    f instanceof Finding.LegacyUsage lu
                                            && lu.pattern().contains("ThreadLocal"));
        @DisplayName("static AtomicInteger detected as legacy shared state")
        void staticAtomicIntegerIsLegacy() {
                    import java.util.concurrent.atomic.AtomicInteger;
                    public class Counter {
                        private static final AtomicInteger count = new AtomicInteger(0);
                                            && lu.pattern().contains("Atomic"));
        @DisplayName("ScopedValue.newInstance detected as modern concurrency pattern")
        void scopedValueIsModern() {
                        private static final ScopedValue<String> REQUEST_ID =
                            ScopedValue.newInstance();
                                    f instanceof Finding.ModernUsage mu
                                            && mu.pattern().contains("ScopedValue"));
        @DisplayName("ThreadLocal lowers concurrency score vs ScopedValue")
        void threadLocalLowersScoreVsScopedValue() {
            var threadLocalSource =
                    "public class A { static final ThreadLocal<String> t = new ThreadLocal<>(); }";
            var scopedValueSource =
                    "public class B { static final ScopedValue<String> s = ScopedValue.newInstance(); }";
            var threadLocalScore = scorer.analyze(threadLocalSource);
            var scopedValueScore = scorer.analyze(scopedValueSource);
            assertThat(threadLocalScore.overallScore()).isLessThan(scopedValueScore.overallScore());
    // ── Edge Cases (JIDOKA - Stop and Fix) ───────────────────────────────────────
    @DisplayName("Edge cases and error handling")
    class EdgeCases {
        @DisplayName("null source throws NullPointerException")
        void nullSourceThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> scorer.analyze(null))
                    .withMessageContaining("javaSource");
        @DisplayName("empty source returns baseline score")
        void emptySourceReturnsBaseline() {
            var result = scorer.analyze("");
            assertThat(result.overallScore()).isEqualTo(50);
        @DisplayName("blank source returns baseline score")
        void blankSourceReturnsBaseline() {
            var result = scorer.analyze("   \n\n   ");
        @DisplayName("source with only comments returns near-baseline score")
        void onlyCommentsReturnsBaseline() {
                    // Just a comment
                    /* Block comment */
            var result = scorer.analyze(source);
            // Comments-only source gets a near-baseline score (no modern patterns detected)
            assertThat(result.overallScore()).isBetween(45, 55);
    // ── Helpers ───────────────────────────────────────────────────────────────
    private static java.util.List<Finding> findingsForCategory(
            CodebaseScore score, String category) {
        return score.categories().stream()
                .filter(c -> c.category().equals(category))
                .findFirst()
                .orElseThrow()
                .findings();
}
