package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Result} — verifies Javadoc documentation matches actual behavior.
 *
 * <p>Tests the sealed Result interface for railway-oriented programming, matching the Erlang/OTP
 * pattern: {ok, Value} | {error, Reason}.
 */
@DisplayName("Result<T,E> railway-oriented programming")
class ResultTest implements WithAssertions {

    // ─────────────────────────────────────────────────────────────────────────────
    // Factory Methods — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Result.ok(value) — Erlang's {ok, Value}")
    class OkFactory {

        @Test
        @DisplayName("creates successful result carrying a value")
        void createsSuccessWithValue() {
            Result<String, Exception> result = Result.ok("hello");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isError()).isFalse();
            assertThat(result.isFailure()).isFalse();
            assertThat(result).isInstanceOf(Result.Ok.class);
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
        void transformsSuccessValue() {
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
        void chainsSuccess() {
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
        void appliesSuccessBranch() {
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
        void validateAndTransformNumber() {
            // From Javadoc: chained operations without nested if-statements
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
        void shortCircuitsOnValidationFailure() {
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
        void appliesActionToSuccess() {
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
        void doesNotApplyHandlerToSuccess() {
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
        void peekThenRecoverOnSuccess() {
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
