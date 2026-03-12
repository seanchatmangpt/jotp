# CLAUDE.md: JOTP Enterprise Solution Architecture

**This guide is for Fortune 500 solution architects, CTOs, and platform engineers** evaluating JOTP as a mission-critical technology decision.

## Quick Navigation

### 🎯 For Decision Makers (CTOs, Architects)

**Start here:** [`.claude/ARCHITECTURE.md`](./.claude/ARCHITECTURE.md)
- Executive summary of JOTP positioning vs. Erlang/OTP, Akka, Go, Rust
- Seven enterprise fault-tolerance patterns with code examples
- Multi-tenant SaaS architecture (isolation SLA)
- Competitive decision matrix: when to choose JOTP
- Acquisition strategy for teams migrating from other platforms

### 🔧 For Operations & SRE

**Start here:** [`.claude/SLA-PATTERNS.md`](./.claude/SLA-PATTERNS.md)
- Meeting 99.95%+ SLA with JOTP supervisor trees
- Operational excellence patterns (observability, graceful degradation)
- Incident runbooks: timeout loops, memory leaks, cascading restarts
- Monitoring checklist (Golden Signals: utilization, saturation, errors)
- Disaster recovery (RTO/RPO) and blue-green deployment

### 🏗️ For Engineering Teams (Brownfield Adoption)

**Start here:** [`.claude/INTEGRATION-PATTERNS.md`](./.claude/INTEGRATION-PATTERNS.md)
- Phased adoption strategy (assessment → pilot → scale → ecosystem)
- Integrating JOTP into existing Spring Boot systems
- Coordinated multi-service sagas (state machine patterns)
- Dual-write gradual migration (zero-downtime switchover)
- Team training program (4-week curriculum)
- Rollback plan if Phase 1 fails

### 📚 For Developers Using Claude Code

**Quick reference:**
- **Plan Mode:** [`.claude/PLAN-MODE.md`](./.claude/PLAN-MODE.md) — Five-phase planning workflow
- **Skills:** [`.claude/SKILLS.md`](./.claude/SKILLS.md) — `/simplify`, `/loop`, `/claude-api`, etc.
- **Agents:** [`.claude/AGENTS.md`](./.claude/AGENTS.md) — Explore/Plan agents for codebase research
- **Hooks:** [`.claude/HOOKS.md`](./.claude/HOOKS.md) — SessionStart, PostToolUse automation

---

## JOTP: The Strategic Synthesis

**Problem Statement:** Enterprise teams face a false choice:
- **Choose Erlang/OTP:** Get fault tolerance, lose Java ecosystem
- **Choose Java:** Get ecosystem, lose OTP fault tolerance
- **Choose Go:** Get concurrency, lose supervision model entirely
- **Choose Akka:** Get actors, but complex API + licensing concerns

**JOTP Solution:** Bring the 20% of OTP responsible for 80% of production reliability into Java 26, eliminating this choice.

**Result:** OTP-equivalent fault tolerance + Java ecosystem + type safety beyond Erlang + 12M developer talent pool.

---

## Why Fortune 500 Organizations Choose JOTP

| Dimension | Erlang/OTP | Go | Akka | **JOTP** |
|-----------|------------|-----|------|---------|
| **Fault tolerance** | 5/5 | 0/5 | 4/5 | **5/5** |
| **Compile-time safety** | 2/5 | 2/5 | 4/5 | **5/5** |
| **JVM ecosystem** | 0/5 | 0/5 | 2/5 | **5/5** |
| **Talent availability** | 0.5M | 3M | 2M | **12M** |
| **Java Spring integration** | ✗ | ✗ | Partial | **Native** |

**Competitive moat:** Type-safe message passing + Java ecosystem + 40-year OTP battle-tested patterns.

---

## Proxy & Java 26 Setup (Automatic via SessionStart hook)

The `.claude/setup.sh` script runs automatically on every session start and handles:

