# Claude Code Hooks Reference

Hooks are automated scripts that trigger on specific events. This project uses two primary hooks configured in `.claude/settings.json`.

## SessionStart Hook

**When it runs:** Every time a Claude Code session begins.

**Purpose:** Initialize the development environment with all necessary tools and context.

### What SessionStart Does

1. **Runs `.claude/setup.sh`**
   - Installs/verifies OpenJDK 26 at `/usr/lib/jvm/openjdk-26`
   - Installs/verifies mvnd 2.0.0-rc-3 at `/root/.mvnd/mvnd-2.0.0-rc-3/`
   - Configures Maven proxy via `~/.m2/settings.xml`
   - Symlinks: `/usr/local/bin/mvnd` → mvnd binary, `/opt/jdk` → JDK 26

2. **Displays Project Context**
   - Git status (modified files, untracked files)
   - Current branch name
   - Recent 5 commits (helps Claude understand project history)

3. **Verifies Java 26**
   - Ensures correct JDK version
   - Confirms preview features are available

### Setup.sh in Detail

**Location:** `.claude/setup.sh`

**Key components:**

| Component | Purpose |
|---|---|
| OpenJDK 26 Download | Fetch latest JDK 26 if not present |
| mvnd Download | Download Maven Daemon 2.0.0-rc-3 |
| Proxy Configuration | Auto-detect and configure Maven proxy from environment |
| Symlinks | Create predictable paths for tools |

**Environment detection:**

Setup.sh automatically detects proxy settings from:
1. `JAVA_TOOL_OPTIONS` environment variable (enterprise)
2. `https_proxy` / `HTTP_PROXY` environment variables
3. Falls back to no proxy if none found

**Maven Proxy Setup:**

```xml
<!-- Generated in ~/.m2/settings.xml -->
<settings>
  <proxies>
    <proxy>
      <id>egress-https</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>proxy.company.com</host>
      <port>3128</port>
      <username>...</username>
      <password>...</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
```

### Troubleshooting SessionStart

**Issue: "OpenJDK 26 not found"**
- Root cause: Download failed or network issue
- Solution: Rerun manually
  ```bash
  bash .claude/setup.sh
  ```
- Check downloads: `ls -la /usr/lib/jvm/`

**Issue: "Maven proxy config failed"**
- Root cause: Proxy settings not detected or invalid
- Solution: Check environment variables
  ```bash
  echo $JAVA_TOOL_OPTIONS
  echo $https_proxy
  ```
- Manual fix: Verify `~/.m2/settings.xml` is valid XML

**Issue: "mvnd not in PATH"**
- Root cause: Symlink not created
- Solution: Manually symlink
  ```bash
  ln -sf /root/.mvnd/mvnd-2.0.0-rc-3/bin/mvnd /usr/local/bin/mvnd
  ```

---

## PostToolUse Hook

**When it runs:** After every `Edit` or `Write` operation on `.java` files.

**Purpose:** Automatically format code and validate against guard rules immediately after edits.

### Hook Chain

When you edit a `.java` file, the hook runs in sequence:

```
1. Detect: Is this a .java file? ✓
2. Spotless Format: mvnd spotless:apply -q
3. Guard Validation: bash .claude/hooks/simple-guards.sh
```

### Why This Order

1. **Spotless first:** Format code before validation
2. **Guards second:** Check for forbidden patterns in formatted code
3. **Quiet output:** `-q` suppresses unnecessary logging

### Spotless (Google Java Format)

**What it does:**
- Enforces consistent code style
- AOSP Android Open Source Project style
- Automatically fixes indentation, spacing, imports

**Configuration:** Defined in `pom.xml` under `spotless-maven-plugin`

**Behavior:**
- Runs on every `.java` edit
- Modifies the file in-place
- Safe to run repeatedly (idempotent)

**Manual verification:**
```bash
mvnd spotless:check    # Check without modifying
mvnd spotless:apply -q # Apply formatting
```

### Guard Validation

**Location:** `.claude/hooks/simple-guards.sh`

**Purpose:** Detect forbidden patterns in production code.

**Forbidden patterns:**

| Pattern | Name | Meaning | How to Fix |
|---|---|---|---|
| `TODO\|FIXME\|XXX` | H_TODO | Deferred work marker | Implement or remove |
| `@Mock\|Mockito\|mock\(` | H_MOCK | Mock/stub implementation | Implement real service |
| `throw new UnsupportedOperationException\(\)` | H_STUB | Placeholder return | Implement method |

**How it works:**
1. Scans production code (not tests) in `src/main/**/*.java`
2. Detects patterns using Rust-based `dx-guard` binary
3. Reports violations with file:line
4. Exits with status 0 (warnings only) or 1 (blocking)

**Example violation output:**
```
core/auth/JwtValidator.java:42: H_TODO Found deferred work marker
  TODO: Add token expiration check
```

**Remediating violations:**

If guard validation fails:
1. Fix the violation in your code (implement, remove mock, implement stub)
2. Spotless hook will auto-run on next edit
3. Re-edit the file to trigger hook again
4. Build will pass once violations cleared

**Disabling guards temporarily:**

**Not recommended**, but for debugging:
```bash
SKIP_GUARDS=1 mvnd verify
```

---

## Hook Configuration

**File:** `.claude/settings.json`

**Schema:**
```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "..."
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write",  // Triggers on Edit or Write
        "hooks": [
          {
            "type": "command",
            "command": "..."
          }
        ]
      }
    ]
  }
}
```

**Pre-approved hooks:** Hooks in `.claude/settings.json` run without user confirmation.

---

## Custom Hooks

**Not applicable for this repository.** Do not add custom hooks without explicit project approval.

To add a custom hook:
1. Edit `.claude/settings.json`
2. Add hook configuration under `hooks.PostToolUse` or `hooks.SessionStart`
3. Specify `type: "command"` and shell command
4. Test in a session

---

## Advanced: Rust Guard System

The guard system is backed by a Rust binary (`dx-guard`) compiled from the `guard-system/` directory.

**Build the guard binary:**
```bash
cd guard-system && cargo build --release
```

**Location of binary:** `guard-system/target/release/dx-guard`

**Guard patterns:** Defined in Rust source via regex matching.

**Integration:** `.claude/hooks/simple-guards.sh` invokes `dx-guard` with directory and pattern arguments.

---

## Monitoring Hook Execution

**Check if hooks ran:**
- Review output after edits (spotless + guards feedback)
- Look for formatting changes in git diff
- Check for guard violation messages

**Re-run hooks manually:**
```bash
# Spotless formatting
mvnd spotless:apply -q

# Guard validation
bash .claude/hooks/simple-guards.sh
```

**Debug hook issues:**
```bash
# Run setup manually
bash .claude/setup.sh

# Run guards with verbose output
bash .claude/hooks/simple-guards.sh --verbose  # (if supported)
```

---

## Best Practices

1. **Let hooks run** — Don't disable guards unless necessary
2. **Fix violations promptly** — Address H_TODO, H_MOCK, H_STUB immediately
3. **Re-run spotless** — If formatting seems wrong, run `mvnd spotless:apply`
4. **Trust automation** — Hooks prevent manual formatting errors
5. **Monitor logs** — Check hook output for warnings or errors
