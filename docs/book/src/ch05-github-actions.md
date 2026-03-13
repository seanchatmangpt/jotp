# Chapter 5: Set Up Integration Pipelines with GitHub Actions

You have working code. You have tests. But "works on my machine" is not a deployment strategy. Every serious team has experienced the sinking feeling of merging a pull request that passed local tests, only to find the CI build red because someone forgot to check in a formatter change, left a `// TODO` in production code, or didn't run the integration tests. This chapter is about closing that gap permanently.

The solution is a pipeline that enforces quality mechanically, not socially. You stop relying on developers to remember to run the right commands. The pipeline runs them for you, fails fast on the cheapest checks, and only proceeds to expensive work when cheap work passes. By the end of this chapter, every push to the TaskFlow repository triggers a workflow that checks formatting, runs unit tests, validates guard rules, runs integration tests, builds a Docker image, and publishes it. You also get a nightly benchmark job that catches performance regressions before they ship.

---

## Pattern: THE QUALITY GATE PIPELINE

**Problem**

Your team has agreed on code standards — formatting, no TODOs in production, no mocks in main source, integration tests must pass. But agreement without enforcement is just aspiration. Developers forget. Reviews miss things. Standards drift.

**Context**

You are operating a Java 26 / JOTP codebase with Spotless for formatting, dx-guard for guard validation, Surefire for unit tests, and Failsafe for integration tests. You are using GitHub as your source control host and need repeatable, auditable quality enforcement on every change.

**Solution**

Define your quality gates as an ordered pipeline in a GitHub Actions workflow. The order matters: cheap, fast gates run first. If formatting is broken, do not spend five minutes running integration tests. Fail immediately, give the developer a clear signal, and let them fix it. The pipeline stages are:

1. **Format check** — `mvnd spotless:check` (ten seconds, zero ambiguity)
2. **Unit tests** — `mvnd test` with Surefire parallel execution (one to three minutes)
3. **Guard validation** — `./dx.sh validate` enforcing H_TODO, H_MOCK, H_STUB rules
4. **Integration tests** — `mvnd verify` with Failsafe (three to eight minutes)
5. **Docker build and push** — only if all previous gates pass

Each gate is a `step` in a single job. If any step fails, the job stops. No Docker image gets published from broken code.

**Code Example**

Create `.github/workflows/ci.yml`:

```yaml
name: TaskFlow CI

on:
  push:
    branches: [ "main", "develop" ]
  pull_request:
    branches: [ "main", "develop" ]
  schedule:
    # Nightly at 02:00 UTC — catches dependency drift and flaky tests
    - cron: "0 2 * * *"

env:
  JAVA_VERSION: "26"
  REGISTRY: ghcr.io
  IMAGE_NAME: seanchatmangpt/taskflow

jobs:
  build:
    name: Build and Verify
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 26
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven

      - name: Install mvnd
        run: |
          MVND_VERSION="2.0.0-rc-3"
          MVND_URL="https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz"
          curl -fsSL "$MVND_URL" -o /tmp/mvnd.tar.gz
          tar -xzf /tmp/mvnd.tar.gz -C /tmp
          sudo mv /tmp/maven-mvnd-${MVND_VERSION}-linux-amd64 /opt/mvnd
          sudo ln -sf /opt/mvnd/bin/mvnd /usr/local/bin/mvnd
          mvnd --version

      # Gate 1: Formatting — fastest gate, fails instantly on style drift
      - name: Check formatting (Spotless)
        run: mvnd spotless:check -q

      # Gate 2: Unit tests — parallel Surefire, no integration tests yet
      - name: Unit tests
        run: mvnd test -T1C
        # -T1C: one thread per CPU core for parallel module builds
        # Surefire parallel execution configured in pom.xml

      # Gate 3: Guard validation — no TODOs, no mocks, no stubs in main source
      - name: Guard validation
        run: ./dx.sh validate

      # Gate 4: Integration tests — Failsafe with @IT suffix, may spin up containers
      - name: Integration tests
        run: mvnd verify -DskipTests=false
        # verify runs compile, test (unit), package, integration-test, verify

      # Gate 5: Docker — only runs after all quality gates pass
      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata for Docker
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: |
            type=ref,event=branch
            type=ref,event=pr
            type=sha,prefix=sha-
            type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: ${{ github.event_name != 'pull_request' }}
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  benchmark:
    name: Nightly JMH Benchmarks
    runs-on: ubuntu-latest
    # Only run on schedule or when explicitly triggered; not on every push
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
    needs: build

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 26
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven

      - name: Install mvnd
        run: |
          MVND_VERSION="2.0.0-rc-3"
          curl -fsSL "https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz" -o /tmp/mvnd.tar.gz
          tar -xzf /tmp/mvnd.tar.gz -C /tmp
          sudo mv /tmp/maven-mvnd-${MVND_VERSION}-linux-amd64 /opt/mvnd
          sudo ln -sf /opt/mvnd/bin/mvnd /usr/local/bin/mvnd

      - name: Run ActorBenchmark (JMH)
        run: |
          mvnd verify -Pbenchmark -Dbenchmark.class=ActorBenchmark \
            -Dbenchmark.fork=1 -Dbenchmark.iterations=5 \
            -Dbenchmark.output=benchmark-results.json

      - name: Upload benchmark results
        uses: actions/upload-artifact@v4
        with:
          name: benchmark-results-${{ github.sha }}
          path: benchmark-results.json

      - name: Check for regressions
        run: |
          # Compare against the baseline stored in the repository
          # Fail if any benchmark is >20% slower than baseline
          mvnd exec:java -Dexec.mainClass=io.github.seanchatmangpt.jotp.bench.RegressionChecker \
            -Dexec.args="benchmark-results.json .ci/benchmark-baseline.json 0.20"
```

