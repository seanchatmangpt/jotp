package io.github.seanchatmangpt.jotp.dogfood.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
class ResultRailwayTest implements WithAssertions {
    // ── Factory methods ───────────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    void success_isSuccess() {
        var r = ResultRailway.success("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
        assertThat(r).isInstanceOf(ResultRailway.Success.class);
    void failure_isFailure() {
        var r = ResultRailway.failure("error");
        assertThat(r.isFailure()).isTrue();
        assertThat(r.isSuccess()).isFalse();
        assertThat(r).isInstanceOf(ResultRailway.Failure.class);
    void of_wrapsSuccessfulSupplier() {
        var r = ResultRailway.of(() -> 42);
        assertThat(((ResultRailway.Success<Integer, ?>) r).value()).isEqualTo(42);
    void of_capturesException() {
        var r =
                ResultRailway.<Integer, RuntimeException>of(
                        () -> {
                            throw new IllegalStateException("boom");
                        });
        assertThat((Throwable) ((ResultRailway.Failure<Integer, ?>) r).error())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    // ── map ───────────────────────────────────────────────────────────────────
    void map_transformsSuccessValue() {
        var r = ResultRailway.<String, String>success("hello").map(String::toUpperCase);
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("HELLO");
    void map_passesFailureThrough() {
        var r = ResultRailway.<String, String>failure("err").map(String::toUpperCase);
        assertThat(((ResultRailway.Failure<?, String>) r).error()).isEqualTo("err");
    // ── mapError ─────────────────────────────────────────────────────────────
    void mapError_transformsFailureError() {
        var r = ResultRailway.<String, String>failure("err").mapError(String::length);
        assertThat(((ResultRailway.Failure<?, Integer>) r).error()).isEqualTo(3);
    void mapError_passesSuccessThrough() {
        var r = ResultRailway.<String, String>success("ok").mapError(String::length);
    // ── flatMap ───────────────────────────────────────────────────────────────
    void flatMap_chainsSuccess() {
                ResultRailway.<String, String>success("42")
                        .flatMap(s -> ResultRailway.success(Integer.parseInt(s)));
    void flatMap_shortCircuitsOnFailure() {
                ResultRailway.<String, String>failure("no")
    void flatMap_propagatesInnerFailure() {
                ResultRailway.<String, String>success("abc")
                        .flatMap(s -> ResultRailway.failure("inner error"));
        assertThat(((ResultRailway.Failure<?, String>) r).error()).isEqualTo("inner error");
    // ── fold ─────────────────────────────────────────────────────────────────
    void fold_appliesSuccessBranch() {
        var result = ResultRailway.<String, String>success("hello").fold(String::length, e -> -1);
        assertThat(result).isEqualTo(5);
    void fold_appliesFailureBranch() {
        var result = ResultRailway.<String, String>failure("err").fold(String::length, e -> -1);
        assertThat(result).isEqualTo(-1);
    // ── recover ───────────────────────────────────────────────────────────────
    void recover_returnsValueOnSuccess() {
        var r = ResultRailway.<String, String>success("ok").recover(e -> "fallback");
        assertThat(r).isEqualTo("ok");
    void recover_appliesFallbackOnFailure() {
        var r = ResultRailway.<String, String>failure("err").recover(e -> "fallback");
        assertThat(r).isEqualTo("fallback");
    // ── recoverWith ───────────────────────────────────────────────────────────
    void recoverWith_returnsSuccessAsIs() {
                ResultRailway.<String, String>success("ok")
                        .recoverWith(e -> ResultRailway.success("recovered"));
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("ok");
    void recoverWith_replacesFailureWithNewResult() {
                ResultRailway.<String, String>failure("err")
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("recovered");
    // ── peek / peekError ──────────────────────────────────────────────────────
    void peek_calledOnSuccess() {
        var called = new AtomicBoolean(false);
        ResultRailway.<String, String>success("ok").peek(v -> called.set(true));
        assertThat(called).isTrue();
    void peek_notCalledOnFailure() {
        ResultRailway.<String, String>failure("err").peek(v -> called.set(true));
        assertThat(called).isFalse();
    void peekError_calledOnFailure() {
        ResultRailway.<String, String>failure("err").peekError(e -> called.set(true));
    void peekError_notCalledOnSuccess() {
        ResultRailway.<String, String>success("ok").peekError(e -> called.set(true));
    void peek_returnsOriginalResult() {
        var r = ResultRailway.<String, String>success("ok");
        assertThat(r.peek(v -> {})).isSameAs(r);
    // ── orElse / orElseGet / orElseThrow ─────────────────────────────────────
    void orElse_returnsValueOnSuccess() {
        assertThat(ResultRailway.<String, String>success("ok").orElse("fallback")).isEqualTo("ok");
    void orElse_returnsFallbackOnFailure() {
        assertThat(ResultRailway.<String, String>failure("err").orElse("fallback"))
                .isEqualTo("fallback");
    void orElseGet_computesFallbackLazily() {
        var r = ResultRailway.<String, String>failure("err").orElseGet(() -> "computed");
        assertThat(r).isEqualTo("computed");
    void orElseThrow_returnsValueOnSuccess() {
        assertThat(ResultRailway.<String, String>success("ok").orElseThrow()).isEqualTo("ok");
    void orElseThrow_throwsRuntimeExceptionOnExceptionFailure() {
        var r = ResultRailway.<String, Exception>failure(new IllegalStateException("boom"));
        assertThatThrownBy(r::orElseThrow)
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    void orElseThrow_throwsForNonExceptionFailure() {
        var r = ResultRailway.<String, String>failure("error message");
                .hasMessageContaining("error message");
    // ── Railway chaining ──────────────────────────────────────────────────────
    void railwayChain_shortCircuitsOnFirstFailure() {
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
    void railwayChain_propagatesFailureToEnd() {
                        .flatMap(s -> ResultRailway.failure("not a number"))
                        .map(n -> n + "!") // should not run
                        .fold(v -> "ok", e -> "failed: " + e);
        assertThat(result).isEqualTo("failed: not a number");
}
