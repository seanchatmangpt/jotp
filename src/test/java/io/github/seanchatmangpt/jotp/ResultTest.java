package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrExtension;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link Result} — verifies Javadoc documentation matches actual behavior.
 *
 * <p>Tests the sealed Result interface for railway-oriented programming, matching the Erlang/OTP
 * pattern: {ok, Value} | {error, Reason}.
 */
@ExtendWith(DtrExtension.class)
@DisplayName("Result<T,E> railway-oriented programming")
class ResultTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Factory Methods — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Result.ok(value) — Erlang's {ok, Value}")
    class OkFactory {

        @Test
        @DisplayName("creates successful result carrying a value")
        void createsSuccessWithValue(DtrContext ctx) {
            ctx.sayNextSection("Result: Railway-Oriented Error Handling");
            ctx.say(
                    """
                    Result<T,E> is Java 26's implementation of Erlang's {ok, Value} | {error, Reason} pattern.
                    Instead of throwing exceptions that propagate invisibly, operations return a Result
                    that forces explicit handling of both success and failure cases.
                    """);

            ctx.sayCode(
                    """
                Result<String, Exception> result = Result.ok("hello");

                assertThat(result.isSuccess()).isTrue();
                assertThat(result.isError()).isFalse();
                assertThat(result).isInstanceOf(Result.Ok.class);
                """,
                    "java");

            Result<String, Exception> result = Result.ok("hello");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isError()).isFalse();
            assertThat(result.isFailure()).isFalse();
            assertThat(result).isInstanceOf(Result.Ok.class);

            ctx.sayTable(
                    new String[][] {
                        {"Method", "Returns", "Purpose"},
                        {"isSuccess()", "true", "Result is on success track"},
                        {"isError()", "false", "Result is NOT on error track"},
                        {"isFailure()", "false", "Alias for isError()"}
                    });
        }

        @Test
        @DisplayName("pattern matches with Ok record")
        void patternMatchesOkRecord() {
            Result<Integer, String> result = Result.ok(42);

            switch (result) {
                case Result.Ok<Integer, String>(var v) -> assertThat(v).isEqualTo(42);
                default -> fail("Expected Ok variant");
            }
        }

        @Test
        @DisplayName("returns value via orElseThrow")
        void returnsValueViaOrElseThrow() {
            Result<String, Exception> result = Result.ok("value");
            assertThat(result.orElseThrow()).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("Result.err(error) — Erlang's {error, Reason}")
    class ErrFactory {

        @Test
        @DisplayName("creates failed result carrying an error")
        void createsFailureWithError() {
            Result<String, String> result = Result.err("something went wrong");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isError()).isTrue();
            assertThat(result.isFailure()).isTrue();
            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("pattern matches with Err record")
        void patternMatchesErrRecord() {
            Result<String, Integer> result = Result.err(404);

            switch (result) {
                case Result.Err<String, Integer>(var e) -> assertThat(e).isEqualTo(404);
                default -> fail("Expected Err variant");
            }
        }

        @Test
        @DisplayName("throws on orElseThrow with string error")
        void throwsOnOrElseThrowWithStringError() {
            Result<String, String> result = Result.err("error message");

            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("error message");
        }

        @Test
        @DisplayName("throws on orElseThrow with RuntimeException error (direct throw)")
        void throwsOnOrElseThrowWithRuntimeException() {
            RuntimeException original = new IllegalStateException("boom");
            Result<String, RuntimeException> result = Result.err(original);

            assertThatThrownBy(result::orElseThrow)
                    .isSameAs(original)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("throws on orElseThrow with checked exception (wrapped)")
        void throwsOnOrElseThrowWithCheckedException() {
            Exception original = new Exception("checked");
            Result<String, Exception> result = Result.err(original);

            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("Result.success(value) — alias for ok()")
    class SuccessFactory {

        @Test
        @DisplayName("is equivalent to ok()")
        void equivalentToOk() {
            Result<String, Exception> r1 = Result.success("value");
            Result<String, Exception> r2 = Result.ok("value");

            assertThat(r1.isSuccess()).isTrue();
            assertThat(r2.isSuccess()).isTrue();
            assertThat(r1).isInstanceOf(Result.Ok.class);
            assertThat(r2).isInstanceOf(Result.Ok.class);
        }

        @Test
        @DisplayName("pattern matches with Success record")
        void patternMatchesSuccessRecord() {
            Result<Integer, String> result = Result.success(99);

            switch (result) {
                case Result.Ok<Integer, String>(var v) -> assertThat(v).isEqualTo(99);
                default -> fail("Expected Success variant");
            }
        }
    }

    @Nested
    @DisplayName("Result.failure(error) — alias for err()")
    class FailureFactory {

        @Test
        @DisplayName("is equivalent to err()")
        void equivalentToErr() {
            Result<String, String> r1 = Result.failure("error");
            Result<String, String> r2 = Result.err("error");

            assertThat(r1.isFailure()).isTrue();
            assertThat(r2.isFailure()).isTrue();
            assertThat(r1).isInstanceOf(Result.Err.class);
            assertThat(r2).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("pattern matches with Failure record")
        void patternMatchesFailureRecord() {
            Result<String, String> result = Result.failure("failed");

            switch (result) {
                case Result.Err<String, String>(var e) -> assertThat(e).isEqualTo("failed");
                default -> fail("Expected Failure variant");
            }
        }
    }

    @Nested
    @DisplayName("Result.of(Supplier) — wraps throwing operations")
    class OfFactory {

        @Test
        @DisplayName("returns Ok when supplier succeeds")
        void returnsOkOnSuccess() {
            Result<Integer, Exception> result = Result.of(() -> 42);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(42);
        }

        @Test
        @DisplayName("returns Err when supplier throws")
        void returnsErrOnException() {
            Result<Integer, Exception> result =
                    Result.of(
                            () -> {
                                throw new IllegalStateException("boom");
                            });

            assertThat(result.isError()).isTrue();
            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("captures exception as error value")
        void capturesExceptionAsError() {
            Result<String, Exception> result =
                    Result.of(
                            () -> {
                                throw new IllegalArgumentException("invalid");
                            });

            switch (result) {
                case Result.Err<String, Exception>(var e) -> {
                    assertThat(e).isInstanceOf(IllegalArgumentException.class);
                    assertThat(e.getMessage()).isEqualTo("invalid");
                }
                default -> fail("Expected Err variant");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Railway Operations — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("map(Function) — transforms success track")
    class MapOperation {

        @Test
        @DisplayName("transforms success value")
        void transformsSuccessValue(DtrContext ctx) {
            ctx.sayNextSection("Railway Operations: map() transforms the success track");
            ctx.say(
                    """
                    The map() function applies a transformation ONLY if the Result is on the success track.
                    If it's on the error track, map() short-circuits and returns the error unchanged.
                    This is the essence of railway-oriented programming: operations skip automatically
                    when things go wrong.
                    """);

            ctx.sayCode(
                    """
                Result<Integer, String> result = Result.<Integer, String>ok(5)
                    .map(x -> x * 2);

                assertThat(result.orElseThrow()).isEqualTo(10);

                // On error track, map() short-circuits:
                Result<Integer, String> error = Result.err("failed");
                Result<Integer, String> mapped = error.map(x -> x * 2);
                // mapped is still Err("failed") — the transformation never ran
                """,
                    "java");

            Result<Integer, String> result = Result.<Integer, String>ok(5).map(x -> x * 2);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(10);
        }

        @Test
        @DisplayName("short-circuits on failure — returns same error")
        void shortCircuitsOnFailure() {
            Result<Integer, String> original = Result.err("error");
            Result<Integer, String> mapped = original.map(x -> x * 2);

            assertThat(mapped.isError()).isTrue();
            assertThat(mapped).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("preserves Err type on short-circuit")
        void preservesErrType() {
            Result<String, Integer> result =
                    Result.<String, Integer>err(404).map(String::toUpperCase);

            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("preserves Failure type on short-circuit")
        void preservesFailureType() {
            Result<String, Integer> result =
                    Result.<String, Integer>failure(500).map(String::toUpperCase);

            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("chains multiple map operations")
        void chainsMultipleMaps() {
            Result<Integer, String> result =
                    Result.<Integer, String>ok(1).map(x -> x + 1).map(x -> x * 2).map(x -> x + 3);

            assertThat(result.orElseThrow()).isEqualTo(7); // ((1+1)*2)+3 = 7
        }
    }

    @Nested
    @DisplayName("flatMap(Function) — chains Result-returning operations")
    class FlatMapOperation {

        @Test
        @DisplayName("chains success to next result")
        void chainsSuccess(DtrContext ctx) {
            ctx.sayNextSection("Railway Operations: flatMap() chains fallible operations");
            ctx.say(
                    """
                    flatMap() chains operations that themselves return Results. This is how you build
                    pipelines where each step can fail. If any step returns an error, the entire
                    pipeline short-circuits — no further steps execute.
                    """);

            ctx.sayCode(
                    """
                Result<Integer, String> result = Result.<String, String>ok("42")
                    .flatMap(s -> {
                        try {
                            return Result.ok(Integer.parseInt(s));
                        } catch (NumberFormatException e) {
                            return Result.err("not a number");
                        }
                    });

                // If parsing succeeds, we get Result.ok(42)
                // If parsing fails, we get Result.err("not a number")
                """,
                    "java");

            Result<Integer, String> result =
                    Result.<String, String>ok("42")
                            .flatMap(
                                    s -> {
                                        try {
                                            return Result.ok(Integer.parseInt(s));
                                        } catch (NumberFormatException e) {
                                            return Result.err("not a number");
                                        }
                                    });

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(42);
        }

        @Test
        @DisplayName("short-circuits on failure")
        void shortCircuitsOnFailure() {
            Result<Integer, String> result =
                    Result.<String, String>err("initial error")
                            .flatMap(s -> Result.ok(Integer.parseInt(s)));

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("propagates inner failure")
        void propagatesInnerFailure() {
            Result<Integer, String> result =
                    Result.<String, String>ok("not-a-number")
                            .flatMap(s -> Result.err("parse error"));

            assertThat(result.isError()).isTrue();
            switch (result) {
                case Result.Err<Integer, String>(var e) -> assertThat(e).isEqualTo("parse error");
                default -> fail("Expected Err");
            }
        }
    }

    @Nested
    @DisplayName("orElse(default) — provides fallback")
    class OrElseOperation {

        @Test
        @DisplayName("returns success value")
        void returnsSuccessValue() {
            Result<String, Exception> result = Result.ok("actual");

            assertThat(result.orElse("fallback")).isEqualTo("actual");
        }

        @Test
        @DisplayName("returns default on failure")
        void returnsDefaultOnFailure() {
            Result<String, Exception> result = Result.err(new RuntimeException());

            assertThat(result.orElse("fallback")).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("fold(onSuccess, onError) — eliminates Result type")
    class FoldOperation {

        @Test
        @DisplayName("applies onSuccess for success")
        void appliesSuccessBranch(DtrContext ctx) {
            ctx.sayNextSection("Railway Operations: fold() eliminates the Result type");
            ctx.say(
                    """
                    fold() is the "eliminator" for Result — it handles both cases and returns a single
                    type. This is how you exit the railway and get a concrete value. Think of it as
                    pattern matching that's guaranteed exhaustive.
                    """);

            ctx.sayCode(
                    """
                Result<String, Integer> result = Result.ok("hello");
                int length = result.fold(
                    String::length,    // onSuccess: extract string length
                    error -> -1        // onError: return sentinel value
                );

                // For Result.err(404):
                String message = result.fold(
                    value -> "success: " + value,
                    error -> "error code: " + error
                );
                """,
                    "java");

            Result<String, Integer> result = Result.ok("hello");

            int length = result.fold(String::length, error -> -1);

            assertThat(length).isEqualTo(5);
        }

        @Test
        @DisplayName("applies onError for failure")
        void appliesErrorBranch() {
            Result<String, Integer> result = Result.err(404);

            String message =
                    result.fold(value -> "success: " + value, error -> "error code: " + error);

            assertThat(message).isEqualTo("error code: 404");
        }

        @Test
        @DisplayName("unifies both branches to same type")
        void unifiesTypes() {
            Result<String, String> success = Result.ok("hello");
            Result<String, String> failure = Result.err("error");

            String s1 = success.fold(String::toUpperCase, e -> "ERROR: " + e);
            String s2 = failure.fold(String::toUpperCase, e -> "ERROR: " + e);

            assertThat(s1).isEqualTo("HELLO");
            assertThat(s2).isEqualTo("ERROR: error");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Railway Chaining — Javadoc example verification
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Railway Chaining — Javadoc examples")
    class RailwayChaining {

        @Test
        @DisplayName("validates and transforms number string")
        void validateAndTransformNumber(DtrContext ctx) {
            ctx.sayNextSection("Railway Chaining: Multi-step transformation pipeline");
            ctx.say(
                    """
                    Railway-oriented programming eliminates nested if-statements. Each operation either
                    continues on the success track or short-circuits to the error track. The code reads
                    left-to-right like a story, with error handling woven through naturally.
                    """);

            ctx.sayCode(
                    """
                String result = Result.of(() -> "  42  ")
                    .map(String::strip)                    // "42"
                    .flatMap(s -> s.matches("\\d+")
                        ? Result.ok(Integer.parseInt(s))  // 42
                        : Result.err("not a number"))
                    .map(n -> n * 2)                       // 84
                    .fold(n -> "result=" + n, e -> "error=" + e);

                // Result: "result=84"
                """,
                    "java");

            ctx.sayMermaid(
                    """
                    graph LR
                    A[Start: 42 ] --> B[strip: 42]
                    B --> C{matches digit?}
                    C -->|Yes| D[parseInt: 42]
                    C -->|No| E[Err: not a number]
                    D --> F[map x2: 84]
                    E --> G[short-circuit]
                    F --> H[fold: result=84]
                    G --> I[fold: error=not a number]

                    style A fill:#90EE90
                    style B fill:#90EE90
                    style D fill:#90EE90
                    style F fill:#90EE90
                    style H fill:#90EE90
                    style E fill:#FFB6C1
                    style G fill:#FFB6C1
                    style I fill:#FFB6C1
                    """);

            String result =
                    Result.of(() -> "  42  ")
                            .map(String::strip)
                            .flatMap(
                                    s ->
                                            s.matches("\\d+")
                                                    ? Result.ok(Integer.parseInt(s))
                                                    : Result.err("not a number"))
                            .map(n -> n * 2)
                            .fold(n -> "result=" + n, e -> "error=" + e);

            assertThat(result).isEqualTo("result=84");
        }

        @Test
        @DisplayName("short-circuits on validation failure")
        void shortCircuitsOnValidationFailure(DtrContext ctx) {
            ctx.say(
                    """
                    When validation fails, flatMap() returns an error and all subsequent map() operations
                    are skipped. The error "falls through" to fold(), which handles it. No try-catch,
                    no null checks — the railway handles it automatically.
                    """);

            ctx.sayCode(
                    """
                String result = Result.of(() -> "  abc  ")
                    .map(String::strip)                    // "abc"
                    .flatMap(s -> s.matches("\\d+")
                        ? Result.ok(Integer.parseInt(s))
                        : Result.err("not a number"))     // Err!
                    .map(n -> n * 2)                       // SKIPPED
                    .fold(n -> "result=" + n, e -> "error=" + e);

                // Result: "error=not a number"
                """,
                    "java");

            ctx.sayNote(
                    "The map(n -> n * 2) step NEVER executes. Once on the error track, "
                            + "subsequent operations automatically short-circuit. This is the key benefit: "
                            + "you can't forget to handle errors.");

            String result =
                    Result.of(() -> "  abc  ")
                            .map(String::strip)
                            .flatMap(
                                    s ->
                                            s.matches("\\d+")
                                                    ? Result.ok(Integer.parseInt(s))
                                                    : Result.err("not a number"))
                            .map(n -> n * 2) // skipped
                            .fold(n -> "result=" + n, e -> "error=" + e);

            assertThat(result).isEqualTo("error=not a number");
        }

        @Test
        @DisplayName("wraps throwing operation in Result.of")
        void wrapsThrowingOperation() {
            Result<Integer, Exception> result =
                    Result.of(
                            () -> {
                                if (true) throw new RuntimeException("intentional");
                                return 0;
                            });

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("multi-step pipeline with error recovery")
        void multiStepPipelineWithErrorRecovery() {
            record Order(String id, int quantity, double price) {}

            Result<Order, String> pipeline =
                    Result.<String, String>of(() -> "order-123")
                            .map(id -> new Order(id, 10, 99.99))
                            .flatMap(
                                    order ->
                                            order.quantity() > 0
                                                    ? Result.ok(order)
                                                    : Result.err("invalid quantity"))
                            .map(
                                    order ->
                                            new Order(
                                                    order.id(),
                                                    order.quantity() * 2,
                                                    order.price()));

            assertThat(pipeline.isSuccess()).isTrue();
            assertThat(pipeline.orElseThrow().quantity()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("peek(Consumer) — side effects on success track")
    class PeekOperation {

        @Test
        @DisplayName("applies action to success value")
        void appliesActionToSuccess(DtrContext ctx) {
            ctx.sayNextSection("Side Effects: peek() for logging and auditing");
            ctx.say(
                    """
                    peek() applies a side-effect (logging, metrics, auditing) on the success track
                    without changing the Result. If on the error track, peek() does nothing — the error
                    passes through unchanged. This keeps your railway clean while still allowing
                    observability.
                    """);

            ctx.sayCode(
                    """
                var log = new ArrayList<String>();
                Result<String, String> result = Result.<String, String>ok("hello")
                    .peek(log::add)         // Log the success value
                    .peek(v -> metrics.record("value_processed"));

                // On success track: log contains ["hello"]
                // On error track: log remains empty
                """,
                    "java");

            var log = new java.util.ArrayList<String>();
            Result<String, String> result = Result.<String, String>ok("hello").peek(log::add);

            assertThat(result.isSuccess()).isTrue();
            assertThat(log).containsExactly("hello");
        }

        @Test
        @DisplayName("returns same result after action")
        void returnsSameResultAfterAction() {
            Result<String, String> original = Result.ok("value");
            Result<String, String> peeked = original.peek(v -> {});

            assertThat(peeked).isSameAs(original);
        }

        @Test
        @DisplayName("does not apply action to failure")
        void doesNotApplyActionToFailure() {
            var log = new java.util.ArrayList<String>();
            Result<String, String> result = Result.<String, String>err("error").peek(log::add);

            assertThat(result.isError()).isTrue();
            assertThat(log).isEmpty();
        }

        @Test
        @DisplayName("returns same failure after peek")
        void returnsSameFailureAfterPeek() {
            Result<String, String> original = Result.err("error");
            Result<String, String> peeked = original.peek(v -> {});

            assertThat(peeked).isSameAs(original);
        }

        @Test
        @DisplayName("works with Success alias")
        void worksWithSuccessAlias() {
            var log = new java.util.ArrayList<Integer>();
            Result<Integer, String> result = Result.<Integer, String>success(42).peek(log::add);

            assertThat(result.isSuccess()).isTrue();
            assertThat(log).containsExactly(42);
        }

        @Test
        @DisplayName("works with Failure alias")
        void worksWithFailureAlias() {
            var log = new java.util.ArrayList<String>();
            Result<String, String> result = Result.<String, String>failure("error").peek(log::add);

            assertThat(result.isError()).isTrue();
            assertThat(log).isEmpty();
        }

        @Test
        @DisplayName("chains multiple peek operations on success")
        void chainsMultiplePeeks() {
            var log1 = new java.util.ArrayList<Integer>();
            var log2 = new java.util.ArrayList<Integer>();
            Result<Integer, String> result =
                    Result.<Integer, String>ok(42).peek(log1::add).peek(log2::add);

            assertThat(log1).containsExactly(42);
            assertThat(log2).containsExactly(42);
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("throws NullPointerException when action is null")
        void throwsNullPointerExceptionForNullAction() {
            Result<String, String> result = Result.ok("value");

            assertThatThrownBy(() -> result.peek(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("propagates exception thrown by action")
        void propagatesExceptionFromAction() {
            Result<String, String> result = Result.ok("value");

            assertThatThrownBy(
                            () ->
                                    result.peek(
                                            v -> {
                                                throw new IllegalStateException("action failed");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("action failed");
        }

        @Test
        @DisplayName("peek with fold in pipeline")
        void peekWithFoldInPipeline() {
            var log = new java.util.ArrayList<Integer>();
            String result =
                    Result.<Integer, String>ok(100)
                            .map(x -> x * 2)
                            .peek(log::add)
                            .fold(v -> "result=" + v, e -> "error=" + e);

            assertThat(result).isEqualTo("result=200");
            assertThat(log).containsExactly(200);
        }
    }

    @Nested
    @DisplayName("recover(Function) — error recovery on failure track")
    class RecoverOperation {

        @Test
        @DisplayName("does not apply handler to success")
        void doesNotApplyHandlerToSuccess(DtrContext ctx) {
            ctx.sayNextSection("Error Recovery: recover() handles failures gracefully");
            ctx.say(
                    """
                    recover() is the error-track equivalent of flatMap(). It only applies when the
                    Result is an error, allowing you to transform failures into successes or provide
                    fallback values. On the success track, recover() is a no-op.
                    """);

            ctx.sayCode(
                    """
                Result<String, String> result = Result.<String, String>ok("value")
                    .recover(e -> Result.ok("recovered"));

                // Success track: recover() does nothing
                // result is still Ok("value")

                Result<String, String> error = Result.<String, String>err("failed")
                    .recover(e -> Result.ok("recovered: " + e));

                // Error track: recover() transforms the error
                // error becomes Ok("recovered: failed")
                """,
                    "java");

            Result<String, String> result =
                    Result.<String, String>ok("value").recover(e -> Result.ok("recovered"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("value");
        }

        @Test
        @DisplayName("returns same success after recover")
        void returnsSameSuccessAfterRecover() {
            Result<String, String> original = Result.ok("value");
            Result<String, String> recovered = original.recover(e -> Result.ok("fallback"));

            assertThat(recovered).isSameAs(original);
        }

        @Test
        @DisplayName("applies handler to failure")
        void appliesHandlerToFailure() {
            Result<String, String> result =
                    Result.<String, String>err("error").recover(e -> Result.ok("recovered: " + e));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("recovered: error");
        }

        @Test
        @DisplayName("handler can return failure")
        void handlerCanReturnFailure() {
            Result<String, String> result =
                    Result.<String, String>err("error1").recover(e -> Result.err("error2"));

            assertThat(result.isError()).isTrue();
            switch (result) {
                case Result.Err<String, String>(var e) -> assertThat(e).isEqualTo("error2");
                default -> fail("Expected Err");
            }
        }

        @Test
        @DisplayName("works with Success alias")
        void worksWithSuccessAlias() {
            Result<String, String> result =
                    Result.<String, String>success("value").recover(e -> Result.ok("fallback"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("value");
        }

        @Test
        @DisplayName("works with Failure alias")
        void worksWithFailureAlias() {
            Result<String, String> result =
                    Result.<String, String>failure("error").recover(e -> Result.ok("recovered"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("recovered");
        }

        @Test
        @DisplayName("chains multiple recover operations")
        void chainsMultipleRecovers() {
            Result<String, String> result =
                    Result.<String, String>err("error1")
                            .recover(e -> Result.err("error2"))
                            .recover(e -> Result.ok("final"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("final");
        }

        @Test
        @DisplayName("throws NullPointerException when handler is null")
        void throwsNullPointerExceptionForNullHandler() {
            Result<String, String> result = Result.err("error");

            assertThatThrownBy(() -> result.recover(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("propagates exception thrown by handler")
        void propagatesExceptionFromHandler() {
            Result<String, String> result = Result.err("error");

            assertThatThrownBy(
                            () ->
                                    result.recover(
                                            e -> {
                                                throw new IllegalStateException("recovery failed");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("recovery failed");
        }

        @Test
        @DisplayName("recover with peek in pipeline")
        void recoverWithPeekInPipeline() {
            var log = new java.util.ArrayList<String>();
            String result =
                    Result.<String, String>err("initial error")
                            .recover(e -> Result.ok("recovered"))
                            .peek(log::add)
                            .fold(v -> "success=" + v, e -> "failed=" + e);

            assertThat(result).isEqualTo("success=recovered");
            assertThat(log).containsExactly("recovered");
        }

        @Test
        @DisplayName("recover with exception type handler")
        void recoverWithExceptionTypeHandler() {
            Exception originalError = new IllegalArgumentException("invalid input");
            Result<String, Exception> result =
                    Result.<String, Exception>err(originalError)
                            .recover(
                                    e -> {
                                        if (e instanceof IllegalArgumentException) {
                                            return Result.ok("handled: " + e.getMessage());
                                        }
                                        return Result.err(e);
                                    });

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("handled: invalid input");
        }
    }

    @Nested
    @DisplayName("peek() and recover() integration")
    class PeekAndRecoverIntegration {

        @Test
        @DisplayName("peek then recover on success")
        void peekThenRecoverOnSuccess(DtrContext ctx) {
            ctx.sayNextSection("Complete Pipeline: Combining all railway operations");
            ctx.say(
                    """
                    A production pipeline combines map(), flatMap(), peek(), and recover() to handle
                    validation, transformation, side effects, and error recovery — all without a single
                    try-catch block. The railway pattern makes error handling explicit and composable.
                    """);

            ctx.sayCode(
                    """
                record Order(String id, int quantity) {}

                String result = Result.<String, String>of(() -> "order-123")
                    .map(id -> new Order(id, 10))                    // Transform
                    .peek(order -> audit.log(order))                 // Side effect
                    .flatMap(order -> order.quantity() > 0           // Validate
                        ? Result.ok(order)
                        : Result.err("invalid quantity"))
                    .recover(e -> Result.ok(new Order("default", 0))) // Fallback
                    .fold(order -> "processed=" + order.id(), e -> "error=" + e);
                """,
                    "java");

            ctx.sayTable(
                    new String[][] {
                        {"Operation", "Track", "Purpose"},
                        {"map()", "Both", "Transforms success value, passes through errors"},
                        {"peek()", "Success only", "Side effects on success, no-op on errors"},
                        {
                            "flatMap()",
                            "Both",
                            "Chains fallible operations, short-circuits on errors"
                        },
                        {
                            "recover()",
                            "Error only",
                            "Transforms errors to successes, no-op on success"
                        },
                        {"fold()", "Both", "Eliminates Result type, handles both cases"}
                    });

            var log = new java.util.ArrayList<String>();
            Result<String, String> result =
                    Result.<String, String>ok("value")
                            .peek(log::add)
                            .recover(e -> Result.ok("fallback"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("value");
            assertThat(log).containsExactly("value");
        }

        @Test
        @DisplayName("recover then peek on failure")
        void recoverThenPeekOnFailure() {
            var log = new java.util.ArrayList<String>();
            Result<String, String> result =
                    Result.<String, String>err("error")
                            .recover(e -> Result.ok("recovered"))
                            .peek(log::add);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo("recovered");
            assertThat(log).containsExactly("recovered");
        }

        @Test
        @DisplayName("complete pipeline: map -> flatMap -> peek -> recover -> fold")
        void completePipeline() {
            record Order(String id, int quantity) {}

            String result =
                    Result.<String, String>of(() -> "order-123")
                            .map(id -> new Order(id, 10))
                            .peek(order -> System.out.println("Order created: " + order.id()))
                            .flatMap(
                                    order ->
                                            order.quantity() > 0
                                                    ? Result.ok(order)
                                                    : Result.err("invalid quantity"))
                            .recover(e -> Result.ok(new Order("default", 0)))
                            .fold(order -> "processed=" + order.id(), e -> "error=" + e);

            assertThat(result).isEqualTo("processed=order-123");
        }

        @Test
        @DisplayName("recovery with side effect using peek")
        void recoveryWithSideEffect() {
            var recoveredErrors = new java.util.ArrayList<String>();
            Result<Integer, String> result =
                    Result.<Integer, String>err("parse failed")
                            .recover(
                                    e -> {
                                        recoveredErrors.add("Caught: " + e);
                                        return Result.ok(0);
                                    })
                            .peek(v -> System.out.println("Final value: " + v));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(0);
            assertThat(recoveredErrors).containsExactly("Caught: parse failed");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Edge Cases
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Result vs Exceptions: Why Railway-Oriented?")
    class ResultVersusExceptions {

        @Test
        @DisplayName("Result makes error handling explicit and composable")
        void explicitErrorHandling(DtrContext ctx) {
            ctx.sayNextSection("Result vs Exceptions: Explicit Error Handling");
            ctx.say(
                    """
                    Exceptions are INVISIBLE in function signatures. You can't tell if a method
                    throws just by looking at its type. Result<T,E> makes error handling EXPLICIT —
                    the error type is part of the signature, forcing callers to handle both cases.
                    """);

            ctx.sayCode(
                    """
                // EXCEPTIONS: Error handling is implicit and invisible
                public Order processOrder(String id) throws ValidationException, SQLException {
                    // Which exceptions? Check Javadoc or runtime!
                }

                // RESULT: Error handling is explicit in the type
                public Result<Order, OrderError> processOrder(String id) {
                    // Return type tells you: success = Order, failure = OrderError
                }

                // With Result, you MUST handle both cases:
                Result<Order, OrderError> result = processOrder("order-123");
                String status = result.fold(
                    order -> "Order processed: " + order.id(),
                    error -> "Order failed: " + error.reason()
                );
                """,
                    "java");

            ctx.sayTable(
                    new String[][] {
                        {"Aspect", "Exceptions", "Result<T,E>"},
                        {"Visibility", "Invisible (throws clause)", "Explicit in type signature"},
                        {"Forgetting to handle", "Runtime crash", "Compiler forces handling"},
                        {
                            "Composition",
                            "Requires try-catch around each call",
                            "Chain with map/flatMap"
                        },
                        {"Control flow", "Non-local jump", "Explicit railway tracks"},
                        {"Type safety", "Catches any Throwable", "Typed errors (sealed hierarchy)"},
                        {"Functional style", "Breaks composition", "Natural for map/reduce"}
                    });

            ctx.sayNote(
                    "Joe Armstrong: 'In Erlang, we don't throw exceptions across process boundaries. "
                            + "We return {ok, Value} or {error, Reason}. This forces the caller to handle "
                            + "both cases explicitly.' Result brings this philosophy to Java 26.");
        }

        @Test
        @DisplayName("Result enables composition without nesting")
        void compositionWithoutNesting(DtrContext ctx) {
            ctx.say(
                    """
                    With exceptions, error handling creates deeply nested try-catch blocks. With Result,
                    you can chain operations flatly using map() and flatMap(). The error handling logic
                    is woven through naturally, without interrupting the happy path.
                    """);

            ctx.sayCode(
                    """
                // EXCEPTIONS: Nested try-catch pyramid of doom
                try {
                    var order = validateOrder(request);
                    try {
                        var priced = calculatePrice(order);
                        try {
                            var reserved = reserveInventory(priced);
                            return "success=" + reserved.id();
                        } catch (InventoryException e) {
                            return "error=out of stock";
                        }
                    } catch (PricingException e) {
                        return "error=invalid price";
                    }
                } catch (ValidationException e) {
                    return "error=invalid order";
                }

                // RESULT: Flat, composable pipeline
                String result = Result.of(() -> validateOrder(request))
                    .flatMap(order -> Result.of(() -> calculatePrice(order)))
                    .flatMap(priced -> Result.of(() -> reserveInventory(priced)))
                    .fold(
                        reserved -> "success=" + reserved.id(),
                        error -> "error=" + error.reason()
                    );
                """,
                    "java");

            ctx.sayNote(
                    "The Result version is not only flatter but also safer — you can't forget to "
                            + "handle any error case. The compiler ensures exhaustiveness through "
                            + "pattern matching.");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("null success value is allowed")
        void nullSuccessValue() {
            Result<String, Exception> result = Result.ok(null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isNull();
        }

        @Test
        @DisplayName("null error value is allowed")
        void nullErrorValue() {
            Result<String, String> result = Result.err(null);

            assertThat(result.isError()).isTrue();
            switch (result) {
                case Result.Err<String, String>(var e) -> assertThat(e).isNull();
                default -> fail("Expected Err");
            }
        }

        @Test
        @DisplayName("Success and Ok variants are distinct but both succeed")
        void successAndOkAreDistinct() {
            Result<String, String> ok = Result.ok("value");
            Result<String, String> success = Result.success("value");

            assertThat(ok).isInstanceOf(Result.Ok.class);
            assertThat(success).isInstanceOf(Result.Ok.class);
            assertThat(ok.isSuccess()).isTrue();
            assertThat(success.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Err and Failure variants are distinct but both fail")
        void errAndFailureAreDistinct() {
            Result<String, String> err = Result.err("error");
            Result<String, String> failure = Result.failure("error");

            assertThat(err).isInstanceOf(Result.Err.class);
            assertThat(failure).isInstanceOf(Result.Err.class);
            assertThat(err.isError()).isTrue();
            assertThat(failure.isError()).isTrue();
        }

        @Test
        @DisplayName("orElseThrow throws RuntimeException directly without wrapping")
        void orElseThrowThrowsRuntimeDirectly() {
            RuntimeException originalError = new IllegalStateException("boom");
            Result<String, RuntimeException> result = Result.err(originalError);

            assertThatThrownBy(result::orElseThrow)
                    .isSameAs(originalError)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("orElseThrow wraps non-Throwable errors in RuntimeException")
        void orElseThrowWrapsNonThrowable() {
            Result<String, String> result = Result.err("error message");

            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("error message")
                    .isNotInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("orElseThrow wraps checked exceptions in RuntimeException")
        void orElseThrowWrapsCheckedException() {
            Exception checked = new Exception("checked error");
            Result<String, Exception> result = Result.err(checked);

            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(Exception.class)
                    .hasMessageContaining("checked error");
        }
    }
}
