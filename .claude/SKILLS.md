# Claude Code Skills Reference

Skills are specialized tools available via `/skill-name` commands. This project includes the following skills:

## /simplify

**Purpose:** Code review automation for quality and reusability.

**When to use:**
- After writing or modifying Java code
- To identify opportunities for refactoring or simplification
- To catch potential performance or quality issues

**Usage:**
```bash
# After making Java changes, invoke:
/simplify
```

**What it does:**
- Reviews code for reusable patterns
- Checks for code quality and efficiency
- Identifies potential improvements
- Suggests refactoring opportunities

**Example workflow:**
```java
// Edit a Java file with complex logic
// Then run:
/simplify
// Skill reviews changes and suggests improvements
```

---

## /claude-api

**Purpose:** Build applications with Claude API and Anthropic SDK.

**Auto-triggers when:**
- Code imports `anthropic`
- Code imports `@anthropic-ai/sdk`
- Code imports `claude_agent_sdk`

**When to use manually:**
- Starting a new Claude API project
- Integrating Claude models into existing code
- Building multi-agent systems with Claude Agent SDK

**Usage:**
```bash
# Auto-triggered on import, or manually:
/claude-api
```

**Available SDKs:**
- **Python:** `anthropic` — Sync/async Claude API client
- **JavaScript:** `@anthropic-ai/sdk` — Node.js/browser Claude client
- **Java:** `claude-agent-sdk` — Claude Agent SDK for Java

**Reference:** See [Anthropic Documentation](https://docs.anthropic.com) for API details.

---

## /loop

**Purpose:** Run recurring tasks at specified intervals.

**When to use:**
- Monitoring builds or tests during development
- Polling for status updates
- Running validation repeatedly (e.g., checking test results)
- Running the same command periodically

**Usage:**
```bash
/loop <interval> <command>

# Examples:
/loop 5m /test                    # Run tests every 5 minutes
/loop 10m mvnd verify             # Full build every 10 minutes
/loop 1m /simplify                # Code review every 1 minute
/loop 30s ./dx.sh validate        # Guard validation every 30 seconds
```

**Interval formats:**
- `s` — seconds (e.g., `30s`, `5s`)
- `m` — minutes (e.g., `5m`, `10m`)
- `h` — hours (e.g., `1h`)
- Default: 10 minutes if not specified

**Stopping a loop:**
- Press Ctrl+C to stop the recurring task

**Example workflow:**
```bash
# Start tests running every 5 minutes while you edit
/loop 5m mvnd test

# In another terminal, keep verifying while refactoring
/loop 10m /simplify
```

---

## /session-start-hook

**Purpose:** Set up new repositories for Claude Code on the web.

**When to use:**
- Initializing a new repository for team use
- Configuring SessionStart automation for CI/CD integration
- Setting up Claude Code web environment

**Usage:**
```bash
/session-start-hook
```

**What it does:**
- Configures `.claude/settings.json` for SessionStart
- Sets up automatic dependency installation
- Configures build tool initialization
- Ensures reproducible environments across team sessions

**Configuration includes:**
- JDK/Java version verification
- Build tool (mvnd) setup
- Git context (status, branch, recent commits)
- Environment variable initialization

---

## Tips for Using Skills

### Combining Skills

You can use multiple skills in a session:

```bash
# Warm up build cache
mvnd compile -q -T1C

# Start recurring tests
/loop 5m mvnd test

# After editing code
/simplify

# In another session, continue with Claude API work
/claude-api
```

### Skill Defaults

- **Interval:** `/loop` defaults to 10 minutes if not specified
- **Verbosity:** Skills output is minimal; errors are highlighted
- **Cancellation:** Most skills can be stopped with Ctrl+C

### Permissions

All skills are pre-approved in `.claude/settings.json`. No user confirmation is needed to invoke them.
