# JOTP — Developer Quick Reference

## Build Tool: mvnd (mandatory)
```bash
make compile          # compile sources
make test             # run unit tests (T=ClassName for single)
make verify           # unit + integration + quality checks
make format           # apply Spotless (Google Java Format AOSP)
make benchmark-quick  # JMH: 1 fork, 1 warmup, 2 iterations
make package          # build JAR (skips tests)
make guard-check      # run H_TODO/H_MOCK/H_STUB guard validation
make deploy           # full cloud deploy pipeline (CLOUD=oci default)
make help             # list all targets
```

## Setup (auto on session start)
```bash
bash .claude/setup.sh   # installs OpenJDK 26, mvnd, Maven proxy
```

## Architecture
- **Module:** `io.github.seanchatmangpt.jotp` (Java 26 JPMS, `--enable-preview` required)
- **Tests:** `*Test.java` → surefire (unit); `*IT.java` → failsafe (integration)
- **Formatting:** Spotless auto-runs via PostToolUse hook after every `.java` edit
- **15 OTP primitives:** `Proc`, `Supervisor`, `StateMachine`, `Parallel`, `ProcMonitor`, `ProcRegistry`, `ProcTimer`, `ProcLink`, `CrashRecovery`, `ProcSys`, `ProcLib`, `EventManager`, `ExitSignal`, `ProcRef`, `Proc.trapExits`

## Detailed Docs
- Architecture/strategy → `.claude/ARCHITECTURE.md`
- SLA/operations → `.claude/SLA-PATTERNS.md`
- Brownfield adoption → `.claude/INTEGRATION-PATTERNS.md`
- Plan mode / Skills / Agents / Hooks → `.claude/PLAN-MODE.md`, `.claude/SKILLS.md`, `.claude/AGENTS.md`, `.claude/HOOKS.md`
