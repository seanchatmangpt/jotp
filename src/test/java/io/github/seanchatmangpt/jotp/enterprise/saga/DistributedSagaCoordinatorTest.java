package io.github.seanchatmangpt.jotp.enterprise.saga;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName(
    "DistributedSagaCoordinator: Joe Armstrong compensating transactions with LIFO rollback")
class DistributedSagaCoordinatorTest implements WithAssertions {

  private SagaConfig singleStepConfig() {
    return SagaConfig.builder("test-saga-" + UUID.randomUUID())
        .steps(List.of(new SagaStep.Action<>("step1", input -> "output1")))
        .timeout(Duration.ofSeconds(30))
        .compensationTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  @DisplayName(
      "createWithValidConfig_returnsInstance: DistributedSagaCoordinator.create returns non-null")
  void createWithValidConfig_returnsInstance() {
    var config = singleStepConfig();
    var coordinator = DistributedSagaCoordinator.create(config);
    assertThat(coordinator).isNotNull();
    coordinator.shutdown();
  }

  @Test
  @DisplayName("configBuilder_rejectsEmptySagaId: empty sagaId throws IllegalArgumentException")
  void configBuilder_rejectsEmptySagaId() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                SagaConfig.builder("")
                    .steps(List.of(new SagaStep.Action<>("step1", input -> "output")))
                    .timeout(Duration.ofSeconds(10))
                    .compensationTimeout(Duration.ofSeconds(5))
                    .build());
  }

  @Test
  @DisplayName("configBuilder_rejectsEmptySteps: empty steps list throws IllegalArgumentException")
  void configBuilder_rejectsEmptySteps() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                SagaConfig.builder("saga-" + UUID.randomUUID())
                    .steps(List.of())
                    .timeout(Duration.ofSeconds(10))
                    .compensationTimeout(Duration.ofSeconds(5))
                    .build());
  }

  @Test
  @DisplayName("configBuilder_rejectsZeroTimeout: Duration.ZERO timeout throws IllegalArgumentException")
  void configBuilder_rejectsZeroTimeout() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                SagaConfig.builder("saga-" + UUID.randomUUID())
                    .steps(List.of(new SagaStep.Action<>("step1", input -> "output")))
                    .timeout(Duration.ZERO)
                    .compensationTimeout(Duration.ofSeconds(5))
                    .build());
  }

  @Test
  @DisplayName(
      "execute_singleActionStep_completesSuccessfully: one Action step produces COMPLETED status")
  void execute_singleActionStep_completesSuccessfully() throws Exception {
    var config = singleStepConfig();
    var coordinator = DistributedSagaCoordinator.create(config);

    var result = coordinator.execute().get(5, TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo(DistributedSagaCoordinator.SagaResult.Status.COMPLETED);

    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "execute_multipleSteps_allExecuted: 3 Action steps each produce output in outputs map")
  void execute_multipleSteps_allExecuted() throws Exception {
    var config =
        SagaConfig.builder("saga-" + UUID.randomUUID())
            .steps(
                List.of(
                    new SagaStep.Action<>("step1", input -> "out1"),
                    new SagaStep.Action<>("step2", input -> "out2"),
                    new SagaStep.Action<>("step3", input -> "out3")))
            .timeout(Duration.ofSeconds(30))
            .compensationTimeout(Duration.ofSeconds(10))
            .build();

    var coordinator = DistributedSagaCoordinator.create(config);
    var result = coordinator.execute().get(5, TimeUnit.SECONDS);

    assertThat(result.outputs()).hasSize(3);
    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "execute_stepFails_triggersCompensation: failing Action step yields COMPENSATED status")
  void execute_stepFails_triggersCompensation() throws Exception {
    var config =
        SagaConfig.builder("saga-" + UUID.randomUUID())
            .steps(
                List.of(
                    new SagaStep.Action<>(
                        "failing-step",
                        input -> {
                          throw new RuntimeException("step failure");
                        })))
            .timeout(Duration.ofSeconds(30))
            .compensationTimeout(Duration.ofSeconds(10))
            .build();

    var coordinator = DistributedSagaCoordinator.create(config);
    var result = coordinator.execute().get(5, TimeUnit.SECONDS);

    assertThat(result.status())
        .isEqualTo(DistributedSagaCoordinator.SagaResult.Status.COMPENSATED);
    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "execute_compensationStepsRunInReverse: compensation executes in reverse order after failure")
  void execute_compensationStepsRunInReverse() throws Exception {
    var executionOrder = new CopyOnWriteArrayList<String>();

    var config =
        SagaConfig.builder("saga-" + UUID.randomUUID())
            .steps(
                List.of(
                    new SagaStep.Action<>(
                        "action1",
                        input -> {
                          executionOrder.add("action1");
                          return "out1";
                        }),
                    new SagaStep.Compensation<>(
                        "comp1",
                        input -> executionOrder.add("comp1")),
                    new SagaStep.Action<>(
                        "action2",
                        input -> {
                          executionOrder.add("action2");
                          throw new RuntimeException("action2 failed");
                        }),
                    new SagaStep.Compensation<>(
                        "comp2",
                        input -> executionOrder.add("comp2"))))
            .timeout(Duration.ofSeconds(30))
            .compensationTimeout(Duration.ofSeconds(10))
            .build();

    var coordinator = DistributedSagaCoordinator.create(config);
    var result = coordinator.execute().get(5, TimeUnit.SECONDS);

    assertThat(result.status())
        .isEqualTo(DistributedSagaCoordinator.SagaResult.Status.COMPENSATED);
    // comp1 should have run as part of compensation (reverse order from failedStep)
    assertThat(executionOrder).contains("action1", "action2");
    assertThat(executionOrder).contains("comp1");

    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "getStatus_returnsCurrentTransaction: after execute, getStatus returns Completed transaction")
  void getStatus_returnsCurrentTransaction() throws Exception {
    var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
    var result = coordinator.execute().get(5, TimeUnit.SECONDS);
    var sagaId = result.sagaId();

    var status = coordinator.getStatus(sagaId);
    assertThat(status).isInstanceOf(SagaTransaction.Completed.class);

    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "getSagaLog_containsStepExecutedEvents: after success, log has at least one StepExecuted event")
  void getSagaLog_containsStepExecutedEvents() throws Exception {
    var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
    var result = coordinator.execute().get(5, TimeUnit.SECONDS);
    var sagaId = result.sagaId();

    var log = coordinator.getSagaLog(sagaId);
    assertThat(log).isNotEmpty();
    assertThat(log).anyMatch(event -> event instanceof SagaEvent.StepExecuted);

    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "addListener_onSagaCompleted_fired: listener onSagaCompleted is called after successful saga")
  void addListener_onSagaCompleted_fired() throws Exception {
    var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
    var completedCalled = new AtomicBoolean(false);

    coordinator.addListener(
        new DistributedSagaCoordinator.SagaListener() {
          @Override
          public void onSagaStarted(String sagaId, int stepCount) {}

          @Override
          public void onStepExecuted(String sagaId, String stepName, Object output) {}

          @Override
          public void onCompensationStarted(String sagaId, int fromStep) {}

          @Override
          public void onCompensationCompleted(String sagaId) {}

          @Override
          public void onSagaCompleted(String sagaId, long durationMs) {
            completedCalled.set(true);
          }

          @Override
          public void onSagaAborted(String sagaId, String reason) {}
        });

    coordinator.execute().get(5, TimeUnit.SECONDS);
    await().atMost(Duration.ofSeconds(3)).untilTrue(completedCalled);

    coordinator.shutdown();
  }

  @Test
  @DisplayName(
      "addListener_onCompensationStarted_fired: listener onCompensationStarted called on failure")
  void addListener_onCompensationStarted_fired() throws Exception {
    var config =
        SagaConfig.builder("saga-" + UUID.randomUUID())
            .steps(
                List.of(
                    new SagaStep.Action<>(
                        "failing-step",
                        input -> {
                          throw new RuntimeException("failure");
                        })))
            .timeout(Duration.ofSeconds(30))
            .compensationTimeout(Duration.ofSeconds(10))
            .build();

    var coordinator = DistributedSagaCoordinator.create(config);
    var compensationStarted = new AtomicBoolean(false);

    coordinator.addListener(
        new DistributedSagaCoordinator.SagaListener() {
          @Override
          public void onSagaStarted(String sagaId, int stepCount) {}

          @Override
          public void onStepExecuted(String sagaId, String stepName, Object output) {}

          @Override
          public void onCompensationStarted(String sagaId, int fromStep) {
            compensationStarted.set(true);
          }

          @Override
          public void onCompensationCompleted(String sagaId) {}

          @Override
          public void onSagaCompleted(String sagaId, long durationMs) {}

          @Override
          public void onSagaAborted(String sagaId, String reason) {}
        });

    coordinator.execute().get(5, TimeUnit.SECONDS);
    await().atMost(Duration.ofSeconds(3)).untilTrue(compensationStarted);

    coordinator.shutdown();
  }

  @Test
  @DisplayName("shutdown_doesNotThrow: calling shutdown does not throw any exception")
  void shutdown_doesNotThrow() {
    var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
    assertThatNoException().isThrownBy(coordinator::shutdown);
  }
}
