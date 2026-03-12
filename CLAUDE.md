# JOTP — Java OTP Primitives

Java 26 JPMS library (`io.github.seanchatmangpt.jotp`) implementing Erlang/OTP
fault-tolerance patterns using virtual threads and sealed types.

## Toolchain

```bash
# Verify on session start
java -version 2>&1 | grep 26        # must be OpenJDK 26
mvnd --version | grep mvnd          # must be 2.0.0-rc-3

# Paths
JAVA_HOME=/usr/lib/jvm/openjdk-26
mvnd: /root/.mvnd/mvnd-2.0.0-rc-3/bin/mvnd  (also /opt/mvnd2/bin/mvnd)
cargo: /root/.cargo/bin/cargo
```

Setup runs automatically on session start via `.claude/setup.sh`. To run manually:
```bash
bash .claude/setup.sh
```

## Build

**Primary — dx.sh (preferred):**
```bash
./dx.sh compile          # compile
./dx.sh test             # unit tests
./dx.sh all              # full build + guards
./dx.sh validate         # guard scan only
```

**Direct mvnd:**
```bash
mvnd test                          # unit tests (*Test.java)
mvnd verify                        # all tests + quality checks
mvnd test -Dtest=ProcTest          # single test class
mvnd verify -Dit.test=ProcIT       # single integration test
mvnd spotless:apply -q             # format manually if needed
```

**Never use raw `mvn` or `./mvnw`** — use `mvnd`.

## Guard Rules — NEVER write these in `src/main/java`

The PostToolUse hook scans every Java edit. Violations block the build.

| Pattern | Forbidden | Fix |
|---------|-----------|-----|
| H_TODO | `// TODO`, `// FIXME`, `// HACK`, `// LATER` | Implement now or throw `UnsupportedOperationException` |
| H_MOCK | Class/method named `Mock*`, `Stub*`, `Fake*` | Delete or implement real |
| H_STUB | `return "";`, `return null; // stub` | Real impl or throw |

Test files (`*Test.java`, `*IT.java`) are excluded from guard scanning.

## Code Quality

- **Spotless** (Google Java Format, AOSP style) runs automatically after every Java
  file edit via PostToolUse hook. Never run it manually.
- **Compile-time**: `spotless:check` runs at compile phase — hooks prevent failures.
- `--enable-preview` required at compile and runtime (already configured in pom.xml).

## Test Conventions

| Kind | Naming | Runner |
|------|--------|--------|
| Unit | `*Test.java` | surefire (`mvnd test`) |
| Integration | `*IT.java` | failsafe (`mvnd verify`) |

All tests run in parallel (JUnit 5 dynamic strategy, concurrent mode).

**Test libraries:** JUnit 5, AssertJ (`implements WithAssertions`), jqwik
(`@Property`/`@ForAll`), Instancio, ArchUnit, Awaitility.

**Result<T,E>:** Use `Result.of(supplier)` to wrap throwing ops. Supports `map`,
`flatMap`, `fold`, `recover`, `peek`.

## OTP Primitives

| Erlang/OTP | JOTP | Notes |
|---|---|---|
| `spawn/3` | `Proc.spawn(init, handler, args)` | Virtual-thread mailbox |
| `link/2` | `Proc.link(pid)` + `trapExits(true)` | Bilateral crash propagation |
| `gen_server:call` | `Proc.ask(msg, timeout)` | Sync request-reply |
| `gen_statem` | `StateMachine<S,E,D>` | Sealed `Transition` hierarchy |
| `supervisor` | `Supervisor(strategy, children)` | ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE |
| `pmap` | `Parallel` | `StructuredTaskScope` fan-out |
| `monitor/2` | `ProcMonitor.monitor(pid)` | Unilateral DOWN, no kill |
| `global:register_name` | `ProcRegistry.register(name, pid)` | Auto-deregisters on exit |
| `timer:send_after` | `ProcTimer.sendAfter(delay, msg)` | Timed delivery |
| `crash_recovery` | `CrashRecovery` | Let-it-crash + retry |
| `trap_exit` | `Proc.trapExits(true)` | Exit signals as messages |
| `sys` | `ProcSys` | Inspect/suspend/resume |
| `proc_lib` | `ProcLib` | `initAck()` startup handshake |
| `gen_event` | `EventManager<E>` | Independent handler crashes |
| `gen_server` | `Proc<S,M>` | Core primitive |

All primitives use sealed types for exhaustive pattern matching.

## Claude Code Config

**Hooks (automatic):**
- SessionStart: runs `setup.sh`, shows git status + branch + commits
- PostToolUse (Edit/Write on `.java`): Spotless format + guard scan

**Permissions pre-approved:** `mvnd *`, `./mvnw *`, `git *`, `./dx.sh *`, `cargo build *`

**Reference docs:**
- Architecture: @.claude/ARCHITECTURE.md
- Integration patterns: @.claude/INTEGRATION-PATTERNS.md
- Skills (/simplify, /loop, /claude-api): @.claude/SKILLS.md
- Agents (Explore, Plan): @.claude/AGENTS.md
- Hooks detail: @.claude/HOOKS.md
