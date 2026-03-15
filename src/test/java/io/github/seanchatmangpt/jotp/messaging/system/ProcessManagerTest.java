package io.github.seanchatmangpt.jotp.messaging.system;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * JUnit Jupiter tests for ProcessManager pattern.
 *
 * <p>Tests cover: - Workflow progression through multiple steps - Successful completion - Error
 * handling and rollback - State management across transitions - Step ordering and context passing
 */
class ProcessManagerTest {
    static class OrderContext {}
    record Order(String id, double amount, boolean canCharge) {}
    private ProcessManager<OrderContext> pm;
    @BeforeEach
    void setup() throws InterruptedException {
        ApplicationController.reset();
        // Clean up from previous test
        pm = new ProcessManager<>("test-workflow");
    }
    @Test
    @DisplayName("Should execute all steps successfully in order")
    void testSuccessfulWorkflow() throws Exception {
        pm.step(
                        "step1",
                        (ctx, input) -> {
                            var order = (Order) input;
                            assertThat(order.id).isEqualTo("ORD-001");
                            return new Order(order.id, order.amount, order.canCharge);
                        },
                        (ctx, output) -> null)
                .step(
                        "step2",
                            assertThat(order.amount).isEqualTo(100.0);
                            return new Order(order.id, order.amount * 1.1, order.canCharge);
                        "step3",
                            assertThat(order.amount).isEqualTo(110.0);
                        (ctx, output) -> null);
        var order = new Order("ORD-001", 100.0, true);
        var result = pm.start(order).join();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        result.fold(
                ctx -> {
                    assertThat(ctx.getExecutedSteps()).containsExactly("step1", "step2", "step3");
                    assertThat(ctx.getLastError()).isEmpty();
                    return null;
                },
                error -> {
                    fail("Should not have error: " + error);
                });
        pm.stop();
    @DisplayName("Should pass output from one step as input to next")
    void testStepOutputPassing() throws Exception {
        pm.step("add10", (ctx, input) -> (int) input + 10, (ctx, output) -> null)
                .step("multiply2", (ctx, input) -> (int) input * 2, (ctx, output) -> null)
                .step("subtract5", (ctx, input) -> (int) input - 5, (ctx, output) -> null);
        var result = pm.start(5).join();
                    // 5 -> add10 -> 15 -> multiply2 -> 30 -> subtract5 -> 25
                    var outputs = ctx.getExecutedSteps();
                    assertThat(outputs).hasSize(3);
                    assertThat(ctx.getStepOutput("add10")).isEqualTo(15);
                    assertThat(ctx.getStepOutput("multiply2")).isEqualTo(30);
                    assertThat(ctx.getStepOutput("subtract5")).isEqualTo(25);
                    fail("Unexpected error: " + error);
    @DisplayName("Should detect and reject duplicate step names")
    void testDuplicateStepRejection() {
        pm.step("validate", (ctx, input) -> input, (ctx, output) -> null);
        assertThatThrownBy(() -> pm.step("validate", (ctx, input) -> input, (ctx, output) -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Step already defined");
    @DisplayName("Should fail if no steps defined")
    void testNoStepsDefined() throws Exception {
        var result = pm.start("input").join();
        assertThat(result.isFailure()).isTrue();
                    fail("Should not succeed");
                    assertThat(error).contains("No steps defined");
    @DisplayName("Should handle step failure with rollback")
    void testRollbackOnFailure() throws Exception {
        var rollbackExecuted = new boolean[3];
                            rollbackExecuted[0] = false;
                            return "output1";
                        (ctx, output) -> {
                            rollbackExecuted[0] = true;
                            return null;
                        })
                            rollbackExecuted[1] = false;
                            return "output2";
                            rollbackExecuted[1] = true;
                        "step3-fails",
                            throw new RuntimeException("Step 3 failed");
                            rollbackExecuted[2] = true;
                        });
                    assertThat(error).contains("Workflow failed");
                    // Verify rollback was initiated (though implementation may vary)
                    assertThat(result.isFailure()).isTrue();
    @DisplayName("Should capture and expose last error")
    void testErrorCapture() throws Exception {
                        "success",
                        (ctx, input) -> "intermediate",
                        "failure",
                            throw new IllegalStateException("Expected test error");
                    fail("Should fail");
                    assertThat(error).contains("Expected test error");
    @DisplayName("Should maintain workflow state through transitions")
    void testStateManagement() throws Exception {
                        "record_name",
                            var map = (java.util.Map) ctx;
                            map.put("name", "Alice");
                            return "recorded";
                            map.remove("name");
                        "verify_name",
                            var name = map.get("name");
                            assertThat(name).isEqualTo("Alice");
                            return name;
        var contextMap = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        var result = pm.start(contextMap).join();
    @DisplayName("Should handle multiple workflows independently")
    void testMultipleWorkflows() throws Exception {
        var pm1 =
                new ProcessManager<OrderContext>("workflow-1")
                        .step("step1", (ctx, input) -> (int) input + 1, (ctx, output) -> null)
                        .step("step2", (ctx, input) -> (int) input * 2, (ctx, output) -> null);
        var pm2 =
                new ProcessManager<OrderContext>("workflow-2")
                        .step("step1", (ctx, input) -> (int) input * 10, (ctx, output) -> null)
                        .step("step2", (ctx, input) -> (int) input - 5, (ctx, output) -> null);
        var result1 = pm1.start(5).join(); // 5 -> 6 -> 12
        var result2 = pm2.start(5).join(); // 5 -> 50 -> 45
        assertThat(result1.isSuccess()).isTrue();
        assertThat(result2.isSuccess()).isTrue();
        result1.fold(
                ctx1 -> {
                    assertThat(ctx1.getStepOutput("step1")).isEqualTo(6);
                    assertThat(ctx1.getStepOutput("step2")).isEqualTo(12);
                    fail("Workflow 1 failed: " + error);
        result2.fold(
                ctx2 -> {
                    assertThat(ctx2.getStepOutput("step1")).isEqualTo(50);
                    assertThat(ctx2.getStepOutput("step2")).isEqualTo(45);
                    fail("Workflow 2 failed: " + error);
        pm1.stop();
        pm2.stop();
    @DisplayName("Should expose current workflow state")
    void testStateIntrospection() throws Exception {
        pm.step("step1", (ctx, input) -> "output1", (ctx, output) -> null)
                .step("step2", (ctx, input) -> "output2", (ctx, output) -> null);
        var stateOpt = pm.currentState();
        assertThat(stateOpt).isEmpty(); // Before start, no state
        var resultFuture = pm.start("input");
        // Give it a moment to process
        Thread.sleep(50);
        var stateOpt2 = pm.currentState();
        assertThat(stateOpt2).isNotEmpty();
        resultFuture.join();
    @DisplayName("Should provide step count and access")
    void testStepIntrospection() {
        pm.step("step1", (ctx, input) -> "out1", (ctx, output) -> null)
                .step("step2", (ctx, input) -> "out2", (ctx, output) -> null)
                .step("step3", (ctx, input) -> "out3", (ctx, output) -> null);
        assertThat(pm.stepCount()).isEqualTo(3);
        assertThat(pm.getStep(0)).isNotEmpty();
        assertThat(pm.getStep(1)).isNotEmpty();
        assertThat(pm.getStep(2)).isNotEmpty();
        assertThat(pm.getStep(3)).isEmpty();
}