**Consequences**

You gain reproducible quality enforcement that runs identically on every developer machine and in CI. The ordering guarantees that expensive work is never done on broken input. The Docker image in `ghcr.io/seanchatmangpt/taskflow` is always a known-good build. The nightly benchmark job catches the quiet performance regressions that creep in through dependency upgrades and refactoring — the kind that never show up in correctness tests but add fifty milliseconds to every request.

The tradeoff is workflow complexity. Five gates mean five places that can fail, and debugging CI failures requires understanding which gate caught what. The payoff is that failures are always specific: if gate one fails, the problem is formatting; if gate four fails, the problem is an integration test. You never hunt for the cause.

---

## Pattern: THE REPRODUCIBLE BUILD

**Problem**

Your CI passes. Your colleague's machine fails. You pass a Docker image to QA and it behaves differently from what you tested locally. The root cause is always the same: environmental variation. Different JDK minor versions, different Maven plugin cache states, different locale settings.

**Context**

You are running Java 26 with preview features enabled via `--enable-preview`. This makes JDK version sensitivity acute — preview APIs change between builds. You need every build to use exactly the same JDK, the same mvnd version, and the same dependency resolution.

**Solution**

Lock everything. Use `actions/setup-java` with an explicit version and the `temurin` distribution. Pin mvnd to `2.0.0-rc-3`. Commit `pom.xml` with explicit plugin versions rather than relying on Maven's default lifecycle bindings, which change between Maven versions. Use the GitHub Actions cache for the Maven local repository to avoid re-downloading dependencies on every run, but use a cache key that includes the `pom.xml` hash so that dependency changes invalidate the cache.

The `pom.xml` configuration for reproducible builds:

```xml
<properties>
  <maven.compiler.source>26</maven.compiler.source>
  <maven.compiler.target>26</maven.compiler.target>
  <!-- Preview features: required for StructuredTaskScope and scoped values -->
  <maven.compiler.compilerArgs>--enable-preview</maven.compiler.compilerArgs>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <!-- Lock Surefire version explicitly -->
  <maven-surefire-plugin.version>3.2.5</maven-surefire-plugin.version>
  <maven-failsafe-plugin.version>3.2.5</maven-failsafe-plugin.version>
</properties>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>${maven-surefire-plugin.version}</version>
      <configuration>
        <!-- Parallel test execution: classes run in parallel -->
        <parallel>classes</parallel>
        <useUnlimitedThreads>true</useUnlimitedThreads>
        <!-- Pass preview flag to forked test JVMs -->
        <argLine>--enable-preview</argLine>
        <!-- Fail fast: stop on first failure in CI -->
        <failIfNoTests>false</failIfNoTests>
      </configuration>
    </plugin>

    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <version>${maven-failsafe-plugin.version}</version>
      <configuration>
        <argLine>--enable-preview</argLine>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>integration-test</goal>
            <goal>verify</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

The `Dockerfile` for the image pushed by CI:

```dockerfile
FROM eclipse-temurin:26-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S taskflow && adduser -S taskflow -G taskflow

COPY --chown=taskflow:taskflow target/taskflow-*.jar app.jar

USER taskflow

EXPOSE 8080

ENTRYPOINT ["java", \
  "--enable-preview", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
  "-jar", "app.jar"]