1. **OpenJDK 26** — Downloads and installs to `/usr/lib/jvm/openjdk-26` if not present
2. **mvnd 2.0.0-rc-3** — Downloads Maven Daemon if not present
3. **Maven proxy** — Auto-generates `~/.m2/settings.xml` with proxy credentials extracted from `JAVA_TOOL_OPTIONS` or `https_proxy` environment variables
4. **maven-proxy-v2.py bridge** — Starts if `https_proxy` env var is set

No manual setup is needed. If the session start hook fails, run manually:
```bash
bash .claude/setup.sh
```

## Build Tool: mvnd (Maven Daemon, Maven 4) — REQUIRED

**mvnd is mandatory.** Raw `mvn`/`./mvnw` is not used — mvnd 2.0.0-rc-3 (bundling Maven 4) is the build tool.

**Why mvnd:**
- **Daemon mode:** Persistent JVM eliminates startup overhead (30-40% faster builds)
- **Incremental builds:** Classpath caching + parallelization
- **Maven 4 support:** Latest features without legacy compatibility burden

**Install once:**
```bash
# Download mvnd 2.0.0-rc-3 (Linux x86_64)
# https://github.com/apache/maven-mvnd/releases/download/2.0.0-rc-3/maven-mvnd-2.0.0-rc-3-linux-amd64.tar.gz
# Symlink: ln -sf /path/to/mvnd/bin/mvnd /usr/local/bin/mvnd
```

**Build optimization tips:**

- **Parallel compilation:** `-T1C` (default, auto-detect cores):
  ```bash
  mvnd compile -T1C
  mvnd verify -T2     # Override: 2 threads for constrained environments
  ```

- **Build cache warmup** (before long sessions):
  ```bash
  mvnd compile -q -T1C              # Warm classpath cache
  mvnd compile -q -T1C --offline    # Offline mode (no downloads)
  ```

- **Maven 4 project grouping:**
  ```bash
  mvnd build --include '::core'      # Build only core module
  mvnd verify --exclude '::integration'
  ```

- **Offline mode** (use cached artifacts):
  ```bash
  mvnd verify --offline
  ```

- **Spotless integration:** The PostToolUse hook auto-runs `spotless:apply` after Java edits. To manually check formatting:
  ```bash
  mvnd spotless:check               # Check without modifying
  mvnd spotless:apply -q            # Apply and quiet output
  ```
  Why PostToolUse runs spotless:apply: Spotless formatting is enforced at compile phase; the hook prevents build failures by formatting proactively.

## Commands

```bash
./mvnw test              # Run unit tests only
./mvnw verify            # Run all tests (unit + integration) and quality checks
./mvnw spotless:apply    # Format code (Google Java Format, AOSP style)
./mvnw spotless:check    # Check formatting without applying
./mvnw jshell:run        # Start interactive JShell REPL
./mvnw package -Dshade   # Build a fat/uber JAR (shade profile)
./mvnw verify -Ddogfood  # Run dogfood: generate-check + compile + test + report
bin/mvndw verify          # Same as ./mvnw but with Maven Daemon (faster)
```

**Run a single test class:**
```bash
mvnd test -Dtest=MathsTest
mvnd verify -Dit.test=MathsIT  # integration test
```

## Claude Code Features

This project integrates advanced Claude Code capabilities for modern development workflows.

### Skills

**Available skills** provide specialized capabilities via `/skill-name` shortcuts:

- **`/simplify`** — Code review automation. Run after making Java changes to review for reuse, quality, and efficiency:
  ```bash
  # After editing code, invoke:
  /simplify
  ```

- **`/claude-api`** — Build applications using Claude API and Anthropic SDK. Auto-triggers when code imports `anthropic` or `@anthropic-ai/sdk`:
  ```java
  import com.anthropic.client.Anthropic;
  // /claude-api automatically available
  ```

- **`/loop`** — Run recurring tasks at specified intervals:
  ```bash
  /loop 5m /test              # Run tests every 5 minutes
  /loop 10m mvnd verify       # Full build every 10 minutes
  ```

