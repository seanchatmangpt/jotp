package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
@DisplayName("ComposedMessageProcessor Tests")
class ComposedMessageProcessorTest {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("should compose multiple routers sequentially")
    void testComposedRouters() {
        // Arrange
        ComposedMessageProcessor<String> processor =
                ComposedMessageProcessor.<String>compose(
                        (String msg) -> msg.trim(),
                        (String msg) -> msg.toUpperCase(),
                        (String msg) -> msg + "!");
        // Act
        String result = processor.apply("  hello world  ");
        // Assert
        assertThat(result).isEqualTo("HELLO WORLD!");
    @DisplayName("should apply filters and reject non-matching messages")
    void testFiltering() {
                ComposedMessageProcessor.<String>compose((String msg) -> msg.trim())
                        .thenFilter((String msg) -> !msg.isEmpty())
                        .thenFilter((String msg) -> msg.length() > 2);
        // Act & Assert
        assertThat(processor.apply("hi")).isNull(); // Too short
        assertThat(processor.apply("   ")).isNull(); // Empty after trim
        assertThat(processor.apply("hello")).isEqualTo("hello"); // Passes all filters
    @DisplayName("should support null messages in apply")
    void testNullHandling() {
                ComposedMessageProcessor.<String>compose((String msg) -> msg.toUpperCase());
        assertThat(processor.apply(null)).isNull();
    @DisplayName("should chain multiple processors with andThen")
    void testChaining() {
        ComposedMessageProcessor<String> first =
                        .thenFilter((String msg) -> !msg.isEmpty());
        ComposedMessageProcessor<String> second =
        ComposedMessageProcessor<String> combined = first.andThen(second);
        String result = combined.apply("  hello  ");
        assertThat(result).isEqualTo("HELLO");
    @DisplayName("should support side-effect observations with peek")
    void testPeek() {
        List<String> observed = new ArrayList<>();
                ComposedMessageProcessor.<String>compose((String msg) -> msg.toUpperCase())
                        .peek(observed::add)
                        .thenRoute((String msg) -> msg + "!");
        String result = processor.apply("hello");
        assertThat(result).isEqualTo("HELLO!");
        assertThat(observed).containsExactly("HELLO");
    @DisplayName("should support Optional wrapper with applyOptional")
    void testApplyOptional() {
                        .thenFilter((String msg) -> msg.length() > 3);
        Optional<String> result1 = processor.applyOptional("hello");
        Optional<String> result2 = processor.applyOptional("hi");
        assertThat(result1).contains("hello");
        assertThat(result2).isEmpty();
    @DisplayName("should execute routers before filters")
    void testRouterBeforeFilter() {
                                (String msg) -> msg.trim(), (String msg) -> msg.toUpperCase())
                        .thenFilter((String msg) -> msg.startsWith("HELLO"));
        assertThat(result).isEqualTo("HELLO WORLD");
    @DisplayName("should short-circuit on first failing filter")
    void testShortCircuitOnFilter() {
        List<String> trace = new ArrayList<>();
                                (String msg) -> {
                                    trace.add("router");
                                    return msg.toUpperCase();
                                })
                        .thenFilter(
                                    trace.add("filter1");
                                    return msg.contains("X");
                                    trace.add("filter2");
                                    return true;
                                });
        assertThat(result).isNull();
        assertThat(trace).containsExactly("router", "filter1");
        // filter2 never executes because filter1 rejected
    @DisplayName("should handle compose with null routers (skip them)")
    void testComposeWithNullRouters() {
                        (String msg) -> msg.trim(), null, (String msg) -> msg.toUpperCase());
        String result = processor.apply("  hello  ");
    @DisplayName("should transform complex objects through pipeline")
    void testComplexObjectTransformation() {
        record Order(String item, int quantity) {}
        ComposedMessageProcessor<Order> processor =
                ComposedMessageProcessor.<Order>compose(
                                (Order order) ->
                                        new Order(order.item().toUpperCase(), order.quantity()))
                        .thenFilter((Order order) -> order.quantity() > 0)
                        .thenRoute(
                                        new Order(
                                                order.item() + " [Q:" + order.quantity() + "]",
                                                order.quantity()));
        Order input = new Order("apple", 5);
        Order result = processor.apply(input);
        assertThat(result).isEqualTo(new Order("APPLE [Q:5]", 5));
    @DisplayName("should maintain message integrity through multi-stage pipeline")
    void testMultiStagePipeline() {
                                (String msg) -> msg.replaceAll("\\s+", " "), // Normalize spaces
                                (String msg) -> msg.trim() // Trim ends
                                )
                        .thenFilter((String msg) -> msg.length() >= 5) // Min length
                        .thenFilter((String msg) -> !msg.startsWith("ERROR")) // Reject errors
                        .thenRoute((String msg) -> "[PROCESSED] " + msg);
        assertThat(processor.apply("  hello   world  ")).isEqualTo("[PROCESSED] hello world");
        assertThat(processor.apply("ERROR: bad")).isNull(); // Starts with ERROR
    @DisplayName("should compose message routers for declarative DSL")
    void testDeclarativeDSL() {
        // Arrange: Build a message routing DSL
                                (String msg) -> msg.toLowerCase(), // Normalize case
                                (String msg) ->
                                        msg.replaceAll("[^a-z0-9 ]", "")) // Remove special chars
                        .thenFilter((String msg) -> !msg.isBlank()) // Reject blank
                        .thenRoute((String msg) -> "[ROUTED] " + msg);
        String result = processor.apply("Hello-World_123!");
        assertThat(result).isEqualTo("[ROUTED] helloworld123");
    @DisplayName("should allow thenRoute null (skip it)")
    void testThenRouteNull() {
                        .thenRoute(null) // Skip
    @DisplayName("should support stateful side effects via peek")
    void testStatefulPeek() {
        List<String> log = new ArrayList<>();
                        .peek((String msg) -> log.add("Step 1: " + msg))
                        .thenRoute((String msg) -> msg + "!")
                        .peek((String msg) -> log.add("Step 2: " + msg));
        processor.apply("hello");
        assertThat(log).containsExactly("Step 1: HELLO", "Step 2: HELLO!");
}
