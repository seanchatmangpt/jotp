package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: tests for ScopedValuePatterns demonstrating Java 26 ScopedValue API.
 *
 * <p>Project Loom (JEP 446) introduces ScopedValue — a modern alternative to ThreadLocal that
 * provides immutable, bounded-scoped context passing. Unlike ThreadLocal, ScopedValue is
 * automatically cleaned up when the scope exits and works correctly with virtual threads.
 *
 * <p>Key benefits: 1) Immutable by design — no accidental mutation 2) Automatic cleanup — no memory
 * leaks 3) Virtual thread safe — no per-thread overhead 4) Nested scopes — inner scopes can shadow
 * outer bindings
 */
@DisplayName("ScopedValuePatterns - Java 26 Scoped Context")
class ScopedValuePatternsTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    @DisplayName("handleAsUser binds user for scope")
    void handleAsUser_bindsUserForScope() {
                "ScopedValue provides a way to pass context through call stacks without explicit"
                        + " parameter passing. Unlike ThreadLocal, it's immutable and automatically cleaned"
                        + " up when the scope exits.");

                new String[][] {
                    {"Feature", "Cleanup", "Inheritance"},
                    {"ThreadLocal", "Manual (memory leak risk)", "InheritableThreadLocal needed"},
                    {"ScopedValue", "Automatic (scope-bound)", "Automatic inheritance"},
                    {"Mutability", "Virtual Threads", "Type Safety"},
                    {"Mutable (racy)", "High overhead", "Runtime type checking"},
                    {"Immutable (safe)", "Zero overhead", "Compile-time generics"}
                });

                """
            // Define a scoped value (usually as a static final field)
            static final ScopedValue<String> CURRENT_USER = ScopedValue.newInstance();

            // Bind a value for the duration of a lambda
            ScopedValuePatterns.handleAsUser("alice", () -> {
                String user = ScopedValuePatterns.currentUser();
                // user = "alice" within this scope
            });

            // Value is automatically unbound after lambda completes
            """,
                "java");

        AtomicReference<String> captured = new AtomicReference<>();
        ScopedValuePatterns.handleAsUser(
                "alice", () -> captured.set(ScopedValuePatterns.currentUser()));

        assertThat(captured.get()).isEqualTo("alice");

                Map.of(
                        "Scoped Binding",
                        "alice",
                        "Captured Value",
                        captured.get(),
                        "Scope",
                        "Lambda lifetime",
                        "Cleanup",
                        "Automatic"));

                "ScopedValue.where(key, value).run(task) binds the value for the duration of task."
                        + " After task completes (successfully or exceptionally), the binding is automatically"
                        + " removed.");
    }

    @Test
    @DisplayName("currentUser throws when not bound")
    void currentUser_throwsWhenNotBound() {
                "Attempting to get() an unbound ScopedValue throws NoSuchElementException. This is a"
                        + " feature, not a bug — it ensures that missing context is detected immediately rather"
                        + " silently using defaults.");

                """
            // Throws NoSuchElementException if not bound
            assertThatThrownBy(ScopedValuePatterns::currentUser)
                .isInstanceOf(NoSuchElementException.class);

            // Use isBound() to check safely
            if (ScopedValuePatterns.CURRENT_USER.isBound()) {
                String user = ScopedValuePatterns.currentUser();
            }
            """,
                "java");

        assertThatThrownBy(ScopedValuePatterns::currentUser)
                .isInstanceOf(java.util.NoSuchElementException.class);

                Map.of(
                        "Unbound Access",
                        "Throws NoSuchElementException",
                        "Safe Check",
                        "isBound() returns false",
                        "Design Philosophy",
                        "Fail fast, explicit context",
                        "Alternative",
                        "Optional wrapper or default"));

                "The fail-fast behavior prevents bugs where context is accidentally missing. Always use"
                        + " isBound() or Optional wrappers when the value might not be set.");
    }

    @Test
    @DisplayName("withTrace binds and returns")
    void withTrace_bindsAndReturns() throws Exception {
                "ScopedValue.where().call() supports tasks that return values. This is ideal for"
                        + " propagating context through computation pipelines while capturing results.");

                """
            // Bind trace ID and return a value
            Callable<String> task = () -> ScopedValuePatterns.TRACE_ID.get();
            String result = ScopedValuePatterns.withTrace("trace-123", task);

            // Result is "trace-123"
            // Trace ID is automatically unbound after call
            """,
                "java");

        Callable<String> task = () -> ScopedValuePatterns.TRACE_ID.get();
        var result = ScopedValuePatterns.withTrace("trace-123", task);

        assertThat(result).isEqualTo("trace-123");

                Map.of(
                        "Bound Value",
                        "trace-123",
                        "Task Result",
                        result,
                        "Return Type",
                        "String",
                        "Cleanup",
                        "Automatic after call"));

                "The Callable-based withTrace() allows tasks to return values while maintaining the"
                        + " scoped binding. This is perfect for request-scoped tracing, authentication, and"
                        + " tenant isolation.");
    }

    @Test
    @DisplayName("withTrace unbinds after scope")
    void withTrace_unbindsAfterScope() throws Exception {
                "ScopedValue bindings are automatically removed when the scope exits, whether normally"
                        + " or exceptionally. This eliminates the ThreadLocal memory leak pattern where"
                        + " values persist indefinitely.");

                """
            // Binding is only active during the lambda
            ScopedValuePatterns.withTrace("trace-456", () -> {
                assertThat(ScopedValuePatterns.TRACE_ID.get()).isEqualTo("trace-456");
                return null;
            });

            // Binding is automatically removed
            assertThat(ScopedValuePatterns.TRACE_ID.isBound()).isFalse();
            """,
                "java");

        ScopedValuePatterns.withTrace(
                "trace-456",
                () -> {
                    assertThat(ScopedValuePatterns.TRACE_ID.get()).isEqualTo("trace-456");
                    return null;
                });

        assertThat(ScopedValuePatterns.TRACE_ID.isBound()).isFalse();

                Map.of(
                        "During Lambda",
                        "TRACE_ID is bound",
                        "After Lambda",
                        "TRACE_ID is unbound",
                        "Cleanup",
                        "Automatic (finally block equivalent)",
                        "Memory Leak Risk",
                        "Zero"));

                "This automatic cleanup is a huge improvement over ThreadLocal, where manual remove()"
                        + " calls are often forgotten, causing memory leaks in thread pools.");
    }

    @Test
    @DisplayName("withRequestContext binds all values")
    void withRequestContext_bindsAllValues() {
                "Real-world applications often need multiple context values: user, trace ID, tenant,"
                        + " locale, etc. ScopedValue supports binding multiple values in a single scope using"
                        + " ScopedValue.where().where().call().");

                """
            // Bind multiple values together
            var ctx = new RequestContext("user-1", "trace-1", "tenant-1");
            ScopedValuePatterns.withRequestContext(ctx, () -> {
                String user = ScopedValuePatterns.CURRENT_USER.get();
                String trace = ScopedValuePatterns.TRACE_ID.get();
                String tenant = ScopedValuePatterns.TENANT_ID.get();
                // All three values are bound
            });
            """,
                "java");

        var requestCtx = new ScopedValuePatterns.RequestContext("user-1", "trace-1", "tenant-1");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedTenant = new AtomicReference<>();

        ScopedValuePatterns.withRequestContext(
                requestCtx,
                () -> {
                    capturedUser.set(ScopedValuePatterns.CURRENT_USER.get());
                    capturedTrace.set(ScopedValuePatterns.TRACE_ID.get());
                    capturedTenant.set(ScopedValuePatterns.TENANT_ID.get());
                });

        assertThat(capturedUser.get()).isEqualTo("user-1");
        assertThat(capturedTrace.get()).isEqualTo("trace-1");
        assertThat(capturedTenant.get()).isEqualTo("tenant-1");

                Map.of(
                        "User",
                        capturedUser.get(),
                        "Trace ID",
                        capturedTrace.get(),
                        "Tenant ID",
                        capturedTenant.get(),
                        "Binding Pattern",
                        "Chained where().where().call()",
                        "Scope",
                        "All values share same lifetime"));

                "The chained where() calls create a composite scope with all bindings. All values are"
                        + " unbound together when the scope exits, ensuring consistency.");
    }

    @Test
    @DisplayName("nested scopes shadow existing bindings")
    void nestedScopes_innerShadowsOuter() {
                "Inner scopes can shadow outer bindings, similar to lexical variable scoping. The"
                        + " outer binding is temporarily hidden and automatically restored when the inner"
                        + " scope exits.");

                """
            // Outer scope: bound to "alice"
            ScopedValuePatterns.handleAsUser("alice", () -> {
                String outer = ScopedValuePatterns.currentUser(); // "alice"

                // Inner scope: shadows with "SYSTEM"
                ScopedValuePatterns.runAsSystem(() -> {
                    String inner = ScopedValuePatterns.currentUser(); // "SYSTEM"
                });

                String restored = ScopedValuePatterns.currentUser(); // "alice" again
            });
            """,
                "java");

        AtomicReference<String> outerUser = new AtomicReference<>();
        AtomicReference<String> innerUser = new AtomicReference<>();

        ScopedValuePatterns.handleAsUser(
                "alice",
                () -> {
                    outerUser.set(ScopedValuePatterns.currentUser());
                    ScopedValuePatterns.runAsSystem(
                            () -> innerUser.set(ScopedValuePatterns.currentUser()));
                });

        assertThat(outerUser.get()).isEqualTo("alice");
        assertThat(innerUser.get()).isEqualTo("SYSTEM");

                Map.of(
                        "Outer Scope",
                        "alice",
                        "Inner Scope",
                        "SYSTEM (shadows outer)",
                        "After Inner Scope",
                        "alice (restored)",
                        "Pattern",
                        "Lexical scoping"));

                "This shadowing behavior is perfect for privilege escalation (admin vs user) or"
                        + " request context switching in multi-tenant systems.");
    }

    @Test
    @DisplayName("concurrentWithContext inherits scoped values")
    void concurrentWithContext_inheritsScopedValues() throws Exception {
                "Scoped values are automatically inherited by virtual threads spawned in a scope."
                        + " This is a game-changer compared to InheritableThreadLocal, which doesn't work"
                        + " reliably with thread pools.");

                new String[][] {
                    {"Feature", "Works correctly", "Mutable (racy)"},
                    {"InheritableThreadLocal", "Virtual Threads", "Immutable (safe)"},
                    {"ScopedValue", "High overhead", "Cleanup"},
                    {"Thread Pools", "Zero overhead", "Manual"},
                    {"Broken (stale values)", "Immutability", "Automatic"}
                });

                """
            // Bind context in parent, virtual threads inherit it
            Callable<String> taskA = () -> ScopedValuePatterns.CURRENT_USER.get() + "|A";
            Callable<String> taskB = () -> ScopedValuePatterns.TRACE_ID.get() + "|B";

            var results = ScopedValuePatterns.concurrentWithContext("alice", "trace-xyz", taskA, taskB);

            // Both virtual threads see the scoped values
            assertThat(results).containsExactlyInAnyOrder("alice|A", "trace-xyz|B");
            """,
                "java");

        Callable<String> taskA = () -> ScopedValuePatterns.CURRENT_USER.get() + "|A";
        Callable<String> taskB = () -> ScopedValuePatterns.TRACE_ID.get() + "|B";

        var results = ScopedValuePatterns.concurrentWithContext("alice", "trace-xyz", taskA, taskB);

        assertThat(results).containsExactlyInAnyOrder("alice|A", "trace-xyz|B");

                Map.of(
                        "Parent Scope",
                        "user=alice, trace=trace-xyz",
                        "Task A Result",
                        "alice|A",
                        "Task B Result",
                        "trace-xyz|B",
                        "Inheritance",
                        "Automatic",
                        "Thread Type",
                        "Virtual threads"));

                "This automatic inheritance makes distributed tracing and authentication context"
                        + " propagation trivial. No more passing context through every method signature!");
    }

    @Test
    @DisplayName("currentUserOrDefault returns default when not bound")
    void currentUserOrDefault_returnsDefaultWhenNotBound() {
                "When scoped values might be unbound, use safe access patterns: isBound() checks,"
                        + " Optional wrappers, or default values. Avoid NoSuchElementException in production"
                        + " code.");

                """
            // Pattern 1: Default value
            String user = ScopedValuePatterns.currentUserOrDefault("anonymous");

            // Pattern 2: Optional wrapper
            Optional<String> user = ScopedValuePatterns.currentUserOptional();

            // Pattern 3: Explicit check
            if (ScopedValuePatterns.CURRENT_USER.isBound()) {
                String user = ScopedValuePatterns.currentUser();
            }
            """,
                "java");

        assertThat(ScopedValuePatterns.currentUserOrDefault("default")).isEqualTo("default");
        assertThat(ScopedValuePatterns.currentUserOptional()).isEmpty();

                Map.of(
                        "Default Pattern",
                        "Returns 'default' when unbound",
                        "Optional Pattern",
                        "Returns Optional.empty()",
                        "Check Pattern",
                        "isBound() returns false",
                        "Recommendation",
                        "Use Optional for explicit handling"));

                "The Optional pattern is most explicit and forces callers to handle the missing case."
                        + " Default values can hide bugs where context should have been set but wasn't.");
    }
}