- **`/session-start-hook`** — Set up new repositories for Claude Code on the web. Use when establishing SessionStart configuration.

### Plan Mode

**Plan mode** organizes complex, multi-step tasks into structured phases:

1. **Explore Phase** — Research the codebase and requirements
2. **Design Phase** — Architect solution using Plan agent
3. **Review Phase** — Validate assumptions with user
4. **Write Plan** — Finalize plan file (`.claude/plans/`)
5. **Exit Plan Mode** — Request user approval before implementation

**When to use plan mode:**
- Complex features requiring 3+ steps
- Significant architectural decisions
- Refactoring multiple components
- Tasks requiring codebase exploration

**Plan files** are stored in `.claude/plans/` and use Markdown format.

### Agents

Claude Code provides specialized agents for parallel exploration and design:

- **`Explore` agent** — Investigates codebases efficiently:
  ```
  subagent_type=Explore
  thoroughness: quick | medium | very-thorough
  ```
  Use for: File pattern searches, understanding existing code, identifying patterns.

- **`Plan` agent** — Designs implementation strategies:
  ```
  subagent_type=Plan
  ```
  Use for: Architecting solutions, considering trade-offs, breaking down complexity.

**Parallel launching:** Launch multiple agents in a single message for efficiency:
```
Agent(description, prompt, subagent_type=Explore)  # Agent 1
Agent(description, prompt, subagent_type=Plan)     # Agent 2
```

**Reference:** See `.claude/PLAN-MODE.md`, `.claude/AGENTS.md`, `.claude/SKILLS.md` for detailed guides.

## Build Commands (dx.sh)

The `dx.sh` script provides a unified build interface that integrates with the yawl submodule when available, or falls back to standalone Maven builds.

```bash
./dx.sh compile          # Compile changed modules
./dx.sh test             # Run tests
./dx.sh all              # Full build + validation (guards)
./dx.sh validate         # Run guard validation only
./dx.sh deploy           # Deploy to cloud (OCI default)
```

**With yawl submodule**: Delegates to `yawl/scripts/dx.sh` for full H+Q validation gates.
**Without yawl**: Uses `dx-standalone.sh` for basic Maven builds with simplified guards.

### Guard Validation

The guard system detects forbidden patterns in production code:

| Pattern | Description | Fix |
|---------|-------------|-----|
| H_TODO | Deferred work markers (TODO, FIXME, etc.) | Implement or remove |
| H_MOCK | Mock/stub/fake implementations | Delete or implement real service |
| H_STUB | Empty/placeholder returns | Throw UnsupportedOperationException |

**Build guard system:**
```bash
cd guard-system && cargo build --release
```

## Architecture

**Java 26 JPMS library** (`io.github.seanchatmangpt.jotp` module) targeting Java 26 with preview features enabled (`--enable-preview`). JDK: OpenJDK 26 (auto-installed by `.claude/setup.sh`).

### Java 26 Language Features

**Pattern Matching** (beyond sealed types):
- **Switch expressions** with sealed type hierarchies for exhaustiveness checking
- **instanceof patterns** with binding variables
- **Record patterns** for nested destructuring
Example: `if (shape instanceof Circle(var radius))` extracts field directly.

**Sealed Types** — Restrict inheritance in OTP primitives:
```java
public sealed interface Transition<S, D>
    permits Transition.Keep, Transition.Next, Transition.Stop { }
```
Enables compiler to verify all cases handled in switch expressions.

**Virtual Threads & Structured Concurrency**:
- `Parallel` primitive uses `StructuredTaskScope` (preview API in Java 21, refined for Java 26)
- Virtual threads provide millions of lightweight processes suitable for OTP supervisor trees
- Preview feature: `--enable-preview` required at compile and runtime

**Preview Features:**
The jotp library uses several preview APIs that require `--enable-preview`:
- Virtual Threads (JEP 425, likely final in Java 23+)
- Structured Concurrency (JEP 453)
- Scoped Values (JEP 429, alternative to ThreadLocal)
- Pattern Matching enhancements (multi-stage rollout through Java 26)

