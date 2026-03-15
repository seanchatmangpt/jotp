package io.github.seanchatmangpt.jotp.dogfood.innovation;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.Set;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/** Dogfood: tests for OntologyMigrationEngine — ontology-driven migration analysis. */
@DisplayName("OntologyMigrationEngine")
class OntologyMigrationEngineTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("allRules returns all 15 built-in ontology rules")
    void allRulesReturnsBuiltInRules() {
        var rules = OntologyMigrationEngine.allRules();
        assertThat(rules).hasSize(15);
        assertThat(rules)
                .allSatisfy(
                        r -> {
                            assertThat(r.id()).isNotBlank();
                            assertThat(r.label()).isNotBlank();
                            assertThat(r.priority()).isBetween(1, 10);
                        });
    @DisplayName("analyze detects new Thread() as virtual thread migration opportunity")
    void analyzeDetectsNewThread() {
        var source =
                """
                public class LegacyService {
                    public void process() {
                        new Thread(() -> System.out.println("work")).start();
                    }
                }
                """;
        var result = OntologyMigrationEngine.analyze("LegacyService.java", source);
        assertThat(result.plans())
                .anyMatch(
                        p ->
                                p
                                        instanceof
                                        OntologyMigrationEngine.MigrationPlan
                                                .VirtualThreadMigration);
    @DisplayName("analyze detects Executors.newFixedThreadPool as thread pool migration")
    void analyzeDetectsThreadPool() {
                import java.util.concurrent.Executors;
                public class PoolService {
                    var pool = Executors.newFixedThreadPool(10);
        var result = OntologyMigrationEngine.analyze("PoolService.java", source);
    @DisplayName("analyze detects legacy Date/Calendar usage")
    void analyzeDetectsLegacyDate() {
                import java.util.Date;
                import java.util.Calendar;
                public class DateService {
                    Date now = new Date();
                    Calendar cal = Calendar.getInstance();
        var result = OntologyMigrationEngine.analyze("DateService.java", source);
                        p -> p instanceof OntologyMigrationEngine.MigrationPlan.JavaTimeMigration);
    @DisplayName("analyze detects JUnit 4 imports as migration opportunity")
    void analyzeDetectsJUnit4() {
                import org.junit.Test;
                import org.junit.Before;
                public class OldTest {
                    @Before public void setUp() {}
                    @Test public void testSomething() {}
        var result = OntologyMigrationEngine.analyze("OldTest.java", source);
                                                instanceof
                                                OntologyMigrationEngine.MigrationPlan
                                                                .GenericMigration
                                                        gm
                                        && gm.rule().id().equals("JUnit4ToJUnit5"));
    @DisplayName("analyze returns empty plans for modern code")
    void analyzeReturnsEmptyForModernCode() {
                package io.github.seanchatmangpt.jotp;
                public record ModernEntity(String name, int value) {}
        var result = OntologyMigrationEngine.analyze("ModernEntity.java", source);
        assertThat(result.plans()).isEmpty();
    @DisplayName("analyze with category filter only returns matching categories")
    void analyzeWithCategoryFilter() {
                public class Mixed {
                    Date d = new Date();
                    new Thread(() -> {}).start();
        var result =
                OntologyMigrationEngine.analyze(
                        "Mixed.java", source, Set.of(OntologyMigrationEngine.Category.CONCURRENCY));
                                assertThat(p.rule().category())
                                        .isEqualTo(OntologyMigrationEngine.Category.CONCURRENCY));
    @DisplayName("safeUpgrades excludes breaking changes")
    void safeUpgradesExcludesBreaking() {
                public class Service {
        var result = OntologyMigrationEngine.analyze("Service.java", source);
        assertThat(result.safeUpgrades())
                .allSatisfy(p -> assertThat(p.rule().breaking()).isFalse());
    @DisplayName("byPriority sorts plans by rule priority ascending")
    void byPrioritySortsPlans() {
                import java.io.File;
                public class Multi {
                    File f = new File("test");
        var result = OntologyMigrationEngine.analyze("Multi.java", source);
        var sorted = result.byPriority();
        if (sorted.size() > 1) {
            for (int i = 1; i < sorted.size(); i++) {
                assertThat(sorted.get(i).rule().priority())
                        .isGreaterThanOrEqualTo(sorted.get(i - 1).rule().priority());
            }
        }
    @DisplayName("describePlan returns human-readable description for each plan type")
    void describePlanReturnsDescription() {
                public class Legacy {
        var result = OntologyMigrationEngine.analyze("Legacy.java", source);
        for (var plan : result.plans()) {
            var description = OntologyMigrationEngine.describePlan(plan);
            assertThat(description).isNotBlank();
    @DisplayName("summary produces non-empty report when migrations found")
    void summaryProducesReport() {
        assertThat(result.summary()).contains("migration(s) found");
    @DisplayName("MigrationRule rejects invalid priority")
    void migrationRuleRejectsInvalidPriority() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new OntologyMigrationEngine.MigrationRule(
                                        "test",
                                        "Test",
                                        "pattern",
                                        "template",
                                        OntologyMigrationEngine.Category.LANGUAGE,
                                        0,
                                        false))
                .withMessageContaining("priority");
    @DisplayName("SourceSignal rejects invalid line number")
    void sourceSignalRejectsInvalidLine() {
                .isThrownBy(() -> new OntologyMigrationEngine.SourceSignal("text", 0))
                .withMessageContaining("lineNumber");
    // ── OTP migration rules ───────────────────────────────────────────────────
    @DisplayName("static Map detected → StaticStateToProc plan")
    void staticMapDetected() {
                package com.example;
                import java.util.Map;
                import java.util.HashMap;
                public class UserCache {
                    private static final Map<String, Object> cache = new HashMap<>();
        var result = OntologyMigrationEngine.analyze("UserCache.java", source);
                                p.rule().id().equals("StaticStateToProc")
                                        && p
                                                        .GenericMigration);
    @DisplayName("ThreadLocal detected → ThreadLocalToScopedValue plan")
    void threadLocalDetected() {
                public class Context {
                    private static final ThreadLocal<String> current = new ThreadLocal<>();
        var result = OntologyMigrationEngine.analyze("Context.java", source);
        assertThat(result.plans()).anyMatch(p -> p.rule().id().equals("ThreadLocalToScopedValue"));
    @DisplayName("swallowed catch detected → CatchToCrashRecovery plan")
    void swallowedCatchDetected() {
                    boolean save(Object o) {
                        try { repo.save(o); return true; }
                        catch (Exception e) { return false; }
        assertThat(result.plans()).anyMatch(p -> p.rule().id().equals("CatchToCrashRecovery"));
    @DisplayName("allRules() includes the 3 new OTP rules")
    void allRulesIncludesNewOtpRules() {
        var ids =
                OntologyMigrationEngine.allRules().stream()
                        .map(OntologyMigrationEngine.MigrationRule::id)
                        .toList();
        assertThat(ids)
                .contains("StaticStateToProc", "ThreadLocalToScopedValue", "CatchToCrashRecovery");
    @DisplayName("OTP rules have CONCURRENCY or ERROR_HANDLING category")
    void otpRuleCategories() {
        var otpRules =
                        .filter(
                                r ->
                                        r.id().startsWith("Static")
                                                || r.id().startsWith("Thread")
                                                || r.id().startsWith("Catch"))
        assertThat(otpRules)
                .allMatch(
                        r ->
                                r.category() == OntologyMigrationEngine.Category.CONCURRENCY
                                        || r.category()
                                                == OntologyMigrationEngine.Category.ERROR_HANDLING);
}
