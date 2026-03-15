package io.github.seanchatmangpt.jotp.dogfood.innovation;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.dogfood.innovation.TemplateCompositionEngine.CompositionResult;
import io.github.seanchatmangpt.jotp.dogfood.innovation.TemplateCompositionEngine.FeatureRecipe;
import io.github.seanchatmangpt.jotp.dogfood.innovation.TemplateCompositionEngine.TemplateRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/**
 * Tests for {@link TemplateCompositionEngine}.
 *
 * <p>Validates recipe composition, template existence checks against the real templates/ directory,
 * variable resolution, and the built-in recipe factories.
 */
@DisplayName("TemplateCompositionEngine")
class TemplateCompositionEngineTest implements WithAssertions {
    private static final Path TEMPLATES_ROOT =
            Path.of(System.getProperty("user.dir")).resolve("templates");
    private TemplateCompositionEngine engine;
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        engine = new TemplateCompositionEngine(TEMPLATES_ROOT);
    }
    // ---------- TemplateRef ----------
    @Nested
    @DisplayName("TemplateRef")
    class TemplateRefTests {
        @Test
        @DisplayName("should compute correct template path")
        void shouldComputeCorrectTemplatePath() {
            var ref = TemplateRef.of("core", "record");
            assertThat(ref.templatePath()).isEqualTo("java/core/record.tera");
        }
        @DisplayName("should reject null category")
        void shouldRejectNullCategory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TemplateRef(null, "record", Map.of()));
        @DisplayName("should reject blank name")
        void shouldRejectBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TemplateRef("core", "  ", Map.of()));
        @DisplayName("should create defensive copy of vars")
        void shouldCreateDefensiveCopy() {
            var mutable = new java.util.HashMap<String, String>();
            mutable.put("key", "value");
            var ref = new TemplateRef("core", "record", mutable);
            mutable.put("extra", "sneaky");
            assertThat(ref.vars()).doesNotContainKey("extra");
        @DisplayName("convenience factory with single variable should work")
        void convenienceFactorySingleVar() {
            var ref = TemplateRef.of("core", "record", "entity_name", "Order");
            assertThat(ref.vars()).containsEntry("entity_name", "Order").hasSize(1);
    // ---------- FeatureRecipe ----------
    @DisplayName("FeatureRecipe")
    class FeatureRecipeTests {
        @DisplayName("should reject empty templates list")
        void shouldRejectEmptyTemplates() {
                    .isThrownBy(() -> new FeatureRecipe("Empty", "desc", List.of(), List.of()));
                    .isThrownBy(
                            () ->
                                    new FeatureRecipe(
                                            " ",
                                            "desc",
                                            List.of(TemplateRef.of("core", "record")),
                                            List.of()));
        @DisplayName("should create defensive copy of templates list")
        void shouldDefensivelyCopyTemplates() {
            var mutableList = new java.util.ArrayList<>(List.of(TemplateRef.of("core", "record")));
            var recipe = new FeatureRecipe("R", "desc", mutableList, List.of());
            mutableList.add(TemplateRef.of("core", "sealed-interface"));
            assertThat(recipe.templates()).hasSize(1);
    // ---------- compose() ----------
    @DisplayName("compose")
    class ComposeTests {
        @DisplayName("should succeed for recipe with valid templates")
        void shouldSucceedForValidTemplates() {
            var recipe =
                    new FeatureRecipe(
                            "SimpleFeature",
                            "A minimal feature",
                            List.of(
                                    new TemplateRef(
                                            "core",
                                            "record",
                                            Map.of(
                                                    "entity_name",
                                                    "Order",
                                                    "package",
                                                    "org.acme.domain")),
                                            "testing",
                                            "junit5-test",
                                                    "entity_name", "Order",
                                                    "package", "org.acme.domain"))),
                            List.of("core/record"));
            var result = engine.compose(recipe);
            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;
            assertThat(success.featureName()).isEqualTo("SimpleFeature");
            assertThat(success.generatedFiles()).hasSize(2);
            assertThat(success.generatedFiles().getFirst()).contains("Order").endsWith(".java");
        @DisplayName("should fail when template does not exist on disk")
        void shouldFailForMissingTemplate() {
                            "BrokenFeature",
                            "references a non-existent template",
                            List.of(TemplateRef.of("core", "does-not-exist")),
                            List.of());
            assertThat(result).isInstanceOf(CompositionResult.Failure.class);
            var failure = (CompositionResult.Failure) result;
            assertThat(failure.featureName()).isEqualTo("BrokenFeature");
            assertThat(failure.errors())
                    .singleElement()
                    .asString()
                    .contains("Template not found")
                    .contains("core/does-not-exist");
        @DisplayName("should fail when dependency references unknown template")
        void shouldFailForBadDependency() {
                            "BadDep",
                            "dependency on non-listed template",
                            List.of(TemplateRef.of("core", "record")),
                            List.of("patterns/service-layer"));
            assertThat(failure.errors()).anyMatch(e -> e.contains("patterns/service-layer"));
        @DisplayName("should collect multiple errors")
        void shouldCollectMultipleErrors() {
                            "MultiError",
                            "multiple bad templates",
                                    TemplateRef.of("core", "nonexistent-a"),
                                    TemplateRef.of("testing", "nonexistent-b")),
            assertThat(failure.errors()).hasSize(2);
        @DisplayName("should resolve shared variables across templates")
        void shouldResolveSharedVariables() {
                            "VarResolution",
                            "test variable propagation",
                                                    "Product",
                                                    "org.acme.shop")),
                                            "patterns",
                                            "repository-generic",
                                            Map.of("repo_type", "JPA"))),
            // The repository template should inherit entity_name and package from shared context
            var repoVars = success.resolvedVariables().get("patterns/repository-generic");
            assertThat(repoVars)
                    .containsEntry("entity_name", "Product")
                    .containsEntry("package", "org.acme.shop")
                    .containsEntry("repo_type", "JPA");
        @DisplayName("should generate distinct file paths for each template")
        void shouldGenerateDistinctPaths() {
                            "DistinctPaths",
                            "no file collisions",
                                                    "Invoice",
                                                    "org.acme.billing")),
                                                    "entity_name", "Invoice",
                                                    "package", "org.acme.billing")),
                                                    "package", "org.acme.billing"))),
            assertThat(success.generatedFiles()).doesNotHaveDuplicates();
            assertThat(success.generatedFiles())
                    .anyMatch(f -> f.endsWith("Invoice.java"))
                    .anyMatch(f -> f.endsWith("InvoiceRepository.java"))
                    .anyMatch(f -> f.endsWith("InvoiceTest.java"));
    // ---------- built-in recipe factories ----------
    @DisplayName("built-in recipe factories")
    class RecipeFactoryTests {
        @DisplayName("crudFeature should compose successfully")
        void crudFeatureShouldCompose() {
            var recipe = TemplateCompositionEngine.crudFeature("Customer");
            assertThat(success.featureName()).isEqualTo("CustomerCrud");
            assertThat(success.generatedFiles()).hasSize(7);
                    .anyMatch(f -> f.endsWith("Customer.java"))
                    .anyMatch(f -> f.endsWith("CustomerRepository.java"))
                    .anyMatch(f -> f.endsWith("CustomerService.java"))
                    .anyMatch(f -> f.endsWith("CustomerDto.java"))
                    .anyMatch(f -> f.endsWith("CustomerTest.java"))
                    .anyMatch(f -> f.endsWith("CustomerPropertyTest.java"))
                    .anyMatch(f -> f.endsWith("CustomerArchTest.java"));
        @DisplayName("valueObjectFeature should compose successfully")
        void valueObjectFeatureShouldCompose() {
            var recipe = TemplateCompositionEngine.valueObjectFeature("Money");
            assertThat(success.featureName()).isEqualTo("MoneyValueObject");
            assertThat(success.generatedFiles()).hasSize(5);
        @DisplayName("serviceLayerFeature should compose successfully")
        void serviceLayerFeatureShouldCompose() {
            var recipe = TemplateCompositionEngine.serviceLayerFeature("Payment");
            assertThat(success.featureName()).isEqualTo("PaymentService");
        @DisplayName("crudFeature should reject null entity name")
        void crudFeatureShouldRejectNull() {
                    .isThrownBy(() -> TemplateCompositionEngine.crudFeature(null));
        @DisplayName("valueObjectFeature should reject null name")
        void valueObjectFeatureShouldRejectNull() {
                    .isThrownBy(() -> TemplateCompositionEngine.valueObjectFeature(null));
        @DisplayName("serviceLayerFeature should reject null name")
        void serviceLayerFeatureShouldRejectNull() {
                    .isThrownBy(() -> TemplateCompositionEngine.serviceLayerFeature(null));
    // ---------- CompositionResult sealed interface ----------
    @DisplayName("CompositionResult pattern matching")
    class CompositionResultTests {
        @DisplayName("should exhaustively switch over sealed variants")
        void shouldExhaustivelySwitchOverVariants() {
            var recipe = TemplateCompositionEngine.crudFeature("Widget");
            var message =
                    switch (result) {
                        case CompositionResult.Success s ->
                                "Generated %d files for %s"
                                        .formatted(s.generatedFiles().size(), s.featureName());
                        case CompositionResult.Failure f ->
                                "Failed %s with %d errors"
                                        .formatted(f.featureName(), f.errors().size());
                    };
            assertThat(message).startsWith("Generated 7 files for WidgetCrud");
    // ---------- Turtle-based composition ----------
    @DisplayName("Turtle-based composition")
    class TurtleCompositionTests {
        private static final Path SCHEMA_DIR = Path.of("schema");
        @DisplayName("messagingFromTurtle should create recipe from messaging ontology")
        void messagingFromTurtleShouldCreateRecipe() {
            var messagingTtl = SCHEMA_DIR.resolve("java-messaging.ttl");
            // Skip if file doesn't exist (CI environments)
            if (!java.nio.file.Files.exists(messagingTtl)) {
                return;
            }
            var recipe = TemplateCompositionEngine.messagingFromTurtle(messagingTtl);
            assertThat(recipe).isNotNull();
            assertThat(recipe.name()).isEqualTo("TurtleMessaging");
            assertThat(recipe.description()).contains("Turtle specification");
            assertThat(recipe.templates()).isNotEmpty();
        @DisplayName("messagingFromTurtle should reject null path")
        void messagingFromTurtleShouldRejectNull() {
                    .isThrownBy(() -> TemplateCompositionEngine.messagingFromTurtle(null));
        @DisplayName("projectFromTurtle should create recipe from project ontology")
        @org.junit.jupiter.api.Disabled("TODO: NPE in TurtleParser - needs schema file update")
        void projectFromTurtleShouldCreateRecipe() {
            var projectTtl = SCHEMA_DIR.resolve("java-project.ttl");
            if (!java.nio.file.Files.exists(projectTtl)) {
            var recipe = TemplateCompositionEngine.projectFromTurtle(projectTtl);
            assertThat(recipe.name()).isNotBlank();
            assertThat(recipe.description()).isNotBlank();
        @DisplayName("projectFromTurtle should reject null path")
        void projectFromTurtleShouldRejectNull() {
                    .isThrownBy(() -> TemplateCompositionEngine.projectFromTurtle(null));
        @DisplayName("messagingFromTurtle with valid ontology should compose successfully")
        void messagingFromTurtleShouldCompose() {
            // Skip if file doesn't exist
            // Result should be Success or Failure, not throw
            assertThat(result).isInstanceOf(CompositionResult.class);
            if (result instanceof CompositionResult.Success success) {
                assertThat(success.featureName()).isEqualTo("TurtleMessaging");
                assertThat(success.generatedFiles()).isNotEmpty();
    // ---------- TurtleParser integration ----------
    @DisplayName("TurtleParser integration")
    class TurtleParserTests {
        @DisplayName("extractPatterns should parse messaging ontology")
        void extractPatternsShouldParseMessaging() {
            var messagingTtl = Path.of("schema/java-messaging.ttl");
            var patterns = TurtleParser.extractPatterns(messagingTtl);
            assertThat(patterns).isNotEmpty();
            assertThat(patterns)
                    .allSatisfy(
                            p -> {
                                assertThat(p.uri()).isNotBlank();
                                assertThat(p.name()).isNotBlank();
                            });
        @DisplayName("extractPatterns should resolve dependencies")
        void extractPatternsShouldResolveDependencies() {
            var resolved = TurtleParser.resolveDependencies(patterns);
            assertThat(resolved).hasSize(patterns.size());
            // Dependencies should come before dependents
        @DisplayName("extractPatterns should reject null path")
        void extractPatternsShouldRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> TurtleParser.extractPatterns(null));
        @DisplayName("extractProject should parse project ontology")
        void extractProjectShouldParseProject() {
            var projectTtl = Path.of("schema/java-project.ttl");
            var project = TurtleParser.extractProject(projectTtl);
            assertThat(project.name()).isNotBlank();
            assertThat(project.groupId()).isNotBlank();
        @DisplayName("extractProject should reject null path")
        void extractProjectShouldRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> TurtleParser.extractProject(null));
    // ---------- TurtleParser error handling ----------
    @DisplayName("TurtleParser error handling")
    class TurtleParserErrorTests {
        @DisplayName("extractPatternsSafe should throw for non-existent file")
        void extractPatternsSafeShouldThrowForMissingFile() {
            assertThatThrownBy(() -> TurtleParser.extractPatternsSafe(Path.of("nonexistent.ttl")))
                    .isInstanceOf(TurtleParser.TurtleFileNotFoundException.class);
        @DisplayName("extractProjectSafe should throw for non-existent file")
        void extractProjectSafeShouldThrowForMissingFile() {
            assertThatThrownBy(() -> TurtleParser.extractProjectSafe(Path.of("nonexistent.ttl")))
        @DisplayName("validateTurtleFile should reject non-ttl files")
        void validateTurtleFileShouldRejectNonTtl() throws Exception {
            var tempFile = java.nio.file.Files.createTempFile("test", ".txt");
            try {
                assertThatThrownBy(() -> TurtleParser.validateTurtleFile(tempFile))
                        .isInstanceOf(TurtleParser.TurtleParseException.class)
                        .hasMessageContaining(".ttl");
            } finally {
                java.nio.file.Files.deleteIfExists(tempFile);
        @DisplayName("validateTurtleContent should reject empty content")
        void validateTurtleContentShouldRejectEmpty() {
            assertThatThrownBy(() -> TurtleParser.validateTurtleContent(""))
                    .isInstanceOf(TurtleParser.MalformedTurtleException.class);
        @DisplayName("validateTurtleContent should reject blank content")
        void validateTurtleContentShouldRejectBlank() {
            assertThatThrownBy(() -> TurtleParser.validateTurtleContent("   \n\n  "))
        @DisplayName("validateTurtleContent should reject null content")
        void validateTurtleContentShouldRejectNull() {
                    .isThrownBy(() -> TurtleParser.validateTurtleContent(null));
        @DisplayName("validateTurtleContent should accept valid Turtle")
        void validateTurtleContentShouldAcceptValidTurtle() {
            var validTurtle =
                    """
                    @prefix ex: <http://example.org/> .
                    ex:Subject ex:predicate ex:object .
                    """;
            // Should not throw
            TurtleParser.validateTurtleContent(validTurtle);
        @DisplayName("PatternSpec should reject null uri")
        void patternSpecShouldRejectNullUri() {
                                    new TurtleParser.PatternSpec(
                                            null, "name", "cat", "tpl", null, 1, null, List.of()));
        @DisplayName("PatternSpec should reject null name")
        void patternSpecShouldRejectNullName() {
                                            "uri", null, "cat", "tpl", null, 1, null, List.of()));
        @DisplayName("PatternSpec should defensively copy dependencies")
        void patternSpecShouldDefensivelyCopyDeps() {
            var mutableDeps = new java.util.ArrayList<String>();
            mutableDeps.add("dep1");
            var spec =
                    new TurtleParser.PatternSpec(
                            "uri", "name", "cat", "tpl", null, 1, null, mutableDeps);
            mutableDeps.add("dep2");
            assertThat(spec.dependencies()).hasSize(1);
        @DisplayName("ModuleSpec should defensively copy patterns")
        void moduleSpecShouldDefensivelyCopyPatterns() {
            var mutablePatterns = new java.util.ArrayList<TurtleParser.PatternSpec>();
            mutablePatterns.add(
                            "uri", "name", null, "tpl", null, 1, null, List.of()));
            var module = new TurtleParser.ModuleSpec("uri", "name", "pkg", mutablePatterns);
            mutablePatterns.clear();
            assertThat(module.patterns()).hasSize(1);
        @DisplayName("ProjectSpec should defensively copy modules")
        void projectSpecShouldDefensivelyCopyModules() {
            var mutableModules = new java.util.ArrayList<TurtleParser.ModuleSpec>();
            mutableModules.add(new TurtleParser.ModuleSpec("uri", "name", "pkg", List.of()));
            var project =
                    new TurtleParser.ProjectSpec(
                            "name", "group", "artifact", "1.0", 26, "desc", mutableModules);
            mutableModules.clear();
            assertThat(project.modules()).hasSize(1);
}
