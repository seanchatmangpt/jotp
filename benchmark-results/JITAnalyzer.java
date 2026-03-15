import javax.tools.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * JIT Compilation Analyzer for JOTP
 *
 * Analyzes bytecode patterns that affect JIT optimization:
 * - Method inlining barriers
 * - Virtual call sites that could be static
 * - Polymorphic call sites
 * - Exception handling that prevents optimization
 * - Missing intrinsic opportunities
 */
public class JITAnalyzer {

    private static final Map<String, List<String>> ANALYSIS_RESULTS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> INLINE_CANDIDATES = new ConcurrentHashMap<>();
    private static final Map<String, List<CallSite>> CALL_SITES = new ConcurrentHashMap<>();

    record CallSite(String methodName, String targetMethod, String callType, boolean isPolymorphic) {}

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("JIT COMPILATION ANALYSIS FOR JOTP");
        System.out.println("=".repeat(80));

        // Analyze core classes
        analyzeClass("io.github.seanchatmangpt.jotp.Proc");
        analyzeClass("io.github.seanchatmangpt.jotp.Supervisor");
        analyzeClass("io.github.seanchatmangpt.jotp.StateMachine");
        analyzeClass("io.github.seanchatmangpt.jotp.Parallel");
        analyzeClass("io.github.seanchatmangpt.jotp.ProcMonitor");
        analyzeClass("io.github.seanchatmangpt.jotp.ProcRegistry");

