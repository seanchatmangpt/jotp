#!/bin/bash

# Simple benchmark runner for JOTP baseline performance
# This bypasses Maven POM issues and runs directly with Java

JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal
export JAVA_HOME

echo "========================================"
echo "JOTP Baseline Performance Benchmark"
echo "Java Version: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "========================================"

# Compile the benchmark test
echo "Compiling benchmark..."
$JAVA_HOME/bin/javac --enable-preview -cp "target/classes:target/test-classes:$HOME/.m2/repository/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar:$HOME/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar:$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar" \
    -d target/benchmark \
    src/test/java/io/github/seanchatmangpt/jotp/benchmark/SimpleBaselineBenchmark.java 2>&1

if [ $? -ne 0 ]; then
    echo "Compilation failed. Trying alternative approach..."
    # Use Maven to compile just the main code first
    exit 1
fi

echo "Running benchmarks..."
$JAVA_HOME/bin/java --enable-preview \
    -cp "target/benchmark:target/classes:$HOME/.m2/repository/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar" \
    io.github.seanchatmangpt.jotp.benchmark.SimpleBaselineBenchmark

echo "========================================"
echo "Benchmark Complete"
echo "========================================"
