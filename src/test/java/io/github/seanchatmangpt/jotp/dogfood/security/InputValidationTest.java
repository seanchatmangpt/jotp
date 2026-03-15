package io.github.seanchatmangpt.jotp.dogfood.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
class InputValidationTest implements WithAssertions {
    // ── Preconditions ────────────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    void requireNonBlank_passesForValidString() {
        assertThat(InputValidation.Preconditions.requireNonBlank("hello", "field"))
                .isEqualTo("hello");
    void requireNonBlank_throwsForBlank() {
        assertThatThrownBy(() -> InputValidation.Preconditions.requireNonBlank("  ", "field"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field");
    void requireNonBlank_throwsForNull() {
        assertThatThrownBy(() -> InputValidation.Preconditions.requireNonBlank(null, "field"))
                .isInstanceOf(NullPointerException.class);
    void requireInRange_passesForBoundaryValues() {
        assertThat(InputValidation.Preconditions.requireInRange(0, 0, 100, "x")).isZero();
        assertThat(InputValidation.Preconditions.requireInRange(100, 0, 100, "x")).isEqualTo(100);
    void requireInRange_throwsOutOfRange() {
        assertThatThrownBy(() -> InputValidation.Preconditions.requireInRange(-1, 0, 100, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InputValidation.Preconditions.requireInRange(101, 0, 100, "x"))
    void requirePositive_passesForPositive() {
        assertThat(InputValidation.Preconditions.requirePositive(1, "n")).isEqualTo(1);
    void requirePositive_throwsForZeroOrNegative() {
        assertThatThrownBy(() -> InputValidation.Preconditions.requirePositive(0, "n"))
        assertThatThrownBy(() -> InputValidation.Preconditions.requirePositive(-5, "n"))
    void requireNonNegative_passesForZero() {
        assertThat(InputValidation.Preconditions.requireNonNegative(0L, "n")).isZero();
    void requireNonEmpty_passesForNonEmptyList() {
        var list = List.of("a");
        assertThat(InputValidation.Preconditions.requireNonEmpty(list, "items")).isSameAs(list);
    void requireNonEmpty_throwsForEmpty() {
        assertThatThrownBy(() -> InputValidation.Preconditions.requireNonEmpty(List.of(), "items"))
    // ── NonBlankString ───────────────────────────────────────────────────────
    void nonBlankString_stripsWhitespace() {
        assertThat(new InputValidation.NonBlankString("  hello  ").value()).isEqualTo("hello");
    void nonBlankString_throwsForBlank() {
        assertThatThrownBy(() -> new InputValidation.NonBlankString("   "))
    void nonBlankString_throwsForNull() {
        assertThatThrownBy(() -> new InputValidation.NonBlankString(null))
    // ── Email ────────────────────────────────────────────────────────────────
    void email_normalizesToLowercase() {
        assertThat(new InputValidation.Email("User@Example.COM").value())
                .isEqualTo("user@example.com");
    void email_throwsForInvalidFormat() {
        assertThatThrownBy(() -> new InputValidation.Email("not-an-email"))
        assertThatThrownBy(() -> new InputValidation.Email("@nodomain"))
    // ── Age ──────────────────────────────────────────────────────────────────
    void age_acceptsBoundaryValues() {
        assertThat(new InputValidation.Age(0).value()).isZero();
        assertThat(new InputValidation.Age(150).value()).isEqualTo(150);
    void age_throwsOutOfRange() {
        assertThatThrownBy(() -> new InputValidation.Age(-1))
        assertThatThrownBy(() -> new InputValidation.Age(151))
    // ── PositiveAmount ───────────────────────────────────────────────────────
    void positiveAmount_acceptsPositive() {
        assertThat(new InputValidation.PositiveAmount(0.01).value()).isEqualTo(0.01);
    void positiveAmount_throwsForNonPositive() {
        assertThatThrownBy(() -> new InputValidation.PositiveAmount(0.0))
        assertThatThrownBy(() -> new InputValidation.PositiveAmount(-1.0))
    void positiveAmount_throwsForNaN() {
        assertThatThrownBy(() -> new InputValidation.PositiveAmount(Double.NaN))
    void positiveAmount_throwsForInfinity() {
        assertThatThrownBy(() -> new InputValidation.PositiveAmount(Double.POSITIVE_INFINITY))
    // ── Validator / ValidationResult ─────────────────────────────────────────
    void validator_valid_whenAllChecksPass() {
        var result =
                InputValidation.Validator.<String>of()
                        .check("field", true, "must be true")
                        .validate(() -> "ok");
        assertThat(result.isValid()).isTrue();
        assertThat(result).isInstanceOf(InputValidation.ValidationResult.Valid.class);
        assertThat(((InputValidation.ValidationResult.Valid<String>) result).value())
                .isEqualTo("ok");
    void validator_invalid_whenAnyCheckFails() {
                        .check("name", false, "must not be empty")
                        .check("age", true, "ok")
                        .validate(() -> "unreachable");
        assertThat(result.isValid()).isFalse();
        assertThat(result).isInstanceOf(InputValidation.ValidationResult.Invalid.class);
        var errors = ((InputValidation.ValidationResult.Invalid<String>) result).errors();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).field()).isEqualTo("name");
    void validator_accumulatesMultipleErrors() {
                        .requireNonBlank("name", "")
                        .requireNonNull("email", null)
                        .check("age", false, "invalid age")
        assertThat(errors).hasSize(3);
    void validator_validateOrThrow_throwsOnErrors() {
        assertThatThrownBy(
                        () ->
                                InputValidation.Validator.<String>of()
                                        .check("x", false, "bad")
                                        .validateOrThrow(() -> "ok"))
                .hasMessageContaining("Validation failed");
    void validationResult_map_transformsValidValue() {
                InputValidation.Validator.<String>of().check("x", true, "ok").validate(() -> "42");
        var mapped = result.map(Integer::parseInt);
        assertThat(mapped.isValid()).isTrue();
    void validationResult_fold_appliesCorrectBranch() {
        var valid = new InputValidation.ValidationResult.Valid<>("hello");
        var folded = valid.fold(String::length, errors -> -1);
        assertThat(folded).isEqualTo(5);
        var invalid =
                new InputValidation.ValidationResult.Invalid<String>(
                        List.of(new InputValidation.ValidationError("f", "bad")));
        var foldedErr = invalid.fold(String::length, errors -> errors.size());
        assertThat(foldedErr).isEqualTo(1);
    // ── validateRegistration integration ─────────────────────────────────────
    void validateRegistration_valid_forGoodInput() {
        var result = InputValidation.validateRegistration("Alice", "alice@example.com", 30);
        var user = ((InputValidation.ValidationResult.Valid<InputValidation.User>) result).value();
        assertThat(user.name().value()).isEqualTo("Alice");
        assertThat(user.email().value()).isEqualTo("alice@example.com");
        assertThat(user.age().value()).isEqualTo(30);
    void validateRegistration_invalid_forBadEmail() {
        var result = InputValidation.validateRegistration("Bob", "not-email", 25);
    void validateRegistration_invalid_forBlankName() {
        var result = InputValidation.validateRegistration("  ", "bob@example.com", 25);
    void validateRegistration_accumulatesBothErrors() {
        var result = InputValidation.validateRegistration("", "bad-email", 200);
        var errors = ((InputValidation.ValidationResult.Invalid<?>) result).errors();
        assertThat(errors.size()).isGreaterThanOrEqualTo(2);
    // ── ValidationError ───────────────────────────────────────────────────────
    void validationError_toStringContainsFieldAndMessage() {
        var err = new InputValidation.ValidationError("email", "invalid format");
        assertThat(err.toString()).contains("email").contains("invalid format");
    void validationError_throwsForBlankField() {
        assertThatThrownBy(() -> new InputValidation.ValidationError("", "msg"))
}
