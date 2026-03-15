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

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Simple runner for JMH precision benchmarks.
 *
 * <p>Usage: java --enable-preview -cp ... RunPrecisionBenchmark
 */
public class RunPrecisionBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt =
                new OptionsBuilder()
                        .include(ObservabilityPrecisionBenchmark.class.getSimpleName())
                        .forks(1)
                        .warmupIterations(3)
                        .measurementIterations(5)
                        .shouldDoGC(true)
                        .jvmArgsAppend("--enable-preview")
                        .build();

        new Runner(opt).run();
    }
}
