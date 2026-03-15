# ⚠️ ARCHIVED - JOTP DevOps Documentation

**ARCHIVE NOTICE**: This file has been archived and is superseded by consolidated DevOps documentation.
**Archived**: 2026-03-15
**See**: `/docs/devops/` for current DevOps guides
**Details**: `ARCHIVE_NOTICE.md` in this directory

---

**Version**: 1.0.0-Alpha
**Last Updated**: 2025-03-14
**Target Audience**: Developers, DevOps Engineers, SREs

## Table of Contents

1. [Development Setup](#development-setup)
2. [Build System](#build-system)
3. [CI/CD Pipeline](#cicd-pipeline)
4. [Testing Strategy](#testing-strategy)
5. [Release Process](#release-process)
6. [Monitoring & Observability](#monitoring--observability)
7. [Troubleshooting](#troubleshooting)

---

## Development Setup

### Prerequisites

#### Required Software

| Tool | Version | Purpose | Installation |
|------|---------|---------|--------------|
| **OpenJDK 26** | 26+ | Java 26 with preview features | Auto-installed by `.claude/setup.sh` or manual download |
| **Maven Daemon (mvnd)** | 2.0.0-rc-3 | Build tool (Maven 4) | Auto-installed by `.claude/setup.sh` or [mvnd releases](https://github.com/apache/maven-mvnd/releases) |
| **Git** | 2.30+ | Version control | System package manager |
| **Make** | 4.0+ | Build automation | System package manager |

#### Optional Tools

- **GPG** - For signing releases (required for Maven Central publishing)
- **Docker** - For containerized testing and deployment
- **Claude Code** - AI-assisted development (optional but recommended)

### IDE Configuration

#### IntelliJ IDEA (Recommended)

1. **Project SDK**: Configure JDK 26
   - File → Project Structure → SDKs → Add JDK
   - Path: `/usr/lib/jvm/openjdk-26`

2. **Enable Preview Features**:
   - File → Project Structure → Project
   - Set "Language Level" to "26 (Preview)"
   - Add to VM options: `--enable-preview`

3. **Build Configuration**:
   - File → Settings → Build → Build Tools → Maven
   - Maven home: Use bundled or point to mvnd installation
   - Runner → VM Options: `--enable-preview`

4. **Code Style**:
   - Import Spotless configuration: Use Google Java Format (AOSP style)
   - Settings → Editor → Code Style → Java → Import → Google Java Format

#### VS Code

1. **Extensions**:
   ```
   - Extension Pack for Java (Microsoft)
   - Java Code Generators (Saber Land)
   - Spotless ( optional - format on save)
   ```

2. **Settings (.vscode/settings.json)**:
   ```json
   {
     "java.configuration.runtimes": [
       {
         "name": "JavaSE-26",
         "path": "/usr/lib/jvm/openjdk-26",
         "default": true
       }
     ],
     "java.jdt.ls.vmargs": "--enable-preview",
     "java.compile.nullAnalysis.mode": "automatic",
     "editor.formatOnSave": true
   }
   ```

#### Eclipse

1. **Preferences**:
   - Java → Compiler → Enable preview features
   - Java → Code Style → Formatter: Import Spotless config
   - Run/Debug → Running Tests: Use JUnit 5

2. **Build Path**:
   - Add JDK 26 as installed JRE
   - Configure Maven integration

### Local Development Workflow

#### Initial Setup

```bash
# Clone repository
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp

# Run setup script (installs JDK 26, mvnd, configures proxy)
bash .claude/setup.sh

# Verify installation
java -version    # Should show 26
mvnd --version   # Should show 2.0.0-rc-3
```

#### Daily Development

```bash
# Create feature branch
git checkout -b feature/your-feature-name

# Make changes and test locally
mvnd compile -T1C                    # Fast incremental compile
mvnd test -Dtest=YourTest -T1C       # Run specific test
mvnd spotless:apply -q               # Format code (auto-runs by hook)

# Commit changes
git add .
git commit -m "feat: add your feature description"

# Push to remote
git push origin feature/your-feature-name
```

#### Pre-commit Checklist

Before pushing, run:

```bash
# Full validation
mvnd verify -T1C

# Manual checks
bash .claude/hooks/simple-guards.sh  # Check for forbidden patterns
mvnd spotless:check                  # Verify formatting
mvnd javadoc:javadoc                 # Check Javadoc
```

---

## Build System

### Maven vs mvnd

**JOTP uses Maven Daemon (mvnd) as the primary build tool.**

| Feature | Maven (mvn) | Maven Daemon (mvnd) |
|---------|-------------|---------------------|
| **Startup time** | 2-5 seconds | <0.5 seconds (cached JVM) |
| **Incremental builds** | Slower | 30-40% faster |
| **Parallel execution** | Via `-T` flag | Default, auto-detects cores |
| **Maven version** | 3.x | **4.0 (bundled)** |
| **Classpath caching** | No | Yes (significant speedup) |
| **Recommendation** | For CI only | **For all development** |

### Build Commands

#### Core Commands

```bash
# Compile main sources
mvnd compile -T1C

# Run unit tests
mvnd test -T1C

# Run integration tests
mvnd verify -T1C

# Full build with all checks
mvnd clean verify -T1C

# Format code
mvnd spotless:apply -q

# Check formatting
mvnd spotless:check

# Generate Javadoc
mvnd javadoc:javadoc

# Package JAR
mvnd package -T1C
```

#### Profile-based Builds

```bash
# Build fat/uber JAR (includes dependencies)
mvnd package -Dshade -T1C

# Run dogfood validation
mvnd verify -Ddogfood

# Run benchmarks
mvnd verify -Pbenchmark
```

#### Module Structure

```
jotp/
├── src/main/java/              # Main source code
│   └── io/github/seanchatmangpt/jotp/
│       ├── Proc.java           # Core primitives
│       ├── Supervisor.java
│       ├── StateMachine.java
│       ├── messaging/          # EIP patterns
│       ├── reactive/           # Reactive adapters
│       └── dogfood/            # Template examples
├── src/test/java/              # Unit tests
│   └── io/github/seanchatmangpt/jotp/
│       ├── ProcTest.java       # *Test.java - unit tests
│       └── ProcIT.java         # *IT.java - integration tests
├── src/test/resources/
│   └── junit-platform.properties  # Parallel test config
├── target/                     # Build output
│   ├── jotp-*.jar              # Main artifact
│   ├── jotp-*-sources.jar      # Sources
│   ├── jotp-*-javadoc.jar      # Javadoc
│   └── test-results/           # Test reports
└── pom.xml                     # Maven configuration
```

### Build Optimization Tips

#### 1. Parallel Compilation

```bash
# Auto-detect CPU cores (default)
mvnd compile -T1C

# Override thread count
mvnd verify -T2     # Use 2 threads (constrained environments)
```

#### 2. Build Cache Warmup

Before long development sessions:

```bash
# Warm classpath cache
mvnd compile -q -T1C

# Offline mode (no downloads)
mvnd verify -T1C --offline
```

#### 3. Incremental Builds

```bash
# Only compile changed modules
mvnd compile --include '::core'

# Exclude specific modules
mvnd verify --exclude '::integration'
```

#### 4. Spotless Integration

Spotless runs automatically at compile phase. The PostToolUse hook auto-runs `spotless:apply` after every Java file edit.

```bash
# Manually check formatting
mvnd spotless:check

# Manually apply formatting
mvnd spotless:apply -q
```

#### 5. Test Optimization

```bash
# Run single test class
mvnd test -Dtest=ProcTest

# Run single test method
mvnd test -Dtest=ProcTest#testSpawn

# Skip tests during development
mvnd package -DskipTests
```

---

## CI/CD Pipeline

### GitHub Actions Workflows

JOTP uses GitHub Actions for continuous integration and deployment.

#### Workflow Files

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `maven-build.yml` | Push to `main` | Basic build verification |
| `release.yml` | Tag push (`v*`) | Full release pipeline |
| `quality-gates.yml` | Pull requests | Code quality validation |
| `publish.yml` | Manual | Maven Central publishing |
| `ci-gate.yml` | All pushes | Pre-commit validation |

#### Build Pipeline Stages

```mermaid
graph LR
    A[Trigger] --> B[Quality Gates]
    B --> C[Unit Tests]
    C --> D[Integration Tests]
    D --> E[Build Artifacts]
    E --> F[GPG Signing]
    F --> G[Deploy to Sonatype]
    G --> H[Maven Central Sync]
    H --> I[GitHub Release]
```

### Branch Strategy

#### Branch Types

| Branch | Pattern | Purpose | Protection |
|--------|---------|---------|------------|
| **main** | `main` | Production code | Required reviews, status checks |
| **develop** | `develop` | Integration branch | Required reviews |
| **feature** | `feature/*` | New features | None |
| **bugfix** | `bugfix/*` | Bug fixes | None |
| **hotfix** | `hotfix/*` | Production hotfixes | Required reviews |
| **release** | `release/*` | Release preparation | Required reviews |

#### Branch Protection Rules (main)

```yaml
# GitHub Settings → Branches → Branch Protection Rules
- Require pull request reviews (1 approval)
- Require status checks to pass
  - Maven Build (mvnd verify)
  - Quality Gates
  - Test Coverage
- Require branches to be up to date
- Do not allow bypassing the above settings
```

### Pull Request Process

#### Creating a PR

1. **Create feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make changes and test**:
   ```bash
   mvnd verify -T1C
   ```

3. **Commit with conventional commits**:
   ```bash
   git commit -m "feat: add process monitoring support"
   ```

4. **Push and create PR**:
   ```bash
   git push origin feature/your-feature-name
   # Create PR via GitHub UI
   ```

#### PR Checklist

- [ ] All tests pass (`mvnd verify`)
- [ ] Code formatted (`spotless:apply`)
- [ ] No forbidden patterns (simple-guards.sh)
- [ ] Javadoc updated for public APIs
- [ ] Changelog updated (if breaking change)
- [ ] Commits follow conventional commits
- [ ] PR description includes:
  - Summary of changes
  - Breaking changes (if any)
  - Testing performed
  - Related issues (closes #123)

#### PR Review Criteria

1. **Code Quality**:
   - Follows Java 26 best practices
   - Uses sealed types and pattern matching appropriately
   - No code smells or anti-patterns

2. **OTP Correctness**:
   - Supervisor trees properly structured
   - Process links and monitors used correctly
   - Error handling follows "let it crash" philosophy

3. **Testing**:
   - Unit tests for new functionality
   - Integration tests for cross-component features
   - Edge cases covered

4. **Documentation**:
   - Public APIs have Javadoc
   - Complex algorithms explained
   - Architecture docs updated if needed

---

## Testing Strategy

### Test Types

#### Unit Tests (`*Test.java`)

- **Location**: `src/test/java/`
- **Framework**: JUnit 5
- **Execution**: `mvnd test`
- **Coverage Goal**: 80%+ line coverage

**Best Practices**:
- Test pure functions independently
- Use jqwik for property-based testing
- Use Instancio for test data generation
- Mock external dependencies ( Mockito)

```java
@Test
@DisplayName("should spawn process with initial state")
void testSpawn() {
    // Given
    var initialState = new MyState(0);
    var handler = (MyState s, Msg m) -> switch (m) {
        case Increment _ -> new MyState(s.count() + 1);
    };

    // When
    var proc = Proc.spawn(initialState, handler);

    // Then
    assertThat(proc).isNotNull();
}
```

#### Integration Tests (`*IT.java`)

- **Location**: `src/test/java/`
- **Framework**: JUnit 5 + Awaitility
- **Execution**: `mvnd verify` (Failsafe plugin)
- **Purpose**: Test cross-component interactions

**Best Practices**:
- Test supervisor tree behavior
- Test process communication
- Test crash recovery scenarios
- Use Awaitility for async assertions

```java
@Test
@DisplayName("should restart crashed child process")
void testSupervisorRestart() {
    // Given
    var supervisor = Supervisor.create(
        RestartStrategy.oneForOne(),
        List.of(childSpec)
    );

    // When
    childProc.crash();

    // Then
    await().atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
            var newChild = supervisor.whichChildren().get(0);
            assertThat(newChild.isAlive()).isTrue();
            assertThat(newChild.pid()).isNotEqualTo(oldPid);
        });
}
```

#### Property-Based Tests

- **Framework**: jqwik
- **Purpose**: Find edge cases via generated inputs
- **Execution**: `mvnd test`

```java
@Property
@DisplayName("state transitions should be deterministic")
void stateTransitionsDeterministic(
    @ForAll("validStates") MyState state,
    @ForAll("validEvents") MyEvent event
) {
    var transition1 = stateMachine.handle(state, event);
    var transition2 = stateMachine.handle(state, event);

    assertThat(transition1).isEqualTo(transition2);
}
```

### Parallel Test Execution

JOTP runs all tests in parallel by default:

```properties
# src/test/resources/junit-platform.properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.config.strategy = dynamic
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.mode.classes.default = concurrent
```

**Benefits**:
- Faster test execution (3-5x speedup on multi-core machines)
- Detects concurrency bugs
- Validates thread safety of OTP primitives

### Coverage Requirements

#### Current Status

JaCoCo is configured but commented out due to network/certificate issues. When re-enabled:

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <configuration>
    <rules>
      <rule>
        <element>PACKAGE</element>
        <limits>
          <limit>
            <counter>LINE</counter>
            <value>COVEREDRATIO</value>
            <minimum>0.80</minimum>  <!-- 80% coverage required -->
          </limit>
        </limits>
      </rule>
    </rules>
  </configuration>
</plugin>
```

#### Running Coverage Reports

```bash
# Generate coverage report
mvnd test jacoco:report jacoco:check

# View report
open target/site/jacoco/index.html
```

### Test Data Generation

JOTP uses **Instancio** for automatic test data generation:

```java
@Test
void testProcessWithComplexState() {
    // Auto-generate complex state
    var state = Instancio.create(MyComplexState.class);

    var proc = Proc.spawn(state, handler);
    assertThat(proc).isNotNull();
}
```

---

## Release Process

### Versioning Scheme

JOTP follows **Semantic Versioning 2.0**:

```
MAJOR.MINOR.PATCH-prerelease+build

Examples:
1.0.0-Alpha      → Initial release
1.0.0-Beta.1     → First beta
1.0.0            → Stable release
1.1.0-RC.1       → Release candidate for feature release
2.0.0            → Major breaking change
```

#### Version Increments

| Change | Example | Impact |
|--------|---------|--------|
| **Major** | 1.0.0 → 2.0.0 | Breaking API changes, removed features |
| **Minor** | 1.0.0 → 1.1.0 | New features, backward compatible |
| **Patch** | 1.0.0 → 1.0.1 | Bug fixes, backward compatible |
| **Pre-release** | 1.0.0-Alpha | Development builds, not production-ready |

### Release Checklist

#### Pre-release

1. **Update Version Numbers**:
   ```bash
   # Update pom.xml version
   vim pom.xml  # Change <version>1.0.0-Alpha</version>

   # Commit version change
   git commit -m "chore: bump version to 1.0.0"
   ```

2. **Update CHANGELOG.md**:
   ```markdown
   ## [1.0.0] - 2025-03-14

   ### Added
   - Initial release of JOTP framework
   - All 15 OTP primitives implemented

   ### Changed
   - N/A

   ### Deprecated
   - N/A

   ### Removed
   - N/A

   ### Fixed
   - N/A

   ### Security
   - N/A
   ```

3. **Run Full Validation**:
   ```bash
   mvnd clean verify -T1C
   bash scripts/devops/verify-release.sh
   ```

4. **Create Release Branch**:
   ```bash
   git checkout -b release/1.0.0
   git push origin release/1.0.0
   ```

#### Release

1. **Create and Push Tag**:
   ```bash
   git tag -a v1.0.0 -m "Release 1.0.0"
   git push origin v1.0.0
   ```

2. **Trigger CI/CD Pipeline**:
   - GitHub Actions automatically detects tag
   - Runs full build, tests, quality gates
   - Deploys to Maven Central staging

3. **Verify Staging Deployment**:
   - Check [Sonatype Staging](https://s01.oss.sonatype.org/)
   - Verify artifacts are present
   - Check GPG signatures

4. **Release to Maven Central**:
   - Click "Release" button in Sonatype Nexus
   - Wait for sync to Maven Central (typically 10-30 minutes)

5. **Create GitHub Release**:
   - CI/CD automatically creates release
   - Includes release notes, artifacts, documentation links

#### Post-release

1. **Merge to Main**:
   ```bash
   git checkout main
   git merge release/1.0.0
   git push origin main
   ```

2. **Announce Release**:
   - Update GitHub Discussions
   - Post on community channels
   - Update documentation with new features

3. **Prepare Next Version**:
   ```bash
   # Bump to next development version
   vim pom.xml  # Change to 1.1.0-SNAPSHOT
   git commit -m "chore: bump version to 1.1.0-SNAPSHOT"
   git push origin main
   ```

### Maven Central Publishing

#### Prerequisites

1. **Sonatype Account**:
   - Create account at [Sonatype JIRA](https://issues.sonatype.org/)
   - Create project ticket for new groupId: `io.github.seanchatmangpt`
   - Verify domain ownership

2. **GPG Key**:
   ```bash
   # Generate GPG key
   gpg --gen-key

   # Publish to key servers
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   gpg --keyserver pgp.mit.edu --send-keys YOUR_KEY_ID

   # Export for GitHub Secrets
   gpg --armor --export-secret-keys YOUR_KEY_ID
   ```

3. **GitHub Secrets**:
   - `SONATYPE_USERNAME` - Sonatype JIRA username
   - `SONATYPE_PASSWORD` - Sonatype JIRA password
   - `GPG_PRIVATE_KEY` - GPG private key (armored export)
   - `GPG_PASSPHRASE` - GPG key passphrase
   - `GPG_KEY_ID` - GPG key ID (e.g., `ABC12345`)

#### Publishing Process

```bash
# Manual publishing (local)
mvnd clean deploy -Prelease \
  -Dgpg.passphrase=YOUR_PASSPHRASE \
  -Dgpg.keyname=YOUR_KEY_ID \
  -DskipTests=false

# Via GitHub Actions (automatic on tag push)
git tag v1.0.0
git push origin v1.0.0  # Triggers release.yml workflow
```

#### Troubleshooting Publishing

**Issue**: GPG signing fails
```bash
# Check GPG agent is running
gpg --agent-program /usr/local/bin/gpg-agent --daemon

# Test GPG signing
echo "test" | gpg --clearsign
```

**Issue**: Sonatype staging fails
```bash
# Check Maven settings
cat ~/.m2/settings.xml

# Verify credentials
mvnd deploy -X  # Enable debug output
```

**Issue**: Artifacts not synced to Maven Central
- Wait 10-30 minutes after release
- Check [Maven Central](https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/)
- Contact Sonatype support if >24 hours

---

## Monitoring & Observability

### OpenTelemetry Integration

JOTP provides native OpenTelemetry integration for cloud-native observability.

#### Quick Start

```bash
# Start OpenTelemetry stack (Collector, Jaeger, Prometheus, Grafana)
make otel-start

# Run JOTP with OpenTelemetry enabled
make otel-run

# View traces in Jaeger
make otel-traces

# View metrics in Prometheus
make otel-metrics

# Stop OpenTelemetry stack
make otel-stop
```

#### Services

| Service | URL | Credentials |
|---------|-----|-------------|
| Jaeger UI | http://localhost:16686 | None |
| Prometheus | http://localhost:9090 | None |
| Grafana | http://localhost:3000 | admin/admin |
| OTLP Collector | http://localhost:4317 | gRPC |

#### Programmatic Configuration

```java
// Create OpenTelemetry service
var config = OtelConfiguration.builder()
    .serviceName("my-jotp-app")
    .otlpEndpoint("http://otel-collector:4317")
    .exportInterval(Duration.ofSeconds(10))
    .enableMetrics(true)
    .enableTracing(true)
    .build();

var otelService = OpenTelemetryService.create(config);

// Bridge existing components
var metricsBridge = MetricsCollectorBridge.create(
    metricsCollector,
    otelService.meterProvider()
);

var tracerBridge = DistributedTracerBridge.create(
    distributedTracer,
    otelService.tracerProvider()
);

// Export metrics and spans periodically
metricsBridge.export();
tracerBridge.export();
```

#### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_SERVICE_NAME` | `jotp-service` | Service name for telemetry |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP endpoint |
| `OTEL_TRACES_EXPORTER` | `otlp` | Trace exporter |
| `OTEL_METRICS_EXPORTER` | `otlp` | Metrics exporter |

#### Makefile Targets

```bash
make otel-setup     # Setup OpenTelemetry configuration
make otel-start     # Start OpenTelemetry stack
make otel-stop      # Stop OpenTelemetry stack
make otel-restart   # Restart OpenTelemetry stack
make otel-run       # Run JOTP with OpenTelemetry
make otel-test      # Run tests with tracing
make otel-metrics   # Open Prometheus UI
make otel-traces    # Open Jaeger UI
make otel-logs      # View stack logs
make otel-validate  # Validate configuration
make otel-status    # Show stack status
make otel-clean     # Remove containers and volumes
```

#### Configuration Files

- `docker-compose.otel.yml` — Docker Compose stack configuration
- `otel/config/collector-config.yaml` — OpenTelemetry Collector configuration
- `otel/config/prometheus.yml` — Prometheus scrape configuration
- `.github/workflows/observability.yml` — CI/CD observability validation

#### Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────┐
│  JOTP Process   │────▶│ OTLP Exporter    │────▶│   Jaeger    │
│  (Virtual       │     │ (gRPC/HTTP)      │     │  (Traces)   │
│   Threads)      │     │                  │     └─────────────┘
└─────────────────┘     │                  │     ┌─────────────┐
                        │                  │────▶│ Prometheus  │
┌─────────────────┐     │                  │     │  (Metrics)  │
│  MetricsCollector│────│                  │     └─────────────┐
│  DistributedTracer│   │                  │     ┌─────────────┐
│  HealthMonitor   │───▶│                  │────▶│   Grafana   │
└─────────────────┘     └──────────────────┘     │ (Dashboards)│
                                                 └─────────────┘
```

For detailed documentation, see [OpenTelemetry Integration Guide](./OPENTELEMETRY.md).

---

### Health Checks

JOTP provides health check endpoints for monitoring:

#### Process Health

```java
public class HealthChecker {
    public static SystemHealth checkSystemHealth() {
        var registry = ProcRegistry.registered();
        var aliveProcesses = registry.stream()
            .filter(pid -> Proc.whereis(pid).isPresent())
            .count();

        var deadProcesses = registry.size() - aliveProcesses;

        return new SystemHealth(
            registry.size(),
            aliveProcesses,
            deadProcesses,
            deadProcesses == 0
        );
    }
}
```

#### Supervisor Health

```java
public record SupervisorHealth(
    String supervisorName,
    int totalChildren,
    int runningChildren,
    int restartingChildren,
    int crashedChildren,
    boolean isHealthy
) {}
```

### Metrics Collection

JOTP integrates with **Micrometer** for metrics collection:

#### Key Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `otp.process.spawned` | Counter | Total processes spawned |
| `otp.process.crashed` | Counter | Total process crashes |
| `otp.supervisor.restarts` | Counter | Supervisor restarts |
| `otp.mailbox.size` | Gauge | Mailbox queue depth |
| `otp.message.latency` | Timer | Message processing time |

#### Configuration

```java
// Add Micrometer dependency (optional)
// pom.xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-core</artifactId>
  <version>1.12.0</version>
</dependency>

// Enable metrics
MeterRegistry registry = new SimpleMeterRegistry();
ProcMetrics.enableMetrics(registry);
```

### Error Tracking

#### Structured Logging

JOTP uses **SLF4J** with structured logging:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyProcess {
    private static final Logger log = LoggerFactory.getLogger(MyProcess.class);

    public void handleMessage(Message msg) {
        try {
            // Process message
        } catch (Exception e) {
            log.error("Message processing failed: {}", msg, e);
            throw e;  // "Let it crash"
        }
    }
}
```

#### Error Aggregation

Integrate with error tracking services:

```java
// Sentry example
import io.sentry.Sentry;

Sentry.captureException(e);

// Datadog example
import datadog.trace.api.DDTags;
import datadog.trace.api.Trace;

@Trace(operationName = "process.message")
public void handleMessage(Message msg) {
    // ...
}
```

### Distributed Tracing

#### OpenTelemetry Integration

```java
// Add OpenTelemetry dependencies
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-api</artifactId>
  <version>1.32.0</version>
</dependency>

// Trace message flows
private final Tracer tracer = OpenTelemetry.getGlobalTracer();

public void tell(Message msg) {
  var span = tracer.spanBuilder("proc.tell")
      .setAttribute("message.type", msg.getClass().getSimpleName())
      .startSpan();

  try (var scope = span.makeCurrent()) {
    mailbox.put(msg);
  } finally {
    span.end();
  }
}
```

---

## Troubleshooting

### Common Issues and Solutions

#### Build Issues

**Issue**: "Spotless formatting violations" at compile

**Root Cause**: Code not formatted with Google Java Format

**Solution**:
```bash
mvnd spotless:apply -q
```

**Prevention**: PostToolUse hook auto-runs spotless:apply after edits

---

**Issue**: "Module not found" after git pull

**Root Cause**: Classpath cache is stale

**Solution**:
```bash
rm -rf ~/.m2/repository/io/github/seanchatmangpt
mvnd clean compile
```

---

**Issue**: Build timeout

**Solution**:
```bash
mvnd verify -Dorg.slf4j.simpleLogger.defaultLogLevel=error
```

---

### Java & Preview Features

**Issue**: "Java 26 not found" error at startup

**Root Cause**: OpenJDK 26 installation failed

**Solution**:
```bash
bash .claude/setup.sh
```

---

**Issue**: "--enable-preview not recognized"

**Root Cause**: Wrong JDK (not Java 26)

**Verification**:
```bash
java -version  # Should show 26
javac -version # Should show 26
```

**Solution**:
```bash
export JAVA_HOME=/usr/lib/jvm/openjdk-26
export PATH=$JAVA_HOME/bin:$PATH
```

---

**Issue**: Virtual thread limit exceeded

**Symptom**: "Cannot create virtual thread" or thread pool saturation

**Root Cause**: Too many concurrent processes in supervisor

**Solution**:
```bash
mvnd verify -XX:+UnlockDiagnosticVMOptions -XX:PreviewFeatures=...
```

---

### Testing Issues

**Issue**: Tests fail with "Preview features not enabled"

**Solution**:
```bash
# Verify surefire configuration
grep -A5 "maven-surefire-plugin" pom.xml

# Should include:
# <argLine>--enable-preview</argLine>
```

---

**Issue**: Parallel test execution causes failures

**Root Cause**: Tests share mutable state

**Solution**:
```bash
# Run tests sequentially for debugging
mvnd test -Djunit.jupiter.execution.parallel.enabled=false
```

---

### Release Issues

**Issue**: GPG signing fails

**Solution**:
```bash
# Check GPG agent
gpg --agent-program /usr/local/bin/gpg-agent --daemon

# Test GPG signing
echo "test" | gpg --clearsign

# Verify key is published
gpg --keyserver keyserver.ubuntu.com --recv-keys YOUR_KEY_ID
```

---

**Issue**: Sonatype staging fails with "401 Unauthorized"

**Root Cause**: Invalid credentials

**Solution**:
```bash
# Update Maven settings
vim ~/.m2/settings.xml

# Verify credentials work
mvnd deploy -X -DdryRun=true
```

---

### Performance Issues

**Issue**: Slow build times

**Solution**:
```bash
# Warm build cache
mvnd compile -q -T1C --offline

# Use build daemon
jps | grep mvnd  # Check if daemon running
mvnd --stop      # Restart daemon if stuck
```

---

**Issue**: OutOfMemoryError during build

**Solution**:
```bash
export MAVEN_OPTS="-Xmx4g -Xms2g"
mvnd verify
```

---

### IDE Issues

**Issue**: IntelliJ shows "Preview features not enabled"

**Solution**:
1. File → Project Structure → Project
2. Set "Language Level" to "26 (Preview)"
3. Add to VM options: `--enable-preview`
4. Build → Rebuild Project

---

**Issue**: VS Code doesn't recognize Java 26

**Solution**:
```bash
# Install Java 26 extension
code --install-extension redhat.java

# Update .vscode/settings.json
{
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-26",
      "path": "/usr/lib/jvm/openjdk-26",
      "default": true
    }
  ]
}
```

---

## Additional Resources

### Documentation

- [Main README](../README.md)
- [Architecture Guide](../.claude/ARCHITECTURE.md)
- [SLA Patterns](../.claude/SLA-PATTERNS.md)
- [Integration Patterns](../.claude/INTEGRATION-PATTERNS.md)
- [Quick Reference](../docs/QUICK_REFERENCE.md)

### Community

- [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- [GitHub Discussions](https://github.com/seanchatmangpt/jotp/discussions)
- [Maven Central](https://search.maven.org/artifact/io.github.seanchatmangpt/jotp)

### External Links

- [Java 26 Documentation](https://openjdk.org/projects/jdk/26/)
- [Erlang/OTP Documentation](https://www.erlang.org/doc/)
- [Virtual Threads (JEP 425)](https://openjdk.org/jeps/425)
- [Pattern Matching for Java](https://openjdk.org/jeps/406)
- [Maven Daemon](https://github.com/apache/maven-mvnd)

---

**Maintained by**: JOTP Community
**Last Updated**: 2025-03-14
**Version**: 1.0.0-Alpha
