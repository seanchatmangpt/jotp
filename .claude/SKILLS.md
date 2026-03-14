# Skills Quick Reference

Skills are invoked with `/skill-name` in Claude Code.

| Skill | Trigger | What it does |
|-------|---------|--------------|
| `/simplify` | Manual | Reviews changed code for reuse, quality, and efficiency; fixes issues found |
| `/claude-api` | Auto: `anthropic`, `@anthropic-ai/sdk`, `claude_agent_sdk` imports | Guides Claude API / Anthropic SDK usage and best practices |
| `/loop <interval> <cmd>` | Manual | Runs a command on a recurring interval (default 10m). E.g. `/loop 5m make test` |
| `/session-start-hook` | Manual | Configures SessionStart hook for a new repo |

## Intervals for /loop
- `30s`, `5m`, `1h` — seconds, minutes, hours
- Stop with Ctrl+C