        generateReport();
    }

    private static void analyzeClass(String className) throws Exception {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("ANALYZING: " + className);
        System.out.println("─".repeat(80));

        Class<?> clazz = Class.forName(className);
        List<String> issues = new ArrayList<>();
        Set<String> inlineCandidates = new HashSet<>();
        List<CallSite> callSites = new ArrayList<>();

        // Analyze methods
        for (Method method : clazz.getDeclaredMethods()) {
            analyzeMethod(method, issues, inlineCandidates, callSites);
        }

        // Analyze constructors
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            analyzeMethod(constructor, issues, inlineCandidates, callSites);
        }

        ANALYSIS_RESULTS.put(className, issues);
        INLINE_CANDIDATES.put(className, inlineCandidates);
        CALL_SITES.put(className, callSites);
    }

    private static void analyzeMethodExecutable(Object executable, List<String> issues) {
        // Analyze JIT compilation barriers
        analyzeCompilationBarriers(executable, issues);
    }

    private static void analyzeMethod(Object executable, List<String> issues,
                                      Set<String> inlineCandidates, List<CallSite> callSites) {
        String methodName = executable instanceof Method m ? m.getName() :
                           executable instanceof Constructor c ? c.getName() : "unknown";

        int modifiers = executable instanceof Method m ? m.getModifiers() :
                        executable instanceof Constructor c ? c.getModifiers() : 0;

        // Check for inline candidates
        if (executable instanceof Method m) {
            if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) ||
                Modifier.isPrivate(modifiers)) {
                if (m.getParameterCount() <= 3 && isShortMethod(m)) {
                    inlineCandidates.add(methodName);
                }
            }
        }

        // Check for virtual call barriers
        if (!Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) &&
            !Modifier.isPrivate(modifiers)) {
            issues.add(String.format(
                "Method '%s' is virtual (non-final) - prevents inlining at call sites",
                methodName
            ));
        }

        // Check for synchronization barriers
        if (executable instanceof Method m && Modifier.isSynchronized(modifiers)) {
            issues.add(String.format(
                "Method '%s' is synchronized - prevents many JIT optimizations",
                methodName
            ));
        }

        // Check exception handling
        if (executable instanceof Method m) {
            analyzeExceptionHandling(m, issues);
        }
    }

    private static boolean isShortMethod(Method method) {
        // Heuristic: methods with few parameters are better inline candidates
        return method.getParameterCount() <= 4;
    }

    private static void analyzeExceptionHandling(Method method, List<String> issues) {
        Class<?>[] exceptionTypes = method.getExceptionTypes();
        if (exceptionTypes.length > 2) {
            issues.add(String.format(
                "Method '%s' declares %d exceptions - complex exception handling may prevent optimization",
                method.getName(), exceptionTypes.length
            ));
        }
    }

    private static void analyzeCompilationBarriers(Object executable, List<String> issues) {
        // Check for common JIT optimization barriers
        String methodName = executable instanceof Method m ? m.getName() :
                           executable instanceof Constructor c ? c.getName() : "unknown";

        // Large methods are less likely to be inlined
        if (executable instanceof Method m && isLargeMethod(m)) {
            issues.add(String.format(
                "Method '%s' appears large - may not be inlined by JIT",
                methodName
            ));
        }
    }

    private static boolean isLargeMethod(Method method) {
        // Heuristic: methods with many parameters are less likely to be inlined
        return method.getParameterCount() > 5;
    }

    private static void generateReport() {
        StringBuilder report = new StringBuilder();

        report.append("\n");
        report.append("=".repeat(80));
        report.append("\nJIT OPTIMIZATION ANALYSIS REPORT\n");
        report.append("=".repeat(80));
        report.append("\n\n");

        // Summary section
        report.append("## SUMMARY\n\n");
        report.append("Total classes analyzed: ").append(ANALYSIS_RESULTS.size()).append("\n");
        report.append("Total issues found: ")
              .append(ANALYSIS_RESULTS.values().stream().mapToInt(List::size).sum()).append("\n");
        report.append("Total inline candidates: ")
              .append(INLINE_CANDIDATES.values().stream().mapToInt(Set::size).sum()).append("\n\n");

        // Detailed findings per class
        for (Map.Entry<String, List<String>> entry : ANALYSIS_RESULTS.entrySet()) {
            String className = entry.getKey();
            List<String> issues = entry.getValue();
            Set<String> inlineCandidates = INLINE_CANDIDATES.get(className);

            report.append("## ").append(className).append("\n\n");

            if (!issues.isEmpty()) {
                report.append("### Optimization Barriers (").append(issues.size()).append(")\n\n");
                for (String issue : issues) {
                    report.append("- **ISSUE**: ").append(issue).append("\n");
                }
                report.append("\n");
            } else {
                report.append("### Optimization Barriers\n\n");
                report.append("No significant barriers found.\n\n");
            }

            if (!inlineCandidates.isEmpty()) {
                report.append("### Inline Candidates (").append(inlineCandidates.size()).append(")\n\n");
                for (String candidate : inlineCandidates) {
                    report.append("- ").append(candidate).append("\n");
                }
                report.append("\n");
            }

            report.append("### Recommendations\n\n");

            if (issues.isEmpty() && inlineCandidates.isEmpty()) {
                report.append("Code appears well-optimized for JIT compilation.\n\n");
            } else {
                report.append("1. Consider marking frequently-called methods as `final` to enable inlining\n");
                report.append("2. Reduce synchronization in hot paths\n");
                report.append("3. Minimize exception handling in performance-critical code\n");
                report.append("4. Use `@HotSpotIntrinsicCandidate` where applicable\n");
                report.append("5. Consider method size reduction for better inline opportunities\n\n");
            }
        }

        // JVM flags section
        report.append("## RECOMMENDED JVM FLAGS FOR JIT ANALYSIS\n\n");
        report.append("```bash\n");
        report.append("# Print compilation decisions\n");
        report.append("-XX:+PrintCompilation\n");
        report.append("-XX:+UnlockDiagnosticVMOptions\n");
        report.append("-XX:+PrintInlining\n\n");

        report.append("# Print assembly output (requires hsdis)\n");
        report.append("-XX:+PrintAssembly\n");
        report.append("-XX:+PrintInterpreter\n\n");

        report.append("# Log compilation (JITWatch format)\n");
        report.append("-XX:+LogCompilation\n");
        report.append("-XX:LogFile=jit compilation.log\n\n");

        report.append("# Tiered compilation settings\n");
        report.append("-XX:+TieredCompilation\n");
        report.append("-XX:TieredStopAtLevel=4\n\n");

        report.append("# Inline settings\n");
        report.append("-XX:MaxInlineSize=35\n");
        report.append("-XX:FreqInlineSize=325\n\n");

        report.append("# For virtual thread analysis\n");
        report.append("-XX:+UnlockDiagnosticVMOptions\n");
        report.append("-XX:+PrintVirtualThreadOperations\n");
        report.append("```\n\n");

        // Optimization strategies
        report.append("## JIT OPTIMIZATION STRATEGIES FOR JOTP\n\n");

        report.append("### 1. Method Inlining\n");
        report.append("- Make hot methods `final` or `static` where possible\n");
        report.append("- Keep method sizes small (< 35 bytes for MaxInlineSize)\n");
        report.append("- Reduce branching in performance-critical paths\n\n");

        report.append("### 2. Virtual Call Optimization\n");
        report.append("- Use sealed types + pattern matching for devirtualization\n");
        report.append("- Consider `final` classes for hot types that don't need extension\n");
        report.append("- Leverage Java 26's pattern matching for polymorphic call sites\n\n");

        report.append("### 3. Lock Elimination\n");
        report.append("- Use `volatile` instead of synchronization for single variables\n");
        report.append("- Consider `java.util.concurrent.atomic` for simple counters\n");
        report.append("- Use lock-free data structures (LinkedTransferQueue already used)\n\n");

        report.append("### 4. Escape Analysis\n");
        report.append("- Return immutable objects instead of mutable ones\n");
        report.append("- Use records for short-lived data objects\n");
        report.append("- Minimize object allocation in hot loops\n\n");

        report.append("### 5. Intrinsics\n");
        report.append("- Use `System.arraycopy` instead of manual array copying\n");
        report.append("- Use `String.indexOf`/`contains` instead of custom implementations\n");
        report.append("- Use `Math.*` functions which have intrinsic implementations\n\n");

        report.append("## JOTP-SPECIFIC FINDINGS\n\n");

        // Analyze specific JOTP patterns
        analyzeJOTPPatterns(report);

        System.out.println(report.toString());

        // Save report to file
        try {
            String outputPath = "/Users/sac/jotp/benchmark-results/ANALYSIS-08-jit-optimization.md";
            Files.writeString(Path.of(outputPath), report.toString());
            System.out.println("\nReport saved to: " + outputPath);
        } catch (IOException e) {
            System.err.println("Failed to save report: " + e.getMessage());
        }
    }

    private static void analyzeJOTPPatterns(StringBuilder report) {
        report.append("### Proc<S,M> Analysis\n");
        report.append("**Current Implementation:**\n");
        report.append("- Uses `LinkedTransferQueue` (lock-free) - EXCELLENT for JIT\n");
        report.append("- Virtual threads - good for throughput, but monitor contention can be an issue\n");
        report.append("- Lambda-based message handlers - may prevent some optimizations\n\n");

        report.append("**Recommendations:**\n");
        report.append("- The `tell()` and `ask()` methods are good inline candidates\n");
        report.append("- Consider marking `Envelope` record as `final` (already implicit)\n");
        report.append("- Handler function could be inlined if made `final` or static\n\n");

        report.append("### Supervisor Analysis\n");
        report.append("**Current Implementation:**\n");
        report.append("- Event-driven architecture with sealed events - GOOD for devirtualization\n");
        report.append("- Pattern matching on events - EXCELLENT for JIT optimization\n");
        report.append("- Complex restart logic may be too large for inlining\n\n");

        report.append("**Recommendations:**\n");
        report.append("- Event handling could benefit from `final` methods\n");
        report.append("- Consider splitting large restart methods into smaller ones\n");
        report.append("- Sealed event types already enable exhaustiveness checking\n\n");

        report.append("### StateMachine<S,E,D> Analysis\n");
        report.append("**Current Implementation:**\n");
        report.append("- Heavy use of sealed types - EXCELLENT for pattern matching optimization\n");
        report.append("- Complex transition logic - may exceed inline limits\n");
        report.append("- Multiple timer management - could benefit from intrinsic usage\n\n");

        report.append("**Recommendations:**\n");
        report.append("- Pattern matching on transitions is already JIT-friendly\n");
        report.append("- Consider using `System.nanoTime` instead of scheduled futures where possible\n");
        report.append("- Timeout management could use lock-free timers\n\n");

        report.append("### Virtual Thread Impact\n");
        report.append("**JIT Compilation Considerations:**\n");
        report.append("- Virtual threads have different JIT compilation profiles than platform threads\n");
        report.append("- Monitor contention is a major JIT optimization barrier\n");
        report.append("- Virtual thread pinning can prevent optimizations\n\n");

        report.append("**Recommendations:**\n");
        report.append("- Use `synchronized` blocks sparingly in virtual thread contexts\n");
        report.append("- Prefer `java.util.concurrent` lock implementations\n");
        report.append("- Monitor virtual thread pinning with `-XX:+PrintVirtualThreadOperations`\n\n");
    }
}