Check [OpenJDK Enhancement Proposals](https://openjdk.org/jeps/) for graduation status.

**Test separation:**
- Unit tests: `*Test.java` — run by maven-surefire-plugin via `./mvnw test`
- Integration tests: `*IT.java` — run by maven-failsafe-plugin during `verify` phase

**Test execution:** JUnit 5 is configured for full parallel execution (dynamic strategy, concurrent mode for both methods and classes) via `src/test/resources/junit-platform.properties`.

**Test libraries available:** JUnit 5, AssertJ (use `implements WithAssertions`), jqwik (property-based testing via `@Property`/`@ForAll`), Instancio (test data generation), ArchUnit (architecture rules), Awaitility (async assertions).

**`Result<T, E>` type:** A sealed interface with `Success`/`Failure` variants providing railway-oriented programming. Use `Result.of(supplier)` to wrap throwing operations. Supports `map`, `flatMap`, `fold`, `recover`, `peek`, and `orElseThrow`.

**Formatting:** Spotless with Google Java Format (AOSP style) runs automatically at compile phase. The PostToolUse hook (see below) auto-runs `spotless:apply` after every Java file edit — do not run it manually.

**Joe Armstrong / Erlang/OTP patterns** — fifteen primitives in `io.github.seanchatmangpt.jotp`:

| Erlang/OTP | Java 26 Primitive | Key Characteristics |
|---|---|---|
| `spawn/3` | `Proc.spawn(init, handler, args)` | Spawn lightweight process with state handler |
| `link/2` | `Proc.link(pid)` + `Proc.trapExits(true)` | Bilateral crash propagation; both die if one fails |
| `gen_server:call` | `Proc.ask(msg, timeout)` | Synchronous request-reply with timeout |
| `gen_statem` | `StateMachine<S,E,D>` | State/event/data separation + sealed `Transition` hierarchy |
| `supervisor` | `Supervisor(strategy, children)` | Supervision tree: ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE / SIMPLE_ONE_FOR_ONE; per-child `ChildSpec` (RestartType, Shutdown, ChildType); AutoShutdown; dynamic `startChild`/`terminateChild`/`deleteChild`/`whichChildren` |
| `pmap` | `Parallel` | Structured fan-out with fail-fast (uses `StructuredTaskScope`) |
| `monitor/2` | `ProcMonitor.monitor(pid)` | Unilateral DOWN notifications; doesn't kill watcher |
| `global:register_name` | `ProcRegistry.register(name, pid)` | Global name table with auto-deregistration |
| `timer:send_after` | `ProcTimer.sendAfter(delay, msg)` | Timed message delivery |
| `crash_recovery` | `CrashRecovery` | "let it crash" + supervised retry |
| `trap_exit` | `Proc.trapExits(true)` | Catch exit signals as messages |
| `sys` module | `ProcSys` | Process introspection: state, suspend, resume, statistics |
| `proc_lib` | `ProcLib` | Startup handshake; `initAck()` blocks caller |
| `gen_event` | `EventManager<E>` | Typed event manager; crashes handlers independently |
| `gen_server` | `Proc<S,M>` | **Core primitive:** Virtual-thread mailbox + pure state handler |

**Note:** All primitives use sealed types for exhaustiveness checking (Java 26 pattern matching).

Details on each primitive:

- `Proc<S,M>` — lightweight process: virtual-thread mailbox + pure state handler (OTP: `spawn/3`)
- `ProcRef<S,M>` — stable Pid: opaque handle that survives supervisor restarts
- `Supervisor` — supervision tree: ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE / SIMPLE_ONE_FOR_ONE (dynamic homogeneous pools) with sliding restart window; per-child `ChildSpec<S,M>` declares `RestartType` (PERMANENT/TRANSIENT/TEMPORARY), `Shutdown` (BrutalKill/Timeout/Infinity), and `ChildType` (WORKER/SUPERVISOR); `AutoShutdown` (NEVER/ANY_SIGNIFICANT/ALL_SIGNIFICANT) controls supervisor lifecycle on significant-child exit; dynamic management via `startChild(ChildSpec)`, `startChild()`, `terminateChild(id)`, `terminateChild(ref)`, `deleteChild(id)`, `whichChildren()`; factory methods `Supervisor.create()` and `Supervisor.createSimple()`
- `CrashRecovery` — "let it crash" + supervised retry via isolated virtual threads
- `StateMachine<S,E,D>` — gen_statem: state/event/data separation + sealed `Transition` hierarchy
- `ProcLink` — process links: bilateral crash propagation (`link/1`, `spawn_link/3`)
- `Parallel` — structured fan-out with fail-fast semantics (`StructuredTaskScope`, OTP: `pmap`)
- `ProcMonitor` — unilateral DOWN notifications: `monitor(process, Pid)` / `demonitor/1`; fires on any exit (normal or abnormal); does NOT kill the monitoring side (unlike links)
- `ProcRegistry` — global name table: `register/2`, `whereis/1`, `unregister/1`, `registered/0`; auto-deregisters when a process terminates
- `ProcTimer` — timed message delivery: `timer:send_after/3`, `timer:send_interval/3`, `timer:cancel/1`
- `ExitSignal` — exit signal record delivered as a mailbox message when a process traps exits (`process_flag(trap_exit, true)`)
- `ProcSys` — sys module: `get_state`, `suspend`, `resume`, `statistics` — process introspection without stopping
- `ProcLib` — proc_lib startup handshake: `start_link` blocks until child calls `initAck()`, returning `StartResult.Ok | Err`
- `EventManager<E>` — gen_event: typed event manager with `addHandler`, `notify`, `syncNotify`, `deleteHandler`, `call`; crashes handlers without killing the manager
- `Proc.trapExits(boolean)` / `Proc.ask(msg, timeout)` — `process_flag(trap_exit)` and timed `gen_server:call` added to core `Proc`

## Claude Code Configuration (`.claude/`)

`.claude/settings.json` is checked in and applies to all contributors using Claude Code.

### Hooks

**SessionStart** — runs when a session begins:
- Displays `git status`, current branch, and last 5 commits so Claude has immediate project context
- Verifies the Java version (must be 26 for `--enable-preview`)

**PostToolUse (Edit/Write on `.java` files)** — runs automatically after every Java file edit:
- Auto-runs `./mvnw spotless:apply -q` after each edit
- Since `spotless:check` runs at compile phase, this prevents build failures without any manual step

### Permissions

`mvnd *`, `./mvnw *`, and `git *` are pre-approved; Claude Code will not prompt for confirmation on these commands.

### Optional: Pre-warm the build cache

For long sessions, warm the build cache before starting:

```bash
mvnd compile -q -T1C
```

## Troubleshooting & FAQ

### Build Issues

**Build times are slow**
- Root cause: Cold JVM startup or classpath cache miss
- Solution: Warm the build cache (`mvnd compile -q -T1C --offline`)
- Check daemon is running: `jps | grep mvnd`

**"Spotless formatting violations" at compile**
- Root cause: PostToolUse hook may not have run, or manual code entry
- Solution: Run `mvnd spotless:apply -q` manually; it auto-runs after edits via hook
- Prevention: Use `/simplify` skill after making changes

**Build timeout**
- Solution: Increase logging verbosity and retry:
  ```bash
  mvnd verify -Dorg.slf4j.simpleLogger.defaultLogLevel=error
  ```

**"Module not found" after git pull**
- Root cause: Classpath cache is stale
- Solution: Clear and rebuild:
  ```bash
  rm -rf ~/.m2/repository/io/github/seanchatmangpt
  mvnd clean compile
  ```

### Java & Preview Features

**"Java 26 not found" error at startup**
- Root cause: OpenJDK 26 installation failed
- Solution: Rerun `.claude/setup.sh`:
  ```bash
  bash .claude/setup.sh
  ```

**"--enable-preview not recognized"**
- Root cause: Wrong JDK (not Java 26)
- Verify: `java -version` should show "21.0.10" (OpenJDK 26) or later
- Solution: Check `JAVA_HOME=/usr/lib/jvm/openjdk-26`

**Virtual thread limit exceeded**
- Symptom: "Cannot create virtual thread" or thread pool saturation
- Root cause: Too many concurrent processes in supervisor
- Solution: Tune JVM flags:
  ```bash
  mvnd verify -XX:+UnlockDiagnosticVMOptions -XX:PreviewFeatures=...
  ```

### Hooks & Automation

**SessionStart hook fails**
- Root cause: Java 26 not installed, or proxy misconfiguration
- Debug: Run `.claude/setup.sh` manually and check output
- Check proxy: Verify `~/.m2/settings.xml` contains `<proxy>` blocks

**PostToolUse hook not triggering**
- Root cause: Edit was not to `.java` file, or hook is disabled
- Solution: Manually run after edits:
  ```bash
  mvnd spotless:apply -q
  bash .claude/hooks/simple-guards.sh
  ```

**Guard validation failing (H_TODO, H_MOCK, H_STUB)**
- Root cause: Code contains forbidden patterns
- View violations: Check `.claude/hooks/simple-guards.sh` output
- Fix: Address violations in code (implement TODO, remove mocks, etc.)
- Temporary disable: Set `SKIP_GUARDS=1` (discouraged; for debugging only):
  ```bash
  SKIP_GUARDS=1 mvnd verify
  ```

### Common Patterns

**Running a single test**
```bash
mvnd test -Dtest=MyTest
mvnd verify -Dit.test=MyIT       # integration test
```

**Checking Java 26 status**
```bash
java -version
javac -version
jshell --version
```

**Using JShell REPL**
```bash
mvnd jshell:run
```

## Code Generation (ggen / jgen)

This project wraps [seanchatmangpt/ggen](https://github.com/seanchatmangpt/ggen) as a code generation engine for Java 26 migration.

**Install ggen:**
```bash
cargo install ggen-cli --features paas,ai
```

**jgen CLI wrapper:**
```bash
bin/jgen generate -t core/record -n Person -p com.example.model
bin/jgen list                          # List all 72 templates
bin/jgen list --category patterns      # List templates in a category
bin/jgen migrate --source ./legacy     # Detect legacy patterns (grep-based)
bin/jgen refactor --source ./legacy    # Full analysis: score + ranked commands
bin/jgen refactor --source ./legacy --plan   # Saves executable migrate.sh
bin/jgen refactor --source ./legacy --score  # Score-only modernization report
bin/jgen verify                        # Compile + format + test check
```

**Template categories (72 templates, 108 patterns):**
- `core/` — 14 templates: records, sealed types, pattern matching, streams, lambdas, var, gatherers
- `concurrency/` — 5 templates: virtual threads, structured concurrency, scoped values
- `patterns/` — 17 templates: all GoF patterns reimagined for modern Java (builder, factory, strategy, state machine, visitor, etc.)
- `api/` — 6 templates: HttpClient, java.time, NIO.2, ProcessBuilder, collections, strings
- `modules/` — 4 templates: JPMS module-info, SPI, qualified exports, multi-module
- `testing/` — 12 templates: JUnit 5, AssertJ, jqwik, Instancio, ArchUnit, Awaitility, Mockito, BDD, Testcontainers
- `error-handling/` — 3 templates: Result<T,E> railway, functional errors, Optional↔Result
- `build/` — 7 templates: POM, Maven wrapper, Spotless, Surefire/Failsafe, build cache, CI/CD
- `security/` — 4 templates: modern crypto, encapsulation, validation, Jakarta EE migration

**Architecture:**
- `schema/*.ttl` — RDF ontologies defining Java type system, patterns, concurrency, modules, migration rules
- `queries/*.rq` — SPARQL queries extracting data from ontologies
- `templates/java/**/*.tera` — Tera templates rendering Java 26 code
- `ggen.toml` — ggen project configuration
- `bin/jgen` — CLI wrapper for Java developers
- `bin/dogfood` — validates templates produce compilable, testable Java code
- `bin/mvndw` — Maven Daemon wrapper (faster builds with persistent JVM)

## Innovation Engine (`io.github.seanchatmangpt.jotp.dogfood.innovation`)

Five coordinated analysis engines power the automated refactor pipeline:

| Class | Role |
|---|---|
| `OntologyMigrationEngine` | Analyzes Java source against 12 ontology-driven migration rules; returns sealed `MigrationPlan` hierarchy |
| `ModernizationScorer` | Scores source files 0-100 across 40+ modern/legacy signal detectors; ranks by ROI |
| `TemplateCompositionEngine` | Composes multiple Tera templates into coherent features (CRUD, value objects, service layers) |
| `BuildDiagnosticEngine` | Maps compiler error output to concrete `DiagnosticFix` suggestions (10 fix subtypes) |
| `LivingDocGenerator` | Parses Java source into structured `DocElement` hierarchy; renders Markdown documentation |
| `RefactorEngine` | **Orchestrator**: chains all engines into a single `RefactorPlan` with per-file scores, `JgenCommand` lists, `toScript()`, and `summary()` |

**One-command refactor of any codebase:**
```java
// Java API
var plan = RefactorEngine.analyze(Path.of("./legacy/src"));
System.out.println(plan.summary());
Files.writeString(Path.of("migrate.sh"), plan.toScript());
```
```bash
# CLI
bin/jgen refactor --source ./legacy/src --plan  # writes migrate.sh
bash migrate.sh                                  # applies migrations
```

## PhD Thesis

`docs/phd-thesis-otp-java26.md` — *"OTP 28 in Pure Java 26: A Formal Equivalence and Migration Framework for Enterprise-Grade Fault-Tolerant Systems"*

Establishes formal equivalence between the 7 OTP primitives and Java 26, benchmarks BEAM vs. JVM under fault conditions, provides migration paths from Elixir, Go, Rust, and Scala/Akka, and frames this as a blue ocean strategy for Oracle ecosystem influencers.

## Dogfood (Eating Our Own Dog Food)

The `io.github.seanchatmangpt.jotp.dogfood` package contains real Java code rendered from templates, proving they compile and pass tests.

**Dogfood commands:**
```bash
bin/dogfood generate     # Check all dogfood source files exist
bin/dogfood report       # Show template coverage report
bin/dogfood verify       # Full pipeline: check + compile + test + report
./mvnw verify -Ddogfood  # Same via Maven (includes dogfood in build lifecycle)
bin/mvndw verify -Ddogfood  # Same via Maven Daemon (fastest)
```

**Dogfood coverage** (one example per template category):
- `core/` → `Person.java` (record with validation + builder)
- `concurrency/` → `VirtualThreadPatterns.java` (virtual thread utilities)
- `patterns/` → `TextTransformStrategy.java` (functional strategy pattern)
- `api/` → `StringMethodPatterns.java` (modern String API) + `StringMethodPatternsTest.java`
- `error-handling/` → `ResultRailway.java` (sealed Result type) + `ResultRailwayTest.java`
- `security/` → `InputValidation.java` (preconditions + error accumulation) + `InputValidationTest.java`
- `testing/` → `PersonTest.java`, `PersonProperties.java` (JUnit 5 + jqwik)
- `innovation/` → all 6 engine classes + full test suites (`RefactorEngineTest`, etc.)
- `build/` → validated implicitly via pom.xml
- `modules/` → validated implicitly via module-info.java

## Maven Daemon (mvnd)

`bin/mvndw` wraps [Apache Maven Daemon](https://github.com/apache/maven-mvnd) for faster builds. It auto-downloads mvnd on first use. Configuration in `.mvn/daemon.properties`.
