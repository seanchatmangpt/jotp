package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.testing.util.JotpTestHelper;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
@Timeout(10)
@DisplayName("JotpTestHelper")
class JotpTestHelperTest implements WithAssertions {
    // ── Reflection fixtures ───────────────────────────────────────────────────
    sealed interface Shape permits Shape.Circle, Shape.Rectangle, Shape.Triangle {
        record Circle(double radius) implements Shape {}
        record Rectangle(double width, double height) implements Shape {}
        record Triangle(double base, double height) implements Shape {}
    }
    // Non-sealed interface for contrast
    interface NonSealed {}
    record Point(int x, int y) {}
    record Named(String name, int age) {}
    // ── getTransitionVariants ─────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    @Nested
    @DisplayName("getTransitionVariants()")
    class GetTransitionVariants {
        @Test
        @DisplayName("sealed interface returns its permitted subclasses")
        void sealedInterfaceReturnsSubclasses() {
            var variants = JotpTestHelper.getTransitionVariants(Shape.class);
            assertThat(variants)
                    .hasSize(3)
                    .extracting(Class::getSimpleName)
                    .containsExactlyInAnyOrder("Circle", "Rectangle", "Triangle");
        }
        @DisplayName("non-sealed class returns empty array")
        void nonSealedClassReturnsEmpty() {
            var variants = JotpTestHelper.getTransitionVariants(String.class);
            assertThat(variants).isEmpty();
        @DisplayName("non-sealed interface returns empty array")
        void nonSealedInterfaceReturnsEmpty() {
            var variants = JotpTestHelper.getTransitionVariants(NonSealed.class);
        @DisplayName("returns Class array (not null)")
        void returnsNonNullArray() {
            assertThat(JotpTestHelper.getTransitionVariants(Object.class)).isNotNull();
        @DisplayName("record class returns empty array (records are not sealed unless declared)")
        void recordClassReturnsEmpty() {
            var variants = JotpTestHelper.getTransitionVariants(Point.class);
    // ── getRecordFieldValues ──────────────────────────────────────────────────
    @DisplayName("getRecordFieldValues()")
    class GetRecordFieldValues {
        @DisplayName("record returns all component values in declaration order")
        void recordReturnsComponentValues() {
            var point = new Point(3, 7);
            var values = JotpTestHelper.getRecordFieldValues(point);
            assertThat(values).containsExactly(3, 7);
        @DisplayName("record with String and int fields returns correct values")
        void recordWithMixedFields() {
            var named = new Named("Alice", 30);
            var values = JotpTestHelper.getRecordFieldValues(named);
            assertThat(values).containsExactly("Alice", 30);
        @DisplayName("non-record object returns empty array")
        void nonRecordReturnsEmpty() {
            var values = JotpTestHelper.getRecordFieldValues("plain string");
            assertThat(values).isEmpty();
        @DisplayName("non-record plain object returns empty array")
        void nonRecordPlainObjectReturnsEmpty() {
            var values = JotpTestHelper.getRecordFieldValues(new Object());
        @DisplayName("record component of sealed inner type returns correct value")
        void sealedInnerRecordValues() {
            var circle = new Shape.Circle(5.0);
            var values = JotpTestHelper.getRecordFieldValues(circle);
            assertThat(values).containsExactly(5.0);
    // ── Stub method contracts ─────────────────────────────────────────────────
    @DisplayName("Stub method contracts")
    class StubMethods {
        @DisplayName("getProcessState returns null (stub)")
        void getProcessStateReturnsNull() {
            assertThat(JotpTestHelper.getProcessState(null)).isNull();
            assertThat(JotpTestHelper.getProcessState(new Object())).isNull();
        @DisplayName("getMailboxSize returns 0 (stub)")
        void getMailboxSizeReturnsZero() {
            assertThat(JotpTestHelper.getMailboxSize(null)).isZero();
        @DisplayName("isProcessAlive returns true (stub)")
        void isProcessAliveReturnsTrue() {
            assertThat(JotpTestHelper.isProcessAlive(null)).isTrue();
            assertThat(JotpTestHelper.isProcessAlive(new Object())).isTrue();
        @DisplayName("getSupervisionTree returns empty map (stub)")
        void getSupervisionTreeReturnsEmptyMap() {
            assertThat(JotpTestHelper.getSupervisionTree(null)).isEmpty();
        @DisplayName("getCrashCount returns 0 (stub)")
        void getCrashCountReturnsZero() {
            assertThat(JotpTestHelper.getCrashCount(null)).isZero();
        @DisplayName("lookupProcess returns null (stub)")
        void lookupProcessReturnsNull() {
            assertThat(JotpTestHelper.lookupProcess("any-name")).isNull();
        @DisplayName("getRegisteredProcessNames returns empty list (stub)")
        void getRegisteredProcessNamesReturnsEmptyList() {
            assertThat(JotpTestHelper.getRegisteredProcessNames()).isEmpty();
        @DisplayName("isLinked returns false (stub)")
        void isLinkedReturnsFalse() {
            assertThat(JotpTestHelper.isLinked(null, null)).isFalse();
        @DisplayName("createMonitor returns non-null object (stub)")
        void createMonitorReturnsNonNull() {
            assertThat(JotpTestHelper.createMonitor(null)).isNotNull();
        @DisplayName("sendAfter returns non-null timer reference (stub)")
        void sendAfterReturnsNonNull() {
            var timerRef = JotpTestHelper.sendAfter(100, TimeUnit.MILLISECONDS, null, "msg");
            assertThat(timerRef).isNotNull();
        @DisplayName("registerProcess and unregisterProcess do not throw (stub)")
        void registerUnregisterDoNotThrow() {
            assertThatCode(() -> JotpTestHelper.registerProcess("test-proc", new Object()))
                    .doesNotThrowAnyException();
            assertThatCode(() -> JotpTestHelper.unregisterProcess("test-proc"))
        @DisplayName("cancelTimer does not throw (stub)")
        void cancelTimerDoesNotThrow() {
            assertThatCode(() -> JotpTestHelper.cancelTimer(new Object()))
        @DisplayName("recordStateTransition does not throw (stub)")
        void recordStateTransitionDoesNotThrow() {
            assertThatCode(() -> JotpTestHelper.recordStateTransition(null, "StateA", "StateB"))
    // ── awaitProcessTermination timeout contract ──────────────────────────────
    @DisplayName("awaitProcessTermination()")
    class AwaitProcessTermination {
        @DisplayName("times out when process remains alive (stub always returns true)")
        void timesOutWhenAlive() {
            // isProcessAlive stub always returns true → should timeout quickly
            assertThatThrownBy(
                            () ->
                                    JotpTestHelper.awaitProcessTermination(
                                            null, 50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("did not terminate");
}
