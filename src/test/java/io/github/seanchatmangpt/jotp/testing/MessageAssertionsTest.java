package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.testing.util.MessageAssertions;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
@Timeout(10)
@DisplayName("MessageAssertions")
class MessageAssertionsTest implements WithAssertions {
    // ── Test fixtures ─────────────────────────────────────────────────────────
    /** Plain record — fields are accessible via record components. */
    record MsgRecord(String correlationId, String priority, String type) {}
    /** Plain POJO with public fields — accessible via getDeclaredField. */
    static class MsgPojo {
        @BeforeEach
        void setUp() {
            ApplicationController.reset();
        }
        public final String correlationId;
        public final String priority;
        MsgPojo(String correlationId, String priority) {
            this.correlationId = correlationId;
            this.priority = priority;
    }
    // ── assertMessage factory ─────────────────────────────────────────────────
    @Nested
    @DisplayName("assertMessage()")
    class AssertMessageFactory {
        @Test
        @DisplayName("returns non-null MessageAssertions instance")
        void returnsNonNull() {
            var assertions = MessageAssertions.assertMessage(new MsgRecord("c", "HIGH", "T"));
            assertThat(assertions).isNotNull();
        @DisplayName("can chain calls fluently")
        void chainable() {
            var assertions = MessageAssertions.assertMessage(new MsgRecord("c1", "LOW", "T"));
            assertThat(assertions.hasCorrelationId("c1")).isSameAs(assertions);
    // ── isNotNull ─────────────────────────────────────────────────────────────
    @DisplayName("isNotNull()")
    class IsNotNull {
        @DisplayName("non-null message produces no failure")
        void nonNullPasses() {
            MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                    .isNotNull()
                    .assertSucceeds();
        @DisplayName("null message produces failure")
        void nullMessageFails() {
            var assertions = MessageAssertions.assertMessage(null).isNotNull();
            assertThatThrownBy(assertions::assertSucceeds)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("non-null");
    // ── hasCorrelationId ──────────────────────────────────────────────────────
    @DisplayName("hasCorrelationId()")
    class HasCorrelationId {
        @DisplayName("matching ID produces no failure on record")
        void matchingIdOnRecord() {
            MessageAssertions.assertMessage(new MsgRecord("corr-1", "HIGH", "T"))
                    .hasCorrelationId("corr-1")
        @DisplayName("matching ID produces no failure on POJO")
        void matchingIdOnPojo() {
            MessageAssertions.assertMessage(new MsgPojo("corr-2", "LOW"))
                    .hasCorrelationId("corr-2")
        @DisplayName("mismatched ID is recorded as failure")
        void mismatchedIdFails() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("actual", "LOW", "T"))
                            .hasCorrelationId("expected");
            assertThatThrownBy(assertions::assertSucceeds).isInstanceOf(AssertionError.class);
        @DisplayName("failure message contains expected and actual values")
        void failureMessageContainsValues() {
                    MessageAssertions.assertMessage(new MsgRecord("actual-id", "LOW", "T"))
                            .hasCorrelationId("expected-id");
            assertThat(assertions.getFailureSummary())
                    .contains("expected-id")
                    .contains("actual-id");
    // ── isPriority ────────────────────────────────────────────────────────────
    @DisplayName("isPriority()")
    class IsPriority {
        @DisplayName("matching priority passes on record")
        void matchingPriorityPasses() {
            MessageAssertions.assertMessage(new MsgRecord("c", "HIGH", "T"))
                    .isPriority("HIGH")
        @DisplayName("mismatched priority fails")
        void mismatchedPriorityFails() {
                    MessageAssertions.assertMessage(new MsgRecord("c", "LOW", "T"))
                            .isPriority("HIGH");
        @DisplayName("failure message references expected priority")
        void failureMessageContainsExpected() {
                            .isPriority("CRITICAL");
            assertThat(assertions.getFailureSummary()).contains("CRITICAL");
    // ── hasType ───────────────────────────────────────────────────────────────
    @DisplayName("hasType()")
    class HasType {
        @DisplayName("correct simple class name passes")
        void correctClassNamePasses() {
                    .hasType("MsgRecord")
        @DisplayName("wrong class name fails")
        void wrongClassNameFails() {
                    MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                            .hasType("WrongType");
        @DisplayName("POJO class name is detected correctly")
        void pojoClassNameDetected() {
            MessageAssertions.assertMessage(new MsgPojo("c", "H"))
                    .hasType("MsgPojo")
    // ── hasField ──────────────────────────────────────────────────────────────
    @DisplayName("hasField()")
    class HasField {
        @DisplayName("field with matching value passes on record")
        void fieldWithValueOnRecord() {
                    .hasField("priority", "HIGH")
        @DisplayName("field with matching value passes on POJO")
        void fieldWithValueOnPojo() {
            MessageAssertions.assertMessage(new MsgPojo("c", "MEDIUM"))
                    .hasField("priority", "MEDIUM")
        @DisplayName("field with wrong value fails")
        void fieldWithWrongValueFails() {
                            .hasField("priority", "HIGH");
        @DisplayName("existence check for present field passes on record")
        void existenceCheckPresentRecord() {
            // record component "correlationId" exists
                    .hasField("correlationId")
    // ── Multiple failures accumulated ─────────────────────────────────────────
    @DisplayName("Multiple failures")
    class MultipleFailures {
        @DisplayName("all failures are accumulated before assertSucceeds")
        void allFailuresAccumulated() {
                    MessageAssertions.assertMessage(new MsgRecord("actual-c", "actual-p", "T"))
                            .hasCorrelationId("expected-c")
                            .isPriority("expected-p")
            var summary = assertions.getFailureSummary();
            assertThat(summary).contains("expected-c").contains("expected-p").contains("WrongType");
        @DisplayName("assertSucceeds throws single AssertionError listing all failures")
        void assertSucceedsThrowsWithAllFailures() {
                    MessageAssertions.assertMessage(new MsgRecord("a", "LOW", "T"))
                            .hasCorrelationId("x")
                    .hasMessageContaining("Message assertions failed");
    // ── getFailureSummary ─────────────────────────────────────────────────────
    @DisplayName("getFailureSummary()")
    class GetFailureSummary {
        @DisplayName("returns 'No failures' when all assertions pass")
        void noFailuresSummary() {
                            .hasCorrelationId("c")
                            .isPriority("H");
            assertThat(assertions.getFailureSummary()).isEqualTo("No failures");
        @DisplayName("returns failure description when assertions fail")
        void failureSummaryDescribed() {
                    MessageAssertions.assertMessage(new MsgRecord("actual", "H", "T"))
            assertThat(assertions.getFailureSummary()).isNotEqualTo("No failures").isNotEmpty();
}
