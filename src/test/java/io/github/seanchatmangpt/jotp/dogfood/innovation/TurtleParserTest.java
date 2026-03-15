package io.github.seanchatmangpt.jotp.dogfood.innovation;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/**
 * Tests for {@link TurtleParser} — Turtle/RDF parser and SPARQL query executor.
 *
 * <p>Validates pattern extraction, dependency resolution, error handling, and project structure
 * parsing.
 */
@DisplayName("TurtleParser")
class TurtleParserTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    private static final Path SCHEMA_DIR = Path.of("schema");
    // ── PatternSpec Tests ─────────────────────────────────────────────────────
    @Nested
    @DisplayName("PatternSpec")
    class PatternSpecTests {
        @Test
        @DisplayName("rejects null uri")
        void rejectsNullUri() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new TurtleParser.PatternSpec(
                                            null, "name", "cat", "tpl", null, 1, null, List.of()));
        }
        @DisplayName("rejects null name")
        void rejectsNullName() {
                                            "uri", null, "cat", "tpl", null, 1, null, List.of()));
        @DisplayName("defensively copies dependencies")
        void defensivelyCopiesDependencies() {
            var mutableDeps = new java.util.ArrayList<String>();
            mutableDeps.add("dep1");
            var spec =
                    new TurtleParser.PatternSpec(
                            "uri", "name", "cat", "tpl", null, 1, null, mutableDeps);
            mutableDeps.add("dep2");
            assertThat(spec.dependencies()).hasSize(1);
        @DisplayName("accepts valid pattern spec")
        void acceptsValidSpec() {
                            "eip:MessageBus",
                            "Message Bus",
                            "foundation",
                            "messaging/message-bus",
                            "Proc",
                            2,
                            "Central message routing",
                            List.of("eip:Channel"));
            assertThat(spec.uri()).isEqualTo("eip:MessageBus");
            assertThat(spec.name()).isEqualTo("Message Bus");
            assertThat(spec.category()).isEqualTo("foundation");
            assertThat(spec.template()).isEqualTo("messaging/message-bus");
            assertThat(spec.primitive()).isEqualTo("Proc");
            assertThat(spec.complexity()).isEqualTo(2);
            assertThat(spec.intent()).isEqualTo("Central message routing");
            assertThat(spec.dependencies()).containsExactly("eip:Channel");
    // ── ModuleSpec Tests ────────────────────────────────────────────────────
    @DisplayName("ModuleSpec")
    class ModuleSpecTests {
                    .isThrownBy(() -> new TurtleParser.ModuleSpec(null, "name", "pkg", List.of()));
                    .isThrownBy(() -> new TurtleParser.ModuleSpec("uri", null, "pkg", List.of()));
        @DisplayName("defensively copies patterns")
        void defensivelyCopiesPatterns() {
            var mutablePatterns = new java.util.ArrayList<TurtleParser.PatternSpec>();
            mutablePatterns.add(
                            "uri", "name", null, "tpl", null, 1, null, List.of()));
            var module = new TurtleParser.ModuleSpec("uri", "name", "pkg", mutablePatterns);
            mutablePatterns.clear();
            assertThat(module.patterns()).hasSize(1);
    // ── ProjectSpec Tests ────────────────────────────────────────────────────
    @DisplayName("ProjectSpec")
    class ProjectSpecTests {
                                    new TurtleParser.ProjectSpec(
                                            null,
                                            "group",
                                            "artifact",
                                            "1.0",
                                            26,
                                            "desc",
                                            List.of()));
        @DisplayName("defensively copies modules")
        void defensivelyCopiesModules() {
            var mutableModules = new java.util.ArrayList<TurtleParser.ModuleSpec>();
            mutableModules.add(new TurtleParser.ModuleSpec("uri", "name", "pkg", List.of()));
            var project =
                    new TurtleParser.ProjectSpec(
                            "name", "group", "artifact", "1.0", 26, "desc", mutableModules);
            mutableModules.clear();
            assertThat(project.modules()).hasSize(1);
        @DisplayName("accepts valid project spec")
            var module = new TurtleParser.ModuleSpec("mod1", "Core", "org.acme.core", List.of());
                            "TestProject",
                            "org.acme",
                            "test-project",
                            "1.0.0",
                            26,
                            "A test project",
                            List.of(module));
            assertThat(project.name()).isEqualTo("TestProject");
            assertThat(project.groupId()).isEqualTo("org.acme");
            assertThat(project.artifactId()).isEqualTo("test-project");
            assertThat(project.version()).isEqualTo("1.0.0");
            assertThat(project.javaVersion()).isEqualTo(26);
            assertThat(project.description()).isEqualTo("A test project");
    // ── Error Handling Tests ─────────────────────────────────────────────────
    @DisplayName("Error Handling")
    class ErrorHandlingTests {
        @DisplayName("TurtleFileNotFoundException for missing file")
        void fileNotFound() {
            assertThatThrownBy(() -> TurtleParser.extractPatterns(Path.of("nonexistent.ttl")))
                    .isInstanceOf(TurtleParser.TurtleFileNotFoundException.class)
                    .hasMessageContaining("not found");
        @DisplayName("extractPatternsSafe throws for missing file")
        void extractPatternsSafeThrowsForMissingFile() {
            assertThatThrownBy(() -> TurtleParser.extractPatternsSafe(Path.of("nonexistent.ttl")))
                    .isInstanceOf(TurtleParser.TurtleFileNotFoundException.class);
        @DisplayName("extractProjectSafe throws for missing file")
        void extractProjectSafeThrowsForMissingFile() {
            assertThatThrownBy(() -> TurtleParser.extractProjectSafe(Path.of("nonexistent.ttl")))
        @DisplayName("validateTurtleFile rejects null")
        void validateFileRejectsNull() {
                    .isThrownBy(() -> TurtleParser.validateTurtleFile(null));
        @DisplayName("validateTurtleFile rejects non-ttl file")
        void validateFileRejectsNonTtl(@TempDir Path tempDir) throws Exception {
            var txtFile = tempDir.resolve("test.txt");
            Files.writeString(txtFile, "content");
            assertThatThrownBy(() -> TurtleParser.validateTurtleFile(txtFile))
                    .isInstanceOf(TurtleParser.TurtleParseException.class)
                    .hasMessageContaining(".ttl");
        @DisplayName("validateTurtleContent rejects null")
        void validateContentRejectsNull() {
                    .isThrownBy(() -> TurtleParser.validateTurtleContent(null));
        @DisplayName("validateTurtleContent rejects empty content")
        void validateContentRejectsEmpty() {
            assertThatThrownBy(() -> TurtleParser.validateTurtleContent(""))
                    .isInstanceOf(TurtleParser.MalformedTurtleException.class);
        @DisplayName("validateTurtleContent rejects blank content")
        void validateContentRejectsBlank() {
            assertThatThrownBy(() -> TurtleParser.validateTurtleContent("   \n\n  "))
        @DisplayName("validateTurtleContent accepts valid Turtle")
        void validateContentAcceptsValidTurtle() {
            var validTurtle =
                    """
                    @prefix ex: <http://example.org/> .
                    ex:Subject ex:predicate ex:object .
                    """;
            // Should not throw
            TurtleParser.validateTurtleContent(validTurtle);
        @DisplayName("validateTurtleContent accepts Turtle with prefixes only")
        void validateContentAcceptsPrefixesOnly() {
                    @base <http://example.org/base> .
    // ── Pattern Extraction Tests ─────────────────────────────────────────────
    @DisplayName("Pattern Extraction")
    class PatternExtractionTests {
        @DisplayName("extractPatterns rejects null path")
        void extractPatternsRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> TurtleParser.extractPatterns(null));
        @DisplayName("extractPatterns parses messaging ontology")
        void extractPatternsParsesMessaging() {
            var messagingTtl = SCHEMA_DIR.resolve("java-messaging.ttl");
            // Skip if file doesn't exist (CI environments)
            if (!Files.exists(messagingTtl)) {
                return;
            }
            var patterns = TurtleParser.extractPatterns(messagingTtl);
            assertThat(patterns).isNotEmpty();
            assertThat(patterns)
                    .allSatisfy(
                            p -> {
                                assertThat(p.uri()).isNotBlank();
                                assertThat(p.name()).isNotBlank();
                            });
        @DisplayName("extractPatterns returns empty list for non-pattern file")
        void extractPatternsReturnsEmptyForNonPatternFile(@TempDir Path tempDir) throws Exception {
            var ttlFile = tempDir.resolve("test.ttl");
            Files.writeString(
                    ttlFile,
                    ex:Subject a ex:Thing .
                    """);
            var patterns = TurtleParser.extractPatterns(ttlFile);
            // Should return empty list (no pattern definitions)
            assertThat(patterns).isEmpty();
    // ── Project Extraction Tests ──────────────────────────────────────────────
    @DisplayName("Project Extraction")
    class ProjectExtractionTests {
        @DisplayName("extractProject rejects null path")
        void extractProjectRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> TurtleParser.extractProject(null));
        @DisplayName("extractProject parses project ontology")
        void extractProjectParsesProject() {
            var projectTtl = SCHEMA_DIR.resolve("java-project.ttl");
            // Skip if file doesn't exist
            if (!Files.exists(projectTtl)) {
            var project = TurtleParser.extractProject(projectTtl);
            assertThat(project.name()).isNotBlank();
            assertThat(project.groupId()).isNotBlank();
        @DisplayName("extractProject returns defaults for empty file")
        void extractProjectReturnsDefaultsForEmptyFile(@TempDir Path tempDir) throws Exception {
            var ttlFile = tempDir.resolve("empty.ttl");
            Files.writeString(ttlFile, "");
            var project = TurtleParser.extractProject(ttlFile);
            assertThat(project.name()).isEqualTo("generated-project");
    // ── Dependency Resolution Tests ──────────────────────────────────────────
    @DisplayName("Dependency Resolution")
    class DependencyResolutionTests {
        @DisplayName("resolveDependencies rejects null list")
        void resolveDependenciesRejectsNull() {
                    .isThrownBy(() -> TurtleParser.resolveDependencies(null));
        @DisplayName("resolveDependencies returns same order for no dependencies")
        void resolveDependenciesNoDeps() {
            var p1 =
                            "uri1", "A", "cat", "tpl", null, 1, null, List.of());
            var p2 =
                            "uri2", "B", "cat", "tpl", null, 1, null, List.of());
            var resolved = TurtleParser.resolveDependencies(List.of(p1, p2));
            assertThat(resolved).containsExactly(p1, p2);
        @DisplayName("resolveDependencies puts dependency before dependent")
        void resolveDependenciesOrdersCorrectly() {
                            "uri1", "A", "cat", "tpl", null, 1, null, List.of("B"));
            // B should come before A (A depends on B)
            var names = resolved.stream().map(TurtleParser.PatternSpec::name).toList();
            assertThat(names.indexOf("B")).isLessThan(names.indexOf("A"));
        @DisplayName("resolveDependencies handles transitive dependencies")
        void resolveDependenciesHandlesTransitive() {
                            "uri2", "B", "cat", "tpl", null, 1, null, List.of("C"));
            var p3 =
                            "uri3", "C", "cat", "tpl", null, 1, null, List.of());
            var resolved = TurtleParser.resolveDependencies(List.of(p1, p2, p3));
            // Order should be: C, B, A
            assertThat(names).containsExactly("C", "B", "A");
        @DisplayName("resolveDependencies detects circular dependency")
        void resolveDependenciesDetectsCircular() {
                            "uri2", "B", "cat", "tpl", null, 1, null, List.of("A"));
            assertThatIllegalStateException()
                    .isThrownBy(() -> TurtleParser.resolveDependencies(List.of(p1, p2)))
                    .withMessageContaining("Circular dependency");
        @DisplayName("resolveDependencies ignores missing dependencies")
        void resolveDependenciesIgnoresMissing() {
                            "uri1", "A", "cat", "tpl", null, 1, null, List.of("NonExistent"));
            var resolved = TurtleParser.resolveDependencies(List.of(p1));
            assertThat(resolved).containsExactly(p1);
    // ── Messaging Patterns Extraction Tests ───────────────────────────────────
    @DisplayName("extractMessagingPatterns")
    class ExtractMessagingPatternsTests {
        @DisplayName("extractMessagingPatterns returns patterns from ontology")
        void extractMessagingPatternsReturnsPatterns() {
            var patterns = TurtleParser.extractMessagingPatterns();
}
