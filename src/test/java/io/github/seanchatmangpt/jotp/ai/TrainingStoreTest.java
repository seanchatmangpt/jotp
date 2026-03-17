package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrainingStoreTest {

  private InMemoryTrainingStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryTrainingStore();
  }

  @Test
  void testSaveAndFetchSample() {
    var sample =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, Instant.now(), 3);
    store.saveSampleAsync(sample);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var stored = store.fetchTrainingSamples(10);
              assertThat(stored).hasSize(1);
            });
  }

  @Test
  void testFetchWithLimit() {
    for (int i = 0; i < 10; i++) {
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0 + i, 60.0, 100, 50.0},
              70.0 + i,
              65.0 + i,
              Instant.now(),
              3);
      store.saveSampleAsync(sample);
    }

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var stored = store.fetchTrainingSamples(5);
              assertThat(stored).hasSize(5);
            });
  }

  @Test
  void testFetchEmpty() {
    var stored = store.fetchTrainingSamples(10);

    assertThat(stored).isEmpty();
  }

  @Test
  void testFetchBetweenTime() {
    var now = Instant.now();
    var oneBefore = now.minusSeconds(60);
    var oneAfter = now.plusSeconds(60);

    var sample1 =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, oneBefore, 3);
    var sample2 =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, now, 3);
    var sample3 =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, oneAfter, 3);

    store.saveSampleAsync(sample1);
    store.saveSampleAsync(sample2);
    store.saveSampleAsync(sample3);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var stored = store.fetchTrainingSamplesBetween(oneBefore, now.plusSeconds(1));
              assertThat(stored).hasSize(2);
            });
  }

  @Test
  void testFlushPending() {
    for (int i = 0; i < 5; i++) {
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0 + i, 60.0, 100, 50.0},
              70.0 + i,
              65.0 + i,
              Instant.now(),
              3);
      store.saveSampleAsync(sample);
    }

    var flushed = store.flushPending();

    assertThat(flushed).isGreaterThan(0);
    assertThat(store.countSamples()).isGreaterThan(0);
  }

  @Test
  void testPurgeOldSamples() {
    var oneMonthAgo = Instant.now().minusSeconds(30L * 86400);
    var recent = Instant.now();

    var oldSample =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, oneMonthAgo, 3);
    var newSample =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, recent, 3);

    store.saveSampleAsync(oldSample);
    store.saveSampleAsync(newSample);

    store.flushPending();

    var purged = store.purgeOldSamples(14); // Keep last 14 days

    assertThat(purged).isGreaterThan(0);
    assertThat(store.countSamples()).isEqualTo(1);
  }

  @Test
  void testCountSamples() {
    assertThat(store.countSamples()).isEqualTo(0);

    for (int i = 0; i < 10; i++) {
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0 + i, 60.0, 100, 50.0},
              70.0 + i,
              65.0 + i,
              Instant.now(),
              3);
      store.saveSampleAsync(sample);
    }

    store.flushPending();

    assertThat(store.countSamples()).isEqualTo(10);
  }

  @Test
  void testGetStatistics() {
    for (int i = 0; i < 5; i++) {
      var actualLoad = 70.0 + i * 2;
      var predictedLoad = 65.0 + i;
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0 + i, 60.0, 100, 50.0},
              actualLoad,
              predictedLoad,
              Instant.now(),
              3);
      store.saveSampleAsync(sample);
    }

    store.flushPending();

    var stats = store.getStatistics();

    assertThat(stats).containsKeys("min_error", "max_error", "avg_error", "count");
    assertThat(stats.get("count")).isEqualTo(5.0);
    assertThat(stats.get("avg_error")).isGreaterThan(0);
  }

  @Test
  void testStatisticsEmpty() {
    var stats = store.getStatistics();

    assertThat(stats.get("count")).isEqualTo(0.0);
    assertThat(stats.get("avg_error")).isEqualTo(0.0);
  }

  @Test
  void testAsyncQueueBehavior() {
    // Fill the queue beyond capacity (simulate high throughput)
    for (int i = 0; i < 15000; i++) {
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0, 60.0, 100, 50.0},
              70.0,
              65.0,
              Instant.now(),
              3);
      store.saveSampleAsync(sample); // May drop oldest if queue is full
    }

    // Flush and count
    store.flushPending();

    // Should have at most the queue capacity
    assertThat(store.countSamples()).isLessThanOrEqualTo(10000);
  }

  @Test
  void testCloseShutdown() {
    for (int i = 0; i < 5; i++) {
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0 + i, 60.0, 100, 50.0},
              70.0 + i,
              65.0 + i,
              Instant.now(),
              3);
      store.saveSampleAsync(sample);
    }

    // Close should flush pending and shutdown gracefully
    assertThatNoException().isThrownBy(store::close);

    assertThat(store.countSamples()).isGreaterThan(0);
  }

  @Test
  void testRecordingManyFeaturesCorrectly() {
    var features = new double[] {45.5, 67.8, 2345, 567.89};
    var sample =
        new ClusterOptimizer.TrainingSample(
            features, 75.2, 73.1, Instant.now(), 5);
    store.saveSampleAsync(sample);

    store.flushPending();

    var fetched = store.fetchTrainingSamples(1);
    assertThat(fetched).hasSize(1);
    var retrieved = fetched.get(0);

    assertThat(retrieved.features()).containsExactly(45.5, 67.8, 2345, 567.89);
    assertThat(retrieved.actualLoad()).isEqualTo(75.2);
    assertThat(retrieved.predictedLoad()).isEqualTo(73.1);
    assertThat(retrieved.nodeCount()).isEqualTo(5);
  }

  @Test
  void testOrdering() {
    var earlier = Instant.now().minusSeconds(60);
    var middle = Instant.now();
    var later = Instant.now().plusSeconds(60);

    var sample1 =
        new ClusterOptimizer.TrainingSample(
            new double[] {1.0, 1.0, 1, 1.0}, 1.0, 1.0, earlier, 1);
    var sample2 =
        new ClusterOptimizer.TrainingSample(
            new double[] {2.0, 2.0, 2, 2.0}, 2.0, 2.0, middle, 2);
    var sample3 =
        new ClusterOptimizer.TrainingSample(
            new double[] {3.0, 3.0, 3, 3.0}, 3.0, 3.0, later, 3);

    store.saveSampleAsync(sample1);
    store.saveSampleAsync(sample3);
    store.saveSampleAsync(sample2);

    store.flushPending();

    var fetched = store.fetchTrainingSamples(3);

    // Should be ordered by most recent first
    assertThat(fetched.get(0).nodeCount()).isEqualTo(3);
    assertThat(fetched.get(1).nodeCount()).isEqualTo(2);
    assertThat(fetched.get(2).nodeCount()).isEqualTo(1);
  }
}
