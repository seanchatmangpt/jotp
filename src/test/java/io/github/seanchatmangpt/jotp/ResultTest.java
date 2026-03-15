package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
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
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
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
        @DisplayName("pattern matches with Ok record")
        void patternMatchesOkRecord() {
            Result<Integer, String> result = Result.ok(42);
            switch (result) {
                case Result.Ok<Integer, String>(var v) -> assertThat(v).isEqualTo(42);
                default -> fail("Expected Ok variant");
            }
        @DisplayName("returns value via orElseThrow")
        void returnsValueViaOrElseThrow() {
            Result<String, Exception> result = Result.ok("value");
            assertThat(result.orElseThrow()).isEqualTo("value");
    @DisplayName("Result.err(error) — Erlang's {error, Reason}")
    class ErrFactory {
        @DisplayName("creates failed result carrying an error")
        void createsFailureWithError() {
            Result<String, String> result = Result.err("something went wrong");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isError()).isTrue();
            assertThat(result.isFailure()).isTrue();
            assertThat(result).isInstanceOf(Result.Err.class);
        @DisplayName("pattern matches with Err record")
        void patternMatchesErrRecord() {
            Result<String, Integer> result = Result.err(404);
                case Result.Err<String, Integer>(var e) -> assertThat(e).isEqualTo(404);
                default -> fail("Expected Err variant");
        @DisplayName("throws on orElseThrow with string error")
        void throwsOnOrElseThrowWithStringError() {
            Result<String, String> result = Result.err("error message");
            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("error message");
        @DisplayName("throws on orElseThrow with RuntimeException error (direct throw)")
        void throwsOnOrElseThrowWithRuntimeException() {
            RuntimeException original = new IllegalStateException("boom");
            Result<String, RuntimeException> result = Result.err(original);
                    .isSameAs(original)
                    .isInstanceOf(IllegalStateException.class);
        @DisplayName("throws on orElseThrow with checked exception (wrapped)")
        void throwsOnOrElseThrowWithCheckedException() {
            Exception original = new Exception("checked");
            Result<String, Exception> result = Result.err(original);
                    .hasCauseInstanceOf(Exception.class);
    @DisplayName("Result.success(value) — alias for ok()")
    class SuccessFactory {
        @DisplayName("is equivalent to ok()")
        void equivalentToOk() {
            Result<String, Exception> r1 = Result.success("value");
            Result<String, Exception> r2 = Result.ok("value");
            assertThat(r1.isSuccess()).isTrue();
            assertThat(r2.isSuccess()).isTrue();
            assertThat(r1).isInstanceOf(Result.Ok.class);
            assertThat(r2).isInstanceOf(Result.Ok.class);
        @DisplayName("pattern matches with Success record")
        void patternMatchesSuccessRecord() {
            Result<Integer, String> result = Result.success(99);
                case Result.Ok<Integer, String>(var v) -> assertThat(v).isEqualTo(99);
                default -> fail("Expected Success variant");
    @DisplayName("Result.failure(error) — alias for err()")
    class FailureFactory {
        @DisplayName("is equivalent to err()")
        void equivalentToErr() {
            Result<String, String> r1 = Result.failure("error");
            Result<String, String> r2 = Result.err("error");
            assertThat(r1.isFailure()).isTrue();
            assertThat(r2.isFailure()).isTrue();
            assertThat(r1).isInstanceOf(Result.Err.class);
            assertThat(r2).isInstanceOf(Result.Err.class);
        @DisplayName("pattern matches with Failure record")
        void patternMatchesFailureRecord() {
            Result<String, String> result = Result.failure("failed");
                case Result.Err<String, String>(var e) -> assertThat(e).isEqualTo("failed");
                default -> fail("Expected Failure variant");
    @DisplayName("Result.of(Supplier) — wraps throwing operations")
    class OfFactory {
        @DisplayName("returns Ok when supplier succeeds")
        void returnsOkOnSuccess() {
            Result<Integer, Exception> result = Result.of(() -> 42);
            assertThat(result.orElseThrow()).isEqualTo(42);
        @DisplayName("returns Err when supplier throws")
        void returnsErrOnException() {
            Result<Integer, Exception> result =
                    Result.of(
                            () -> {
                                throw new IllegalStateException("boom");
                            });
        @DisplayName("captures exception as error value")
        void capturesExceptionAsError() {
            Result<String, Exception> result =
                                throw new IllegalArgumentException("invalid");
                case Result.Err<String, Exception>(var e) -> {
                    assertThat(e).isInstanceOf(IllegalArgumentException.class);
                    assertThat(e.getMessage()).isEqualTo("invalid");
                }
    // Railway Operations — documented in Javadoc
    @DisplayName("map(Function) — transforms success track")
    class MapOperation {
        @DisplayName("transforms success value")
        void transformsSuccessValue() {
            Result<Integer, String> result = Result.<Integer, String>ok(5).map(x -> x * 2);
            assertThat(result.orElseThrow()).isEqualTo(10);
        @DisplayName("short-circuits on failure — returns same error")
        void shortCircuitsOnFailure() {
            Result<Integer, String> original = Result.err("error");
            Result<Integer, String> mapped = original.map(x -> x * 2);
            assertThat(mapped.isError()).isTrue();
            assertThat(mapped).isInstanceOf(Result.Err.class);
        @DisplayName("preserves Err type on short-circuit")
        void preservesErrType() {
            Result<String, Integer> result =
                    Result.<String, Integer>err(404).map(String::toUpperCase);
        @DisplayName("preserves Failure type on short-circuit")
        void preservesFailureType() {
                    Result.<String, Integer>failure(500).map(String::toUpperCase);
        @DisplayName("chains multiple map operations")
        void chainsMultipleMaps() {
            Result<Integer, String> result =
                    Result.<Integer, String>ok(1).map(x -> x + 1).map(x -> x * 2).map(x -> x + 3);
            assertThat(result.orElseThrow()).isEqualTo(7); // ((1+1)*2)+3 = 7
    @DisplayName("flatMap(Function) — chains Result-returning operations")
    class FlatMapOperation {
        @DisplayName("chains success to next result")
        void chainsSuccess() {
                    Result.<String, String>ok("42")
                            .flatMap(
                                    s -> {
                                        try {
                                            return Result.ok(Integer.parseInt(s));
                                        } catch (NumberFormatException e) {
                                            return Result.err("not a number");
                                        }
                                    });
        @DisplayName("short-circuits on failure")
                    Result.<String, String>err("initial error")
                            .flatMap(s -> Result.ok(Integer.parseInt(s)));
        @DisplayName("propagates inner failure")
        void propagatesInnerFailure() {
                    Result.<String, String>ok("not-a-number")
                            .flatMap(s -> Result.err("parse error"));
                case Result.Err<Integer, String>(var e) -> assertThat(e).isEqualTo("parse error");
                default -> fail("Expected Err");
    @DisplayName("orElse(default) — provides fallback")
    class OrElseOperation {
        @DisplayName("returns success value")
        void returnsSuccessValue() {
            Result<String, Exception> result = Result.ok("actual");
            assertThat(result.orElse("fallback")).isEqualTo("actual");
        @DisplayName("returns default on failure")
        void returnsDefaultOnFailure() {
            Result<String, Exception> result = Result.err(new RuntimeException());
            assertThat(result.orElse("fallback")).isEqualTo("fallback");
    @DisplayName("fold(onSuccess, onError) — eliminates Result type")
    class FoldOperation {
        @DisplayName("applies onSuccess for success")
        void appliesSuccessBranch() {
            Result<String, Integer> result = Result.ok("hello");
            int length = result.fold(String::length, error -> -1);
            assertThat(length).isEqualTo(5);
        @DisplayName("applies onError for failure")
        void appliesErrorBranch() {
            String message =
                    result.fold(value -> "success: " + value, error -> "error code: " + error);
            assertThat(message).isEqualTo("error code: 404");
        @DisplayName("unifies both branches to same type")
        void unifiesTypes() {
            Result<String, String> success = Result.ok("hello");
            Result<String, String> failure = Result.err("error");
            String s1 = success.fold(String::toUpperCase, e -> "ERROR: " + e);
            String s2 = failure.fold(String::toUpperCase, e -> "ERROR: " + e);
            assertThat(s1).isEqualTo("HELLO");
            assertThat(s2).isEqualTo("ERROR: error");
    // Railway Chaining — Javadoc example verification
    @DisplayName("Railway Chaining — Javadoc examples")
    class RailwayChaining {
        @DisplayName("validates and transforms number string")
        void validateAndTransformNumber() {
            // From Javadoc: chained operations without nested if-statements
            String result =
                    Result.of(() -> "  42  ")
                            .map(String::strip)
                                    s ->
                                            s.matches("\\d+")
                                                    ? Result.ok(Integer.parseInt(s))
                                                    : Result.err("not a number"))
                            .map(n -> n * 2)
                            .fold(n -> "result=" + n, e -> "error=" + e);
            assertThat(result).isEqualTo("result=84");
        @DisplayName("short-circuits on validation failure")
        void shortCircuitsOnValidationFailure() {
                    Result.of(() -> "  abc  ")
                            .map(n -> n * 2) // skipped
            assertThat(result).isEqualTo("error=not a number");
        @DisplayName("wraps throwing operation in Result.of")
        void wrapsThrowingOperation() {
                                if (true) throw new RuntimeException("intentional");
                                return 0;
        @DisplayName("multi-step pipeline with error recovery")
        void multiStepPipelineWithErrorRecovery() {
            record Order(String id, int quantity, double price) {}
            Result<Order, String> pipeline =
                    Result.<String, String>of(() -> "order-123")
                            .map(id -> new Order(id, 10, 99.99))
                                    order ->
                                            order.quantity() > 0
                                                    ? Result.ok(order)
                                                    : Result.err("invalid quantity"))
                            .map(
                                            new Order(
                                                    order.id(),
                                                    order.quantity() * 2,
                                                    order.price()));
            assertThat(pipeline.isSuccess()).isTrue();
            assertThat(pipeline.orElseThrow().quantity()).isEqualTo(20);
    @DisplayName("peek(Consumer) — side effects on success track")
    class PeekOperation {
        @DisplayName("applies action to success value")
        void appliesActionToSuccess() {
            var log = new java.util.ArrayList<String>();
            Result<String, String> result = Result.<String, String>ok("hello").peek(log::add);
            assertThat(log).containsExactly("hello");
        @DisplayName("returns same result after action")
        void returnsSameResultAfterAction() {
            Result<String, String> original = Result.ok("value");
            Result<String, String> peeked = original.peek(v -> {});
            assertThat(peeked).isSameAs(original);
        @DisplayName("does not apply action to failure")
        void doesNotApplyActionToFailure() {
            Result<String, String> result = Result.<String, String>err("error").peek(log::add);
            assertThat(log).isEmpty();
        @DisplayName("returns same failure after peek")
        void returnsSameFailureAfterPeek() {
            Result<String, String> original = Result.err("error");
        @DisplayName("works with Success alias")
        void worksWithSuccessAlias() {
            var log = new java.util.ArrayList<Integer>();
            Result<Integer, String> result = Result.<Integer, String>success(42).peek(log::add);
            assertThat(log).containsExactly(42);
        @DisplayName("works with Failure alias")
        void worksWithFailureAlias() {
            Result<String, String> result = Result.<String, String>failure("error").peek(log::add);
        @DisplayName("chains multiple peek operations on success")
        void chainsMultiplePeeks() {
            var log1 = new java.util.ArrayList<Integer>();
            var log2 = new java.util.ArrayList<Integer>();
                    Result.<Integer, String>ok(42).peek(log1::add).peek(log2::add);
            assertThat(log1).containsExactly(42);
            assertThat(log2).containsExactly(42);
        @DisplayName("throws NullPointerException when action is null")
        void throwsNullPointerExceptionForNullAction() {
            Result<String, String> result = Result.ok("value");
            assertThatThrownBy(() -> result.peek(null)).isInstanceOf(NullPointerException.class);
        @DisplayName("propagates exception thrown by action")
        void propagatesExceptionFromAction() {
            assertThatThrownBy(
                            () ->
                                    result.peek(
                                            v -> {
                                                throw new IllegalStateException("action failed");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("action failed");
        @DisplayName("peek with fold in pipeline")
        void peekWithFoldInPipeline() {
                    Result.<Integer, String>ok(100)
                            .map(x -> x * 2)
                            .peek(log::add)
                            .fold(v -> "result=" + v, e -> "error=" + e);
            assertThat(result).isEqualTo("result=200");
            assertThat(log).containsExactly(200);
    @DisplayName("recover(Function) — error recovery on failure track")
    class RecoverOperation {
        @DisplayName("does not apply handler to success")
        void doesNotApplyHandlerToSuccess() {
            Result<String, String> result =
                    Result.<String, String>ok("value").recover(e -> Result.ok("recovered"));
        @DisplayName("returns same success after recover")
        void returnsSameSuccessAfterRecover() {
            Result<String, String> recovered = original.recover(e -> Result.ok("fallback"));
            assertThat(recovered).isSameAs(original);
        @DisplayName("applies handler to failure")
        void appliesHandlerToFailure() {
                    Result.<String, String>err("error").recover(e -> Result.ok("recovered: " + e));
            assertThat(result.orElseThrow()).isEqualTo("recovered: error");
        @DisplayName("handler can return failure")
        void handlerCanReturnFailure() {
                    Result.<String, String>err("error1").recover(e -> Result.err("error2"));
                case Result.Err<String, String>(var e) -> assertThat(e).isEqualTo("error2");
                    Result.<String, String>success("value").recover(e -> Result.ok("fallback"));
                    Result.<String, String>failure("error").recover(e -> Result.ok("recovered"));
            assertThat(result.orElseThrow()).isEqualTo("recovered");
        @DisplayName("chains multiple recover operations")
        void chainsMultipleRecovers() {
                    Result.<String, String>err("error1")
                            .recover(e -> Result.err("error2"))
                            .recover(e -> Result.ok("final"));
            assertThat(result.orElseThrow()).isEqualTo("final");
        @DisplayName("throws NullPointerException when handler is null")
        void throwsNullPointerExceptionForNullHandler() {
            Result<String, String> result = Result.err("error");
            assertThatThrownBy(() -> result.recover(null)).isInstanceOf(NullPointerException.class);
        @DisplayName("propagates exception thrown by handler")
        void propagatesExceptionFromHandler() {
                                    result.recover(
                                            e -> {
                                                throw new IllegalStateException("recovery failed");
                    .hasMessage("recovery failed");
        @DisplayName("recover with peek in pipeline")
        void recoverWithPeekInPipeline() {
                            .recover(e -> Result.ok("recovered"))
                            .fold(v -> "success=" + v, e -> "failed=" + e);
            assertThat(result).isEqualTo("success=recovered");
            assertThat(log).containsExactly("recovered");
        @DisplayName("recover with exception type handler")
        void recoverWithExceptionTypeHandler() {
            Exception originalError = new IllegalArgumentException("invalid input");
                    Result.<String, Exception>err(originalError)
                            .recover(
                                    e -> {
                                        if (e instanceof IllegalArgumentException) {
                                            return Result.ok("handled: " + e.getMessage());
                                        return Result.err(e);
            assertThat(result.orElseThrow()).isEqualTo("handled: invalid input");
    @DisplayName("peek() and recover() integration")
    class PeekAndRecoverIntegration {
        @DisplayName("peek then recover on success")
        void peekThenRecoverOnSuccess() {
                    Result.<String, String>ok("value")
                            .recover(e -> Result.ok("fallback"));
            assertThat(log).containsExactly("value");
        @DisplayName("recover then peek on failure")
        void recoverThenPeekOnFailure() {
                    Result.<String, String>err("error")
                            .peek(log::add);
        @DisplayName("complete pipeline: map -> flatMap -> peek -> recover -> fold")
        void completePipeline() {
            record Order(String id, int quantity) {}
                            .map(id -> new Order(id, 10))
                            .peek(order -> System.out.println("Order created: " + order.id()))
                            .recover(e -> Result.ok(new Order("default", 0)))
                            .fold(order -> "processed=" + order.id(), e -> "error=" + e);
            assertThat(result).isEqualTo("processed=order-123");
        @DisplayName("recovery with side effect using peek")
        void recoveryWithSideEffect() {
            var recoveredErrors = new java.util.ArrayList<String>();
                    Result.<Integer, String>err("parse failed")
                                        recoveredErrors.add("Caught: " + e);
                                        return Result.ok(0);
                                    })
                            .peek(v -> System.out.println("Final value: " + v));
            assertThat(result.orElseThrow()).isEqualTo(0);
            assertThat(recoveredErrors).containsExactly("Caught: parse failed");
    // Edge Cases
    @DisplayName("Edge Cases")
    class EdgeCases {
        @DisplayName("null success value is allowed")
        void nullSuccessValue() {
            Result<String, Exception> result = Result.ok(null);
            assertThat(result.orElseThrow()).isNull();
        @DisplayName("null error value is allowed")
        void nullErrorValue() {
            Result<String, String> result = Result.err(null);
                case Result.Err<String, String>(var e) -> assertThat(e).isNull();
        @DisplayName("Success and Ok variants are distinct but both succeed")
        void successAndOkAreDistinct() {
            Result<String, String> ok = Result.ok("value");
            Result<String, String> success = Result.success("value");
            assertThat(ok).isInstanceOf(Result.Ok.class);
            assertThat(success).isInstanceOf(Result.Ok.class);
            assertThat(ok.isSuccess()).isTrue();
            assertThat(success.isSuccess()).isTrue();
        @DisplayName("Err and Failure variants are distinct but both fail")
        void errAndFailureAreDistinct() {
            Result<String, String> err = Result.err("error");
            Result<String, String> failure = Result.failure("error");
            assertThat(err).isInstanceOf(Result.Err.class);
            assertThat(failure).isInstanceOf(Result.Err.class);
            assertThat(err.isError()).isTrue();
            assertThat(failure.isError()).isTrue();
        @DisplayName("orElseThrow throws RuntimeException directly without wrapping")
        void orElseThrowThrowsRuntimeDirectly() {
            RuntimeException originalError = new IllegalStateException("boom");
            Result<String, RuntimeException> result = Result.err(originalError);
                    .isSameAs(originalError)
        @DisplayName("orElseThrow wraps non-Throwable errors in RuntimeException")
        void orElseThrowWrapsNonThrowable() {
                    .hasMessageContaining("error message")
                    .isNotInstanceOf(IllegalStateException.class);
        @DisplayName("orElseThrow wraps checked exceptions in RuntimeException")
        void orElseThrowWrapsCheckedException() {
            Exception checked = new Exception("checked error");
            Result<String, Exception> result = Result.err(checked);
                    .hasCauseInstanceOf(Exception.class)
                    .hasMessageContaining("checked error");
}
