package io.github.seanchatmangpt.jotp.dogfood.patterns;

import io.github.seanchatmangpt.jotp.ApplicationController;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/** Dogfood: tests for TextTransformStrategy generated from patterns/strategy-functional.tera. */
@DisplayName("TextTransformStrategy")
class TextTransformStrategyTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("UPPER_CASE transforms to uppercase")
    void upperCaseTransforms() {
        assertThat(TextTransformStrategy.UPPER_CASE.execute("hello")).isEqualTo("HELLO");
    @DisplayName("LOWER_CASE transforms to lowercase")
    void lowerCaseTransforms() {
        assertThat(TextTransformStrategy.LOWER_CASE.execute("HELLO")).isEqualTo("hello");
    @DisplayName("REVERSE reverses the string")
    void reverseTransforms() {
        assertThat(TextTransformStrategy.REVERSE.execute("hello")).isEqualTo("olleh");
    @DisplayName("TRIM strips whitespace")
    void trimTransforms() {
        assertThat(TextTransformStrategy.TRIM.execute("  hello  ")).isEqualTo("hello");
    @DisplayName("lookup finds registered strategies")
    void lookupFindsStrategies() {
        assertThat(TextTransformStrategy.lookup("upper_case")).isPresent();
        assertThat(TextTransformStrategy.lookup("UPPER_CASE")).isPresent();
        assertThat(TextTransformStrategy.lookup("unknown")).isEmpty();
    @DisplayName("execute runs strategy by name")
    void executeRunsByName() {
        assertThat(TextTransformStrategy.execute("reverse", "abc")).isEqualTo("cba");
    @DisplayName("execute throws for unknown strategy")
    void executeThrowsForUnknown() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TextTransformStrategy.execute("nope", "abc"))
                .withMessageContaining("Unknown strategy");
    @DisplayName("andThen chains strategies")
    void andThenChainsStrategies() {
        var trimThenUpper = TextTransformStrategy.TRIM.andThen(TextTransformStrategy.UPPER_CASE);
        assertThat(trimThenUpper.execute("  hello  ")).isEqualTo("HELLO");
    @DisplayName("compose chains strategies in reverse order")
    void composeChainsInReverse() {
        var upperThenReverse =
                TextTransformStrategy.REVERSE.compose(TextTransformStrategy.UPPER_CASE);
        assertThat(upperThenReverse.execute("hello")).isEqualTo("OLLEH");
    @DisplayName("strategies work as Function via apply")
    void strategiesWorkAsFunction() {
        var result =
                java.util.List.of("Hello", "World").stream()
                        .map(TextTransformStrategy.UPPER_CASE)
                        .toList();
        assertThat(result).containsExactly("HELLO", "WORLD");
}
