package io.github.seanchatmangpt.jotp.benchmark.example;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Example JMH benchmark demonstrating the benchmark reporting infrastructure.
 *
 * <p>This benchmark tests simple map operations and serves as a template for creating your own
 * benchmarks.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * ./mvnw test -Dtest=ExampleBenchmark -Djmh.format=json -Djmh.outputDir=target/jmh
 * }</pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
public class ExampleBenchmark {

    private static final int SIZE = 1000;

    private int[] array;

    @Setup
    public void setup() {
        array = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            array[i] = i;
        }
    }

    @Benchmark
    public int sumArray() {
        int sum = 0;
        for (int value : array) {
            sum += value;
        }
        return sum;
    }

    @Benchmark
    public int sumArrayWithBranch() {
        int sum = 0;
        for (int value : array) {
            if (value > 0) {
                sum += value;
            }
        }
        return sum;
    }

    @Benchmark
    public int findMax() {
        int max = array[0];
        for (int value : array) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }
}
