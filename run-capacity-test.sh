#!/bin/bash

# Run capacity planning test directly with Java 26

set -e

export JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal
export PATH=$JAVA_HOME/bin:$PATH

# Build classpath
CP="target/classes"
CP="$CP:$(find ~/.m2/repository/org/junit/junit-jupiter-api -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/junit/junit-jupiter-engine -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/junit/platform/commons -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/junit/platform/engine -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/junit/platform/launcher -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/assertj -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/opentest4j -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/net/jqwik -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find ~/.m2/repository/org/apiguardian -name '*.jar' | tr '\n' ':')"

# Compile the test directly
echo "Compiling SimpleCapacityPlanner..."
javac -d target/test-classes \
  -cp "$CP" \
  --enable-preview \
  --release 26 \
  src/test/java/io/github/seanchatmangpt/jotp/stress/SimpleCapacityPlanner.java

echo "Running capacity planning test..."
java -cp "$CP:target/test-classes" \
  --enable-preview \
  io.github.seanchatmangpt.jotp.stress.SimpleCapacityPlanner
