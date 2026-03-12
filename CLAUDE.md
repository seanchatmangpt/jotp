# JOTP

Java 26 OTP library (`io.github.seanchatmangpt.jotp`). Always `mvnd`, never `mvn`. Setup: `bash .claude/setup.sh`.

## Build

`./dx.sh all` · `mvnd test` · `mvnd verify` · `mvnd test -Dtest=Foo` · `mvnd verify -Dit.test=FooIT`

## Guards — NEVER in `src/main/java`

PostToolUse hook enforces on every edit. Fix: implement or `throw new UnsupportedOperationException()`.

- **H_TODO** — `// TODO`, `// FIXME`, `// HACK`, `// LATER`
- **H_MOCK** — class/method named `Mock*`, `Stub*`, `Fake*`
- **H_STUB** — `return "";` or `return null; // stub`

Tests (`*Test.java`, `*IT.java`) are excluded from scanning.

## Quality + Tests

Spotless (Google Java Format AOSP) auto-formats after every `.java` edit — never run manually.
`*Test.java` unit (surefire) · `*IT.java` integration (failsafe) · all parallel.
AssertJ (`implements WithAssertions`) · jqwik (`@Property/@ForAll`) · Awaitility · `Result.of()`.

## OTP Application Equivalence (added post-merge)

Six new classes implement the Erlang/OTP `application` module in pure Java:

| Erlang/OTP | Java class | Role |
|---|---|---|
| `application_controller` / `application:start/2` | `ApplicationController` | Load · start · stop · query lifecycle |
| `.app` resource file | `ApplicationSpec` | Metadata · deps · callback binding (record + Builder) |
| `-behaviour(application)` | `ApplicationCallback<S>` | `start/2` + `stop/1` functional interface |
| `normal \| {takeover,N} \| {failover,N}` | `StartType` | Sealed start type (exhaustive switch) |
| `permanent \| transient \| temporary` | `RunType` | Termination cascade enum |
| `{Name, Desc, Vsn}` tuple | `ApplicationInfo` | Loaded/running app metadata record |

`ApplicationController.reset()` clears state between tests (`@BeforeEach`).

### ApplicationController Method Reference

| Method | Erlang equivalent | Notes |
|---|---|---|
| `load(spec)` | `application:load/1` | Load spec without starting |
| `unload(name)` | `application:unload/1` | Throws if running |
| `start(name)` | `application:start/1` | TEMPORARY run type |
| `start(name, RunType)` | `application:start/2` | Start with cascade semantics |
| `start(name, RunType, StartType)` | `application:start/2` | Passes Normal/Takeover/Failover to callback |
| `stop(name)` | `application:stop/1` | Normal stop; abnormal: stop(name, true) |
| `stop(name, true)` | crash/abnormal exit | TRANSIENT cascades like PERMANENT |
| `restart(name)` | `application:restart/1` | Hot reconfiguration |
| `loadedApplications()` | `application:loaded_applications/0` | Includes running |
| `whichApplications()` | `application:which_applications/0` | Running only |
| `getEnv(app, key)` | `application:get_env/2` | Config lookup; returns Optional |
| `getEnv(app, key, default)` | `application:get_env/3` | Config lookup; returns value or default |
| `setEnv(app, key, value)` | `application:set_env/3` | Runtime override |
| `unsetEnv(app, key)` | `application:unset_env/2` | Removes override; spec env visible again |
| `getKey(app, key)` | `application:get_key/2` | Spec metadata |
| `reset()` | — | Test isolation; call in @BeforeEach |
| `-behaviour(application)` | `ApplicationCallback<S>` | start/2 + stop/1 sealed switch |
| `.app resource file` | `ApplicationSpec` | Builder with 9 fields |

## Reference

@.claude/ARCHITECTURE.md · @.claude/SKILLS.md · @.claude/AGENTS.md · @.claude/HOOKS.md

OTP primitives + Java 26 patterns load from `.claude/rules/java-source.md` when editing Java files.
