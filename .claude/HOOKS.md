# Hooks Quick Reference

## SessionStart — runs .claude/setup.sh on session start
Installs JDK 26 + mvnd, configures Maven proxy, shows git context.

**Troubleshoot:**
| Symptom | Fix |
|---------|-----|
| "OpenJDK 26 not found" | `bash .claude/setup.sh` manually |
| "mvnd not in PATH" | `ln -sf /root/.mvnd/mvnd-2.0.0-rc-3/bin/mvnd /usr/local/bin/mvnd` |
| "Maven proxy config failed" | Check `echo $JAVA_TOOL_OPTIONS` or `echo $https_proxy` |

## PostToolUse — runs after every Edit/Write on .java files

Hook chain:
1. Detect: is file .java? → yes
2. Spotless format: `mvnd spotless:apply -q`
3. Guard validation: `.claude/hooks/simple-guards.sh`

**Guard violations (exit 2):**
| Pattern | Name | Fix |
|---------|------|-----|
| `TODO\|FIXME\|XXX` | H_TODO | Implement or remove |
| `@Mock\|Mockito\|mock(` | H_MOCK | Use real implementation |
| `throw new UnsupportedOperationException()` | H_STUB | Implement the method |

**Re-run manually:**
```bash
mvnd spotless:apply -q               # format
bash .claude/hooks/simple-guards.sh  # guard check
make guard-check                     # full guard validation
```
