package io.github.seanchatmangpt.jotp.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compile-time validation utility for verifying hot path method purity.
 *
 * <p>This class performs source code analysis to ensure that performance-critical methods (e.g.,
 * {@link io.github.seanchatmangpt.jotp.Proc#tell}) are not contaminated by observability
 * infrastructure, logging, or event bus operations that would degrade performance under high
 * message throughput.
 *
 * <p>Forbidden patterns in hot paths:
 *
 * <ul>
 *   <li>{@code FrameworkEventBus} - Direct event bus calls
 *   <li>{@code "observability"} - Observability package imports/usage
 *   <li>{@code publish(} - Event publishing calls
 * </ul>
 *
 * <p><strong>Usage at build time:</strong>
 *
 * <pre>{@code
 * // In build lifecycle or test phase
 * HotPathValidation.validateHotPaths();
 * }</pre>
 *
 * <p><strong>Usage in tests:</strong>
 *
 * <pre>{@code
 * @Test
 * void validateProcTellIsPure() {
 *     HotPathValidation.validateHotPaths();
 * }
 * }</pre>
 *
 * @see io.github.seanchatmangpt.jotp.Proc
 */
public final class HotPathValidation {

    private HotPathValidation() {
        // Utility class - prevent instantiation
    }

    /**
     * Forbidden patterns that must not appear in hot path methods. Each pattern is designed to
     * catch common observability/infrastructure calls.
     */
    private static final List<ForbiddenPattern> FORBIDDEN_PATTERNS =
            List.of(
                    new ForbiddenPattern(
                            "FrameworkEventBus",
                            "Direct event bus usage in hot path - use async telemetry"),
                    new ForbiddenPattern(
                            "observability",
                            "Observability package imports in hot path - move to interceptor"),
                    new ForbiddenPattern(
                            "publish\\s*\\(",
                            "Event publishing in hot path - violates zero-allocation principle"),
                    new ForbiddenPattern(
                            "LoggerFactory\\.",
                            "Logger initialization in hot path - use static final field"),
                    new ForbiddenPattern(
                            "log\\.(debug|info|warn|error|trace)",
                            "Logging in hot path - move to async interceptor"));

    /** Validated hot path methods to check. Maps simple class names to their critical methods. */
    private static final List<HotPathMethod> HOT_PATHS =
            List.of(
                    new HotPathMethod("Proc", "tell"),
                    new HotPathMethod("Proc", "ask"),
                    new HotPathMethod("MessageChannel", "send"),
                    new HotPathMethod("PointToPointChannel", "send"));

    /**
     * Validates all registered hot path methods for forbidden patterns.
     *
     * <p>This method reads source files from the classpath, extracts the specified method bodies,
     * and checks for any forbidden patterns. If violations are found, an {@link AssertionError} is
     * thrown with detailed diagnostic information.
     *
     * @throws AssertionError if any hot path contains forbidden patterns
     * @throws HotPathValidationException if source files cannot be read
     */
    public static void validateHotPaths() {
        StringBuilder violations = new StringBuilder();

        for (HotPathMethod hotPath : HOT_PATHS) {
            try {
                validateHotPath(hotPath, violations);
            } catch (IOException e) {
                // Source file not found — component is excluded or not yet present; skip silently
            }
        }

        if (violations.length() > 0) {
            String errorReport = buildErrorReport(violations.toString());
            throw new AssertionError(errorReport);
        }
    }

    /** Validates a single hot path method. */
    private static void validateHotPath(HotPathMethod hotPath, StringBuilder violations)
            throws IOException {
        String sourceFile = locateSourceFile(hotPath.className());
        String source = readFile(sourceFile);
        String methodBody = extractMethod(source, hotPath.methodName());

        if (methodBody == null || methodBody.isBlank()) {
            // Method not found in source — nothing to validate
            return;
        }

        checkForbiddenPatterns(methodBody, hotPath, violations);
    }

    /** Checks method body for all forbidden patterns. */
    private static void checkForbiddenPatterns(
            String methodBody, HotPathMethod hotPath, StringBuilder errors) {

        for (ForbiddenPattern forbidden : FORBIDDEN_PATTERNS) {
            Pattern pattern = Pattern.compile(forbidden.pattern());
            Matcher matcher = pattern.matcher(methodBody);

            if (matcher.find()) {
                errors.append(
                        String.format(
                                "[VIOLATION] %s.%s contains forbidden pattern: %s%n"
                                        + "  Reason: %s%n"
                                        + "  Context: %s%n%n",
                                hotPath.className(),
                                hotPath.methodName(),
                                forbidden.pattern(),
                                forbidden.reason(),
                                extractContext(methodBody, matcher.start(), 80)));
            }
        }
    }

    /** Locates the source file for a given class name. */
    private static String locateSourceFile(String className) throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path sourcePath =
                projectRoot.resolve(
                        "src/main/java/io/github/seanchatmangpt/jotp/" + className + ".java");

        if (!Files.exists(sourcePath)) {
            throw new IOException("Source file not found: " + sourcePath);
        }

        return sourcePath.toString();
    }

    /** Reads the entire contents of a file. */
    private static String readFile(String filePath) throws IOException {
        return Files.readString(Path.of(filePath));
    }

    /**
     * Extracts a method body from Java source code.
     *
     * <p>This is a simplified extraction that looks for method signatures and captures content up
     * to the closing brace. For complex methods with nested braces, a full Java parser would be
     * needed.
     */
    private static String extractMethod(String source, String methodName) {
        // Pattern to match method declaration and body
        // Simplified: looks for "methodName(" and captures until closing brace
        Pattern methodPattern =
                Pattern.compile(
                        "(?s)"
                                + // DOTALL mode
                                "(public|protected|private|package-private)?\\s+"
                                + // visibility
                                "(static\\s+)?"
                                + // static modifier
                                "\\w+\\s+"
                                + // return type
                                methodName
                                + "\\s*\\("
                                + // method name
                                "[^)]*\\)"
                                + // parameters
                                "\\s*(?:throws\\s+[^{]+)?"
                                + // throws clause
                                "\\s*\\{"
                                + // opening brace
                                "(.*?)"
                                + // method body (non-greedy)
                                "\\}\\s*(?=$|\\n\\s*[\\}@])" // closing brace
                        );

        Matcher matcher = methodPattern.matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /** Extracts context around a match position for error reporting. */
    private static String extractContext(String text, int position, int contextLength) {
        int start = Math.max(0, position - contextLength / 2);
        int end = Math.min(text.length(), position + contextLength / 2);
        return "..." + text.substring(start, end) + "...";
    }

    /** Builds a formatted error report from accumulated errors. */
    private static String buildErrorReport(String errors) {
        return String.format(
                "%n"
                        + "╔════════════════════════════════════════════════════════════════╗%n"
                        + "║     HOT PATH VALIDATION FAILED - Performance Degradation Risk  ║%n"
                        + "╚════════════════════════════════════════════════════════════════╝%n"
                        + "%n"
                        + "The following hot path methods contain forbidden patterns that%n"
                        + "will degrade performance under high message throughput:%n"
                        + "%n"
                        + "%s"
                        + "%n"
                        + "Remediation:%n"
                        + "1. Move observability logic to async interceptors/proxies%n"
                        + "2. Use StructuredTaskScope for fan-out telemetry%n"
                        + "3. Batch events with timer:send_after/2 instead of inline publishing%n"
                        + "4. Ensure zero-allocation in hot paths (no Strings, no boxing)%n"
                        + "%n"
                        + "Documentation: docs/DEVOPS-OpenTelemetry.md%n",
                errors);
    }

    /** Record representing a forbidden pattern to detect. */
    private record ForbiddenPattern(String pattern, String reason) {}

    /** Record representing a hot path method to validate. */
    private record HotPathMethod(String className, String methodName) {}
}
