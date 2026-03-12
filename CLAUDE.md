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

## Reference

@.claude/ARCHITECTURE.md · @.claude/SKILLS.md · @.claude/AGENTS.md · @.claude/HOOKS.md

OTP primitives + Java 26 patterns load from `.claude/rules/java-source.md` when editing Java files.
