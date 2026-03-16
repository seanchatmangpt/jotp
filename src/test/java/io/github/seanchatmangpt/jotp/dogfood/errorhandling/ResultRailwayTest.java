package io.github.seanchatmangpt.jotp.dogfood.errorhandling;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for ResultRailway demonstrating railway-oriented error handling.
 *
 * <p>Railway-oriented programming treats errors as first-class values that flow through a
 * pipeline." + " Instead of throwing exceptions, operations return Result<T,E> which is either
 * Success<T> or" + " Failure<E>. This enables explicit error handling without exception-based
 * control flow."
 *
 * <p>Key patterns: - map/flatMap for chaining operations - recover for fallback values - fold for"
 * + " branching - peek/peekError for side effects
 */
@DtrTest
@DisplayName("ResultRailway - Railway-Oriented Error Handling")
class ResultRailwayTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    @DisplayName("success is success")
    void success_isSuccess() {
        ctx.sayNextSection("Railway-Oriented Programming Basics");
        ctx.say(
                "Railway-oriented programming (ROP) treats errors as values, not exceptions. A Result<T,E>"
                        + " is either Success<T> or Failure<E>. This enables explicit error handling without"
                        + " try-catch blocks.");

        ctx.sayTable(
                new String[][] {
                    {"Aspect", "Error Handling", "Composition"},
                    {"Exception-Based", "try-catch blocks", "Difficult (exception flow)"},
                    {"Railway-Oriented", "Pattern matching on Result", "Easy (monadic chaining)"},
                    {"Error Propagation", "Type Safety", "Debugging"},
                    {"Implicit (stack unwinding)", "Runtime checks", "Stack traces"},
                    {
                        "Explicit (return values)",
                        "Compile-time exhaustiveness",
                        "Explicit error path"
                    }
                });

        ctx.sayCode(
                """
            // Create success result
            Result<String, String> success = ResultRailway.success("hello");

            assertThat(success.isSuccess()).isTrue();
            assertThat(success.isFailure()).isFalse();
            assertThat(success).isInstanceOf(ResultRailway.Success.class);

            // Create failure result
            Result<String, String> failure = ResultRailway.failure("error");

            assertThat(failure.isFailure()).isTrue();
            assertThat(failure.isSuccess()).isFalse();
            assertThat(failure).isInstanceOf(ResultRailway.Failure.class);
            """,
                "java");

        var r = ResultRailway.success("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
        assertThat(r).isInstanceOf(ResultRailway.Success.class);

        var r2 = ResultRailway.failure("error");
        assertThat(r2.isFailure()).isTrue();
        assertThat(r2.isSuccess()).isFalse();
        assertThat(r2).isInstanceOf(ResultRailway.Failure.class);

        ctx.sayKeyValue(
                Map.of(
                        "Success Variant",
                        "isSuccess() = true",
                        "Failure Variant",
                        "isFailure() = true",
                        "Pattern",
                        "Sealed interface",
                        "Type Safety",
                        "Compile-time"));

        ctx.sayNote(
                "Result<T,E> is a sealed interface with Success<T,E> and Failure<T,E> records. The"
                        + " compiler enforces exhaustive pattern matching, ensuring all cases are handled.");
    }

    @Test
    @DisplayName("of wraps successful supplier")
    void of_wrapsSuccessfulSupplier() {
        ctx.sayNextSection("Exception Wrapping with of()");
        ctx.say(
                "The Result.of() factory method wraps a supplier, catching exceptions and converting them"
                        + " to Failure results. This bridges exception-based and railway-oriented code.");

        ctx.sayCode(
                """
            // Wrap supplier that may throw
            Result<Integer, RuntimeException> result = ResultRailway.of(() -> 42);

            // Success case: value wrapped in Success
            assertThat(result.isSuccess()).isTrue();
            assertThat(((ResultRailway.Success<Integer, ?>) result).value()).isEqualTo(42);

            // Failure case: exception caught and wrapped
            Result<Integer, RuntimeException> failed = ResultRailway.<Integer, RuntimeException>of(() -> {
                throw new IllegalStateException("boom");
            });

            assertThat(failed.isFailure()).isTrue();
            assertThat((Throwable) ((ResultRailway.Failure<Integer, ?>) failed).error())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
            """,
                "java");

        var r = ResultRailway.of(() -> 42);
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<Integer, ?>) r).value()).isEqualTo(42);

        var r2 =
                ResultRailway.<Integer, RuntimeException>of(
                        () -> {
                            throw new IllegalStateException("boom");
                        });
        assertThat(r2.isFailure()).isTrue();
        assertThat((Throwable) ((ResultRailway.Failure<Integer, ?>) r2).error())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        ctx.sayKeyValue(
                Map.of(
                        "Success Path",
                        "Value wrapped in Success",
                        "Failure Path",
                        "Exception caught and wrapped",
                        "Exception Type",
                        "RuntimeException",
                        "Bridge",
                        "Exception → Railway"));

        ctx.sayNote(
                "Result.of() is perfect for wrapping legacy exception-based APIs. The exception becomes"
                        + " a value that can be processed with map/flatMap instead of try-catch.");
    }

    @Test
    @DisplayName("map transforms success value")
    void map_transformsSuccessValue() {
        ctx.sayNextSection("Functor: map() for Transforming Success");
        ctx.say(
                "The map() method transforms the success value while preserving failures. This is the"
                        + " functor pattern — applying a function to the wrapped value without unwrapping.");

        ctx.sayCode(
                """
            // Map only applies to Success
            Result<String, String> success = ResultRailway.success("hello");
            Result<String, String> upper = success.map(String::toUpperCase);

            assertThat(upper.isSuccess()).isTrue();
            assertThat(((ResultRailway.Success<String, ?>) upper).value()).isEqualTo("HELLO");

            // Map passes through Failure unchanged
            Result<String, String> failure = ResultRailway.failure("err");
            Result<String, String> mapped = failure.map(String::toUpperCase);

            assertThat(mapped.isFailure()).isTrue();
            assertThat(((ResultRailway.Failure<?, String>) mapped).error()).isEqualTo("err");
            """,
                "java");

        var r = ResultRailway.<String, String>success("hello").map(String::toUpperCase);
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("HELLO");

        var r2 = ResultRailway.<String, String>failure("err").map(String::toUpperCase);
        assertThat(r2.isFailure()).isTrue();
        assertThat(((ResultRailway.Failure<?, String>) r2).error()).isEqualTo("err");

        ctx.sayKeyValue(
                Map.of(
                        "Success.map",
                        "Transforms value",
                        "Failure.map",
                        "Passes through unchanged",
                        "Pattern",
                        "Functor",
                        "Composition",
                        "Chainable"));

        ctx.sayNote(
                "map() is one-directional: it transforms Success but ignores Failure. For two-way"
                        + " transformation, use fold() or bimap() if available.");
    }

    @Test
    @DisplayName("flatMap chains success")
    void flatMap_chainsSuccess() {
        ctx.sayNextSection("Monad: flatMap() for Chaining Operations");
        ctx.say(
                "The flatMap() method chains operations that return Results. This is the monad pattern —"
                        + " sequencing computations that might fail, short-circuiting on the first failure.");

        ctx.sayCode(
                """
            // Chain operations that return Result
            Result<Integer, String> result = ResultRailway.<String, String>success("42")
                .flatMap(s -> {
                    try {
                        int n = Integer.parseInt(s);
                        return ResultRailway.success(n);
                    } catch (NumberFormatException e) {
                        return ResultRailway.failure("not a number");
                    }
                })
                .map(n -> n * 2);

            // Result: Success(84)
            assertThat(result.isSuccess()).isTrue();
            """,
                "java");

        var r =
                ResultRailway.<String, String>success("42")
                        .flatMap(s -> ResultRailway.success(Integer.parseInt(s)));
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<Integer, ?>) r).value()).isEqualTo(42);

        var r2 =
                ResultRailway.<String, String>failure("no")
                        .flatMap(s -> ResultRailway.success(Integer.parseInt(s)));
        assertThat(r2.isFailure()).isTrue();

        var r3 =
                ResultRailway.<String, String>success("abc")
                        .flatMap(s -> ResultRailway.failure("inner error"));
        assertThat(r3.isFailure()).isTrue();

        ctx.sayKeyValue(
                Map.of(
                        "Success Chain",
                        "Continues to next operation",
                        "Failure Chain",
                        "Short-circuits (skips rest)",
                        "Pattern",
                        "Monad",
                        "Railway",
                        "Fail → Fail"));

        ctx.sayNote(
                "flatMap() enables railway-oriented programming where operations are chained like"
                        + " track switches. If any operation fails, the rest of the chain is skipped.");
    }

    @Test
    @DisplayName("fold branches on success/failure")
    void fold_appliesSuccessBranch() {
        ctx.sayNextSection("Branching with fold()");
        ctx.say(
                "The fold() method branches on success or failure, applying different functions to each"
                        + " case. This is the catamorphism pattern — collapsing a structure into a value.");

        ctx.sayCode(
                """
            // Fold applies different functions based on variant
            int result = ResultRailway.<String, String>success("hello")
                .fold(
                    value -> value.length(),    // Success branch
                    error -> -1                 // Failure branch
                );

            // Result: 5 (length of "hello")
            assertThat(result).isEqualTo(5);

            // Failure case
            int result2 = ResultRailway.<String, String>failure("err")
                .fold(
                    value -> value.length(),
                    error -> -1
                );

            // Result: -1 (error marker)
            assertThat(result2).isEqualTo(-1);
            """,
                "java");

        var result = ResultRailway.<String, String>success("hello").fold(String::length, e -> -1);
        assertThat(result).isEqualTo(5);

        var result2 = ResultRailway.<String, String>failure("err").fold(String::length, e -> -1);
        assertThat(result2).isEqualTo(-1);

        ctx.sayKeyValue(
                Map.of(
                        "Success Path",
                        "Applies success function",
                        "Failure Path",
                        "Applies error function",
                        "Return Type",
                        "Same for both branches (R)",
                        "Pattern",
                        "Catamorphism"));

        ctx.sayNote(
                "fold() is the most flexible way to extract values from Result. Unlike orElse(), it"
                        + " handles both success and failure cases explicitly.");
    }

    @Test
    @DisplayName("recover applies fallback on failure")
    void recover_appliesFallbackOnFailure() {
        ctx.sayNextSection("Error Recovery with recover()");
        ctx.say(
                "The recover() method provides fallback values for failures. This is similar to"
                        + " Optional.orElse() but for errors — convert failures into successes with defaults.");

        ctx.sayCode(
                """
            // Recover with default value on failure
            String value = ResultRailway.<String, String>failure("err")
                .recover(error -> "fallback");

            // Result: "fallback"
            assertThat(value).isEqualTo("fallback");

            // Success returns value unchanged
            String value2 = ResultRailway.<String, String>success("ok")
                .recover(error -> "fallback");

            // Result: "ok"
            assertThat(value2).isEqualTo("ok");
            """,
                "java");

        var r = ResultRailway.<String, String>success("ok").recover(e -> "fallback");
        assertThat(r).isEqualTo("ok");

        var r2 = ResultRailway.<String, String>failure("err").recover(e -> "fallback");
        assertThat(r2).isEqualTo("fallback");

        ctx.sayKeyValue(
                Map.of(
                        "Success Path",
                        "Returns value unchanged",
                        "Failure Path",
                        "Applies recovery function",
                        "Result Type",
                        "Success value type (T)",
                        "Pattern",
                        "Error recovery"));

        ctx.sayNote(
                "recover() is perfect for providing defaults: missing config → default value, API"
                        + " failure → cached response, validation error → sanitized input.");
    }

    @Test
    @DisplayName("peek called on success")
    void peek_calledOnSuccess() {
        ctx.sayNextSection("Side Effects with peek() and peekError()");
        ctx.say(
                "The peek() methods execute side effects without transforming values. peek() runs on"
                        + " success, peekError() runs on failure. Useful for logging, metrics, debugging.");

        ctx.sayCode(
                """
            // Peek runs on success
            var called = new AtomicBoolean(false);
            ResultRailway.<String, String>success("ok")
                .peek(v -> called.set(true));

            assertThat(called).isTrue();

            // Peek doesn't run on failure
            var called2 = new AtomicBoolean(false);
            ResultRailway.<String, String>failure("err")
                .peek(v -> called2.set(true));

            assertThat(called2).isFalse();

            // peekError runs on failure
            var called3 = new AtomicBoolean(false);
            ResultRailway.<String, String>failure("err")
                .peekError(e -> called3.set(true));

            assertThat(called3).isTrue();
            """,
                "java");

        var called = new AtomicBoolean(false);
        ResultRailway.<String, String>success("ok").peek(v -> called.set(true));
        assertThat(called).isTrue();

        var called2 = new AtomicBoolean(false);
        ResultRailway.<String, String>failure("err").peek(v -> called2.set(true));
        assertThat(called2).isFalse();

        var called3 = new AtomicBoolean(false);
        ResultRailway.<String, String>failure("err").peekError(e -> called3.set(true));
        assertThat(called3).isTrue();

        var r = ResultRailway.<String, String>success("ok");
        assertThat(r.peek(v -> {})).isSameAs(r);

        ctx.sayKeyValue(
                Map.of(
                        "peek()",
                        "Runs on success",
                        "peekError()",
                        "Runs on failure",
                        "Return",
                        "Original Result (chainable)",
                        "Use Case",
                        "Logging, metrics, debugging"));

        ctx.sayNote(
                "peek() returns the original Result, enabling chaining. Use it for logging and"
                        + " observability without affecting the computation pipeline.");
    }

    @Test
    @DisplayName("railwayChain short-circuits on first failure")
    void railwayChain_shortCircuitsOnFirstFailure() {
        ctx.sayNextSection("Railway-Oriented Pipelines");
        ctx.say(
                "Railway-oriented programming chains operations like track switches. Success moves"
                        + " forward; failure switches to a side track and bypasses remaining operations.");

        ctx.sayCode(
                """
            // Railway pipeline: strip → parse → double → format
            String result = ResultRailway.<String, String>success("  42  ")
                .map(String::strip)              // Success: "42"
                .flatMap(s ->
                    s.matches("\\d+")
                        ? ResultRailway.success(Integer.parseInt(s))  // Success: 42
                        : ResultRailway.failure("not a number")        // Failure: short-circuit
                )
                .map(n -> n * 2)                // Success: 84
                .fold(n -> "result=" + n, e -> "error=" + e);

            // Result: "result=84"
            assertThat(result).isEqualTo("result=84");
            """,
                "java");

        var result =
                ResultRailway.<String, String>success("  42  ")
                        .map(String::strip)
                        .flatMap(
                                s ->
                                        s.matches("\\d+")
                                                ? ResultRailway.success(Integer.parseInt(s))
                                                : ResultRailway.failure("not a number"))
                        .map(n -> n * 2)
                        .fold(n -> "result=" + n, e -> "error=" + e);

        assertThat(result).isEqualTo("result=84");

        var result2 =
                ResultRailway.<String, String>success("abc")
                        .flatMap(s -> ResultRailway.failure("not a number"))
                        .map(n -> n + "!")
                        .fold(v -> "ok", e -> "failed: " + e);

        assertThat(result2).isEqualTo("failed: not a number");

        ctx.sayKeyValue(
                Map.of(
                        "Success Pipeline",
                        "strip → parse → double → format",
                        "Success Result",
                        "\"result=84\"",
                        "Failure Pipeline",
                        "parse fails → rest skipped",
                        "Failure Result",
                        "\"failed: not a number\"",
                        "Pattern",
                        "Railway switches"));

        ctx.sayNote(
                "Railway-oriented programming makes error paths explicit and type-safe. The compiler"
                        + " ensures all cases are handled, and the control flow is visible in the type"
                        + " system.");
    }
}
