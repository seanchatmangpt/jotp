package io.github.seanchatmangpt.jotp.eventsourcing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class SagaCoordinatorTest {

  @Test
  public void sagaStepExecution() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-1");

    SagaStep.Action step = new SagaStep.Action(
        "transfer-money",
        ctx -> {
          ctx.put("transferred", true);
          return true;
        },
        ctx -> {
          ctx.put("transferred", false);
          return true;
        }
    );

    // Execute
    boolean result = step.execute().apply(context);
    assertThat(result).isTrue();
    assertThat(context.get("transferred")).isEqualTo(true);

    // Compensate
    boolean compensation = step.compensate().apply(context);
    assertThat(compensation).isTrue();
    assertThat(context.get("transferred")).isEqualTo(false);
  }

  @Test
  public void sagaContextStateTracking() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-2");

    context.put("step1_result", "success");
    context.put("step2_result", 100);

    assertThat(context.get("step1_result")).isEqualTo("success");
    assertThat(context.get("step2_result")).isEqualTo(100);

    // Track completion
    context.markCompleted("step1");
    context.markCompleted("step2");

    assertThat(context.hasCompleted("step1")).isTrue();
    assertThat(context.hasCompleted("step3")).isFalse();
  }

  @Test
  public void sagaContextFailureTracking() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-3");

    Exception ex = new RuntimeException("Payment failed");
    context.markFailed("payment", ex);

    assertThat(context.hasFailed("payment")).isTrue();
    assertThat(context.getFailure("payment")).isEqualTo(ex);
    assertThat(context.failedSteps().keySet()).contains("payment");
  }

  @Test
  public void sagaStepWithCompensation() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-4");

    SagaStep.Action reserveInventory = new SagaStep.Action(
        "reserve-inventory",
        ctx -> {
          ctx.put("inventory_reserved", true);
          return true;
        },
        ctx -> {
          ctx.put("inventory_reserved", false);
          return true;
        }
    );

    SagaStep.Action processPayment = new SagaStep.Action(
        "process-payment",
        ctx -> {
          ctx.put("payment_processed", true);
          return true;
        },
        ctx -> {
          ctx.put("payment_processed", false);
          return true;
        }
    );

    // Execute both
    assertTrue(reserveInventory.execute().apply(context));
    assertTrue(processPayment.execute().apply(context));

    // Verify both succeeded
    assertThat(context.hasCompleted("reserve-inventory")).isFalse(); // Not marked by test
    assertThat(context.get("inventory_reserved")).isEqualTo(true);
    assertThat(context.get("payment_processed")).isEqualTo(true);

    // Compensate in reverse order
    assertTrue(processPayment.compensate().apply(context));
    assertTrue(reserveInventory.compensate().apply(context));

    // Verify both compensated
    assertThat(context.get("inventory_reserved")).isEqualTo(false);
    assertThat(context.get("payment_processed")).isEqualTo(false);
  }

  @Test
  public void sagaStepFailureHandling() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-5");

    SagaStep.Action failingStep = new SagaStep.Action(
        "failing-step",
        ctx -> {
          throw new RuntimeException("Step failed");
        },
        ctx -> true
    );

    // Attempt execution
    assertThatThrownBy(() -> failingStep.execute().apply(context))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  public void sagaDefinitionSequential() {
    SagaCoordinator.SagaDefinition def =
        SagaCoordinator.SagaDefinition.sequential(
            "order-saga",
            new SagaStep.Action("step1", ctx -> true, ctx -> true),
            new SagaStep.Action("step2", ctx -> true, ctx -> true)
        );

    assertThat(def.name()).isEqualTo("order-saga");
    assertThat(def.steps()).hasSize(2);
  }

  @Test
  public void sagaContextCompletedSteps() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-6");

    context.markCompleted("step1");
    context.markCompleted("step2");
    context.markCompleted("step3");

    assertThat(context.completedSteps()).hasSize(3);
    assertThat(context.completedSteps()).contains("step1", "step2", "step3");
  }

  @Test
  public void sagaConditionalStep() {
    SagaStep.SagaContext context = new SagaStep.SagaContext("saga-cond");
    context.put("amount", 1000.0);

    SagaStep.ConditionalStep step = new SagaStep.ConditionalStep(
        "check-amount",
        ctx -> (Double) ctx.get("amount") > 500,
        new SagaStep.Action("approve", ctx -> true, ctx -> true),
        new SagaStep.Action("deny", ctx -> true, ctx -> true)
    );

    assertThat(step.stepName()).isEqualTo("check-amount");
    assertThat(step.condition().apply(context)).isTrue();
  }

  @Test
  public void sagaParallelSteps() {
    SagaStep step1 = new SagaStep.Action("s1", ctx -> true, ctx -> true);
    SagaStep step2 = new SagaStep.Action("s2", ctx -> true, ctx -> true);
    SagaStep step3 = new SagaStep.Action("s3", ctx -> true, ctx -> true);

    SagaStep.ParallelSteps parallel = new SagaStep.ParallelSteps("parallel", step1, step2, step3);

    assertThat(parallel.stepName()).isEqualTo("parallel");
    assertThat(parallel.steps()).hasLength(3);
  }

  @Test
  public void sagaSequentialSteps() {
    SagaStep step1 = new SagaStep.Action("s1", ctx -> true, ctx -> true);
    SagaStep step2 = new SagaStep.Action("s2", ctx -> true, ctx -> true);

    SagaStep.SequentialSteps sequential =
        new SagaStep.SequentialSteps("sequential", step1, step2);

    assertThat(sequential.stepName()).isEqualTo("sequential");
    assertThat(sequential.steps()).hasLength(2);
  }

  private boolean assertTrue(boolean value) {
    assertThat(value).isTrue();
    return value;
  }
}
