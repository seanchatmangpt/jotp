/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.benchmark;

import java.util.Collection;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/** Simple JMH benchmark runner for ObservabilityPrecisionBenchmark. */
public class BenchmarkRunner {
    public static void main(String[] args) throws Exception {
        Options opt =
                new OptionsBuilder()
                        .include(ObservabilityPrecisionBenchmark.class.getSimpleName())
                        .forks(1)
                        .warmupIterations(3)
                        .measurementIterations(5)
                        .build();

        Collection<RunResult> results = new Runner(opt).run();

        System.out.println("\n=== JMH BENCHMARK RESULTS ===");
        for (RunResult result : results) {
            System.out.printf(
                    "%s: %.2f ns/op%n",
                    result.getParams().getBenchmark(), result.getPrimaryResult().getScore());
        }
    }
}
