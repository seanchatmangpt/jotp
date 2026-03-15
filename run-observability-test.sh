#!/bin/bash
set -e

export JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal
export PATH=$JAVA_HOME/bin:$PATH

echo "=== Compiling main sources ==="
./mvnw compile -q

echo "=== Compiling test sources ==="
./mvnw test-compile -q

echo "=== Running ObservabilityPerformanceTest ==="
java --enable-preview \
     --module-path target/classes:target/test-classes \
     --module io.github.seanchatmangpt.jotp/io.github.seanchatmangpt.jotp.observability.ObservabilityPerformanceTest \
     -Djotp.observability.enabled=false \
     -Djava.util.logging.config.file=src/test/resources/logging.properties \
     -XX:+EnableDynamicAgentLoading \
     -Xmx2g \
     2>&1 | tee /tmp/observability-test-output.txt

echo "=== Test completed. Results saved to /tmp/observability-test-output.txt ==="