```

**Consequences**

Pinning versions makes upgrades explicit rather than accidental. You will know when you upgrade mvnd because you changed the number. You will know when you change Surefire because you changed the number. This is more verbose than using Maven's default binding resolution, but it eliminates the class of failures where "it worked last week" and the only change is a transitive plugin version bump.

---

## Pattern: THE ARCHITECTURAL FENCE

**Problem**

JOTP's `Proc<S,M>` is powerful and straightforward to instantiate — which means developers will create processes anywhere: in service classes, in controllers, inside lambda expressions passed to Spring event listeners. Over time this produces a codebase where process lifecycle is unmanaged, supervision is bypassed, and faults propagate in ways the supervision tree was designed to prevent.

**Context**

You have a rule: `Proc` instances must only be created inside Spring `@Configuration` classes, where they can be properly supervised and registered with `ProcRegistry`. This rule is architectural, not stylistic — violation is a defect, not a style preference.

**Solution**

Encode the architectural rule in ArchUnit and run it as part of the test suite. ArchUnit lets you express dependency and creation constraints as Java code, with failure messages that tell developers exactly what went wrong and how to fix it.

Create `src/test/java/io/github/seanchatmangpt/taskflow/arch/ArchitectureTest.java`:

```java
package io.github.seanchatmangpt.taskflow.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {

    private static final JavaClasses TASKFLOW_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.github.seanchatmangpt.taskflow");

    /**
     * Proc instances must only be created inside @Configuration classes.
     * Creating a Proc outside a Configuration class bypasses supervision and
     * ProcRegistry registration, producing unmanaged processes with no fault isolation.
     *
     * Fix: Move Proc creation to a @Configuration class and register with ProcRegistry.
     */
    @Test
    void proc_creation_only_in_configuration_classes() {
        ArchRule rule = noClasses()
            .that()
            .areNotAnnotatedWith(org.springframework.context.annotation.Configuration.class)
            .should()
            .callConstructor(io.github.seanchatmangpt.jotp.Proc.class, Object.class, java.util.function.BiFunction.class)
            .because(
                "Proc instances must be created inside @Configuration classes to ensure " +
                "proper supervision and ProcRegistry registration. " +
                "Unmanaged processes cannot be supervised and bypass fault isolation."
            );

        rule.check(TASKFLOW_CLASSES);
    }

    /**
     * Service classes must not import JOTP primitives directly.
     * Services communicate through ProcRef (a stable handle) or via message passing.
     * Direct imports of Proc couple services to lifecycle management concerns.
     */
    @Test
    void services_do_not_depend_on_proc_directly() {
        ArchRule rule = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("io.github.seanchatmangpt.jotp.Proc")
            .because(
                "Service classes must use ProcRef<S,M> for actor references, not Proc<S,M> directly. " +
                "ProcRef is a stable handle that survives supervisor restarts."
            );

        rule.check(TASKFLOW_CLASSES);
    }

    /**
     * EventManager instances must be declared in @Configuration classes or @Component classes.
     * Ad-hoc EventManager creation in request handlers loses the isolation guarantee —
     * a crashed handler would take down the EventManager itself.
     */
    @Test
    void event_managers_declared_in_managed_beans() {
        ArchRule rule = noClasses()
            .that()
            .areNotAnnotatedWith(org.springframework.context.annotation.Configuration.class)
            .and()
            .areNotAnnotatedWith(org.springframework.stereotype.Component.class)
            .and()
            .areNotAnnotatedWith(org.springframework.stereotype.Service.class)
            .should()
            .callMethod(
                io.github.seanchatmangpt.jotp.EventManager.class,
                "create"
            )
            .because(
                "EventManager instances must be managed Spring beans. " +
                "Transient EventManager instances created per-request lose handler registration " +
                "and the isolation guarantee between handlers."
            );

        rule.check(TASKFLOW_CLASSES);
    }
}
```

The ArchUnit dependency in `pom.xml`:

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version>
  <scope>test</scope>
</dependency>
```

ArchUnit tests run under Surefire as ordinary unit tests. No special configuration is required. When a developer creates a `new Proc<>(...)` in a service class, the `proc_creation_only_in_configuration_classes` test fails with the message you wrote: "Proc instances must be created inside @Configuration classes to ensure proper supervision and ProcRegistry registration."

**Consequences**

The architectural fence is documentation that cannot be ignored. It lives next to your code, runs every time tests run, and gives actionable failure messages. Unlike ADRs or wiki pages, it cannot drift out of date — it reflects the actual structure of the codebase.

The limitation is that ArchUnit rules require explicit maintenance. When you add new constraints, you add new tests. When you intentionally violate a rule (for testing infrastructure, for example), you add an `@ArchIgnore` annotation with a comment explaining why. This is the right tradeoff: explicit exceptions are better than silent non-enforcement.

---

## What Have You Learned?

- **Gates are ordered by cost.** Formatting check before unit tests before integration tests before Docker build. Cheap failures never let expensive work begin.

- **`mvnd spotless:check` as the first gate** provides a ten-second signal that fails fast, produces no ambiguous output, and teaches developers to run formatting before committing rather than after.

- **`./dx.sh validate`** enforces H_TODO, H_MOCK, and H_STUB rules in the same pipeline that enforces tests. Guard violations are CI failures, not optional warnings.

- **Surefire parallel execution** (`-T1C`, `<parallel>classes</parallel>`) cuts unit test time in proportion to core count. This matters when the test suite grows: parallel execution on eight cores cuts eight-minute test suites to under two minutes.

- **ArchUnit rules make architectural decisions enforceable.** "Proc only in @Configuration" is not a guideline — it is a failing test.

- **Nightly JMH benchmarks catch regressions that tests cannot.** A benchmark that fails on twenty percent regression means you find the slow commit the morning after it merges, not three months later when production is sluggish.

- **GitHub Actions `schedule` trigger** runs the full pipeline without a push event. This catches dependency drift: a library you did not change publishes a new version overnight and breaks your build. You find out before your team does.

---
