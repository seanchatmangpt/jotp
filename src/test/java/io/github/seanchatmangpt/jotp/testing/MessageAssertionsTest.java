package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.testing.util.MessageAssertions;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
@DisplayName("MessageAssertions")
class MessageAssertionsTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    /** Plain record — fields are accessible via record components. */
    record MsgRecord(String correlationId, String priority, String type) {}

    /** Plain POJO with public fields — accessible via getDeclaredField. */
    static class MsgPojo {
        public final String correlationId;
        public final String priority;

        MsgPojo(String correlationId, String priority) {
            this.correlationId = correlationId;
            this.priority = priority;
        }
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
        }

        @Test
        @DisplayName("can chain calls fluently")
        void chainable() {
            var assertions = MessageAssertions.assertMessage(new MsgRecord("c1", "LOW", "T"));
            assertThat(assertions.hasCorrelationId("c1")).isSameAs(assertions);
        }
    }

    // ── isNotNull ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isNotNull()")
    class IsNotNull {

        @Test
        @DisplayName("non-null message produces no failure")
        void nonNullPasses() {
            MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                    .isNotNull()
                    .assertSucceeds();
        }

        @Test
        @DisplayName("null message produces failure")
        void nullMessageFails() {
            var assertions = MessageAssertions.assertMessage(null).isNotNull();
            assertThatThrownBy(assertions::assertSucceeds)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("non-null");
        }
    }

    // ── hasCorrelationId ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasCorrelationId()")
    class HasCorrelationId {

        @Test
        @DisplayName("matching ID produces no failure on record")
        void matchingIdOnRecord() {
            MessageAssertions.assertMessage(new MsgRecord("corr-1", "HIGH", "T"))
                    .hasCorrelationId("corr-1")
                    .assertSucceeds();
        }

        @Test
        @DisplayName("matching ID produces no failure on POJO")
        void matchingIdOnPojo() {
            MessageAssertions.assertMessage(new MsgPojo("corr-2", "LOW"))
                    .hasCorrelationId("corr-2")
                    .assertSucceeds();
        }

        @Test
        @DisplayName("mismatched ID is recorded as failure")
        void mismatchedIdFails() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("actual", "LOW", "T"))
                            .hasCorrelationId("expected");
            assertThatThrownBy(assertions::assertSucceeds).isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("failure message contains expected and actual values")
        void failureMessageContainsValues() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("actual-id", "LOW", "T"))
                            .hasCorrelationId("expected-id");

            assertThat(assertions.getFailureSummary())
                    .contains("expected-id")
                    .contains("actual-id");
        }
    }

    // ── isPriority ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isPriority()")
    class IsPriority {

        @Test
        @DisplayName("matching priority passes on record")
        void matchingPriorityPasses() {
            MessageAssertions.assertMessage(new MsgRecord("c", "HIGH", "T"))
                    .isPriority("HIGH")
                    .assertSucceeds();
        }

        @Test
        @DisplayName("mismatched priority fails")
        void mismatchedPriorityFails() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("c", "LOW", "T"))
                            .isPriority("HIGH");
            assertThatThrownBy(assertions::assertSucceeds).isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("failure message references expected priority")
        void failureMessageContainsExpected() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("c", "LOW", "T"))
                            .isPriority("CRITICAL");
            assertThat(assertions.getFailureSummary()).contains("CRITICAL");
        }
    }

    // ── hasType ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasType()")
    class HasType {

        @Test
        @DisplayName("correct simple class name passes")
        void correctClassNamePasses() {
            MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                    .hasType("MsgRecord")
                    .assertSucceeds();
        }

        @Test
        @DisplayName("wrong class name fails")
        void wrongClassNameFails() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                            .hasType("WrongType");
            assertThatThrownBy(assertions::assertSucceeds).isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("POJO class name is detected correctly")
        void pojoClassNameDetected() {
            MessageAssertions.assertMessage(new MsgPojo("c", "H"))
                    .hasType("MsgPojo")
                    .assertSucceeds();
        }
    }

    // ── hasField ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasField()")
    class HasField {

        @Test
        @DisplayName("field with matching value passes on record")
        void fieldWithValueOnRecord() {
            MessageAssertions.assertMessage(new MsgRecord("c", "HIGH", "T"))
                    .hasField("priority", "HIGH")
                    .assertSucceeds();
        }

        @Test
        @DisplayName("field with matching value passes on POJO")
        void fieldWithValueOnPojo() {
            MessageAssertions.assertMessage(new MsgPojo("c", "MEDIUM"))
                    .hasField("priority", "MEDIUM")
                    .assertSucceeds();
        }

        @Test
        @DisplayName("field with wrong value fails")
        void fieldWithWrongValueFails() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("c", "LOW", "T"))
                            .hasField("priority", "HIGH");
            assertThatThrownBy(assertions::assertSucceeds).isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("existence check for present field passes on record")
        void existenceCheckPresentRecord() {
            // record component "correlationId" exists
            MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                    .hasField("correlationId")
                    .assertSucceeds();
        }
    }

    // ── Multiple failures accumulated ─────────────────────────────────────────

    @Nested
    @DisplayName("Multiple failures")
    class MultipleFailures {

        @Test
        @DisplayName("all failures are accumulated before assertSucceeds")
        void allFailuresAccumulated() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("actual-c", "actual-p", "T"))
                            .hasCorrelationId("expected-c")
                            .isPriority("expected-p")
                            .hasType("WrongType");

            var summary = assertions.getFailureSummary();
            assertThat(summary).contains("expected-c").contains("expected-p").contains("WrongType");
        }

        @Test
        @DisplayName("assertSucceeds throws single AssertionError listing all failures")
        void assertSucceedsThrowsWithAllFailures() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("a", "LOW", "T"))
                            .hasCorrelationId("x")
                            .isPriority("HIGH");

            assertThatThrownBy(assertions::assertSucceeds)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Message assertions failed");
        }
    }

    // ── getFailureSummary ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getFailureSummary()")
    class GetFailureSummary {

        @Test
        @DisplayName("returns 'No failures' when all assertions pass")
        void noFailuresSummary() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("c", "H", "T"))
                            .hasCorrelationId("c")
                            .isPriority("H");
            assertThat(assertions.getFailureSummary()).isEqualTo("No failures");
        }

        @Test
        @DisplayName("returns failure description when assertions fail")
        void failureSummaryDescribed() {
            var assertions =
                    MessageAssertions.assertMessage(new MsgRecord("actual", "H", "T"))
                            .hasCorrelationId("expected");
            assertThat(assertions.getFailureSummary()).isNotEqualTo("No failures").isNotEmpty();
        }
    }
}
