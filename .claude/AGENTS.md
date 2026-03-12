# Claude Code Agents Reference

Agents are specialized AI sub-processes that autonomously handle complex tasks. They're used during the exploration and design phases of plan mode, or for independent research tasks.

## Available Agent Types

### Explore Agent

**Purpose:** Investigate codebases quickly and efficiently.

**When to use:**
- Searching for specific patterns or implementations
- Understanding how existing code works
- Finding all occurrences of a feature across files
- Analyzing architecture and dependencies
- Answering codebase questions

**Invocation:**
```python
Agent(
  description="short description (3-5 words)",
  prompt="detailed task description",
  subagent_type="Explore",
  # Optional: thoroughness level
)
```

**Thoroughness Levels:**

| Level | Scope | Best For |
|---|---|---|
| `quick` | Single focused search | Finding a specific file or function |
| `medium` | Multiple searches, related areas | Understanding a feature across components |
| `very-thorough` | Comprehensive codebase analysis | Architectural analysis, migration planning |

**Example: Quick Search**
```
Agent(
  description="Find authentication handler",
  prompt="Search for JwtValidator class and all files that use it",
  subagent_type="Explore"
)
# Returns: file paths, code snippets, usage patterns
```

**Example: Very Thorough Analysis**
```
Agent(
  description="Analyze error handling patterns",
  prompt="Find all Result<T,E> usage patterns and how errors are handled",
  subagent_type="Explore"
)
# Returns: comprehensive analysis of error handling across codebase
```

**What Explore returns:**
- File paths and locations
- Code snippets showing patterns
- Summary of findings
- Related files and dependencies

---

### Plan Agent

**Purpose:** Design implementation strategies based on exploration findings.

**When to use:**
- Architecting solutions to complex problems
- Breaking down multi-step tasks
- Considering alternatives and trade-offs
- Planning refactoring efforts
- Designing new features

**Invocation:**
```python
Agent(
  description="short description (3-5 words)",
  prompt="detailed requirements + exploration findings",
  subagent_type="Plan"
)
```

**Input to Plan agent:**
- User requirements (what needs to be done)
- Exploration findings (what code exists, patterns, constraints)
- Architectural constraints
- Testing considerations

**Example:**
```
Agent(
  description="Design JWT authentication module",
  prompt="""
  Requirements:
  - Add JWT token validation
  - Support token refresh
  - Integrate with existing auth system

  Findings (from Explore):
  - AuthHandler.java exists at core/auth/
  - Tests use JUnit 5 with AssertJ
  - Use sealed Result<T,E> for error handling

  Please design:
  1. Class structure
  2. Integration points
  3. Test strategy
  """,
  subagent_type="Plan"
)
```

**What Plan returns:**
- Step-by-step implementation plan
- File structure and changes
- Code organization recommendations
- Integration strategy
- Testing approach

---

## Parallel Agent Launching

Launch multiple agents in a single message for efficiency. Use when:
- Exploration tasks are independent
- You need quick parallel results
- Combining multiple perspectives

**Syntax:**
```python
# In a single message, multiple tool calls:
Agent(description="...", prompt="...", subagent_type="Explore")  # Agent 1
Agent(description="...", prompt="...", subagent_type="Explore")  # Agent 2
Agent(description="...", prompt="...", subagent_type="Plan")     # Agent 3
```

**Example: Parallel Exploration**
```
Agent(
  description="Find authentication code",
  prompt="Locate all auth-related classes and files",
  subagent_type="Explore"
)

Agent(
  description="Find test utilities",
  prompt="Find existing test fixtures, mocks, and helpers",
  subagent_type="Explore"
)

Agent(
  description="Find error handling patterns",
  prompt="How are Result<T,E> and exceptions used?",
  subagent_type="Explore"
)
```

All three agents run in parallel, returning results as they complete.

---

## Agent Usage in Plan Mode

### Phase 1: Exploration

**Goal:** Understand requirements and codebase.

**Use Explore agents to:**
1. Find existing implementations
2. Understand patterns and architecture
3. Identify constraints and opportunities
4. Map affected code areas

**Typically:** 1-3 Explore agents

---

### Phase 2: Design

**Goal:** Create implementation strategy.

**Use Plan agent to:**
1. Break down complexity
2. Design integration points
3. Plan testing strategy
4. Consider alternatives

**Typically:** 1 Plan agent

---

## Advanced Features

### Resume Agent

Continue work from a previous agent invocation:

```python
Agent(
  description="Continue previous analysis",
  prompt="Follow-up questions based on previous findings",
  subagent_type="Explore",
  resume="<agent-id>"  # From previous invocation
)
```

### Worktree Isolation

Run agents in an isolated git worktree:

```python
Agent(
  description="Test refactoring safely",
  prompt="...",
  subagent_type="Plan",
  isolation="worktree"  # Creates temporary isolated worktree
)
```

The worktree is automatically cleaned up if no changes are made.

---

## Best Practices

1. **Specific descriptions** — Keep 3-5 word descriptions focused and clear
2. **Detailed prompts** — Provide context, requirements, and constraints
3. **Leverage findings** — Use Explore results to inform Plan agent inputs
4. **Parallel when possible** — Launch independent agents simultaneously
5. **Trust agents** — They're designed to handle their tasks autonomously
6. **Check results** — Review what agents return and adjust follow-up searches if needed

---

## Common Patterns

### Pattern 1: Explore then Plan

```
1. Explore Agent: "Find authentication implementations"
   ↓ Returns: List of auth files, patterns
2. Plan Agent: "Design new auth module" (using findings)
   ↓ Returns: Step-by-step plan
3. Review: Validate plan aligns with findings
```

### Pattern 2: Parallel Exploration

```
Explore: "Find service layer"
Explore: "Find test patterns"     ← Parallel
Explore: "Find error handling"

↓ All complete, results combined
3. Plan Agent: "Design service architecture" (using all findings)
```

### Pattern 3: Deep Dive Analysis

```
1. Explore (quick): "Find JWT code"
2. Explore (very-thorough): "Analyze all JWT usage"
   ↓ Detailed findings
3. Plan: "Design JWT module improvements"
```

---

## Troubleshooting

**Agent returns limited results:**
- Increase thoroughness level (`quick` → `very-thorough`)
- Make prompt more specific
- Break into multiple focused searches

**Agent doesn't find what you expected:**
- Try different search terms/patterns
- Check if feature exists (use Glob tool manually)
- Ask agent to search broader scope

**Agents taking too long:**
- Check if thoroughness is appropriate
- Reduce scope (more focused search)
- Break into multiple agents with specific focuses

---

## Tips

- **Agents excel at:** Finding patterns, understanding code structure, proposing designs
- **Not suitable for:** Actual code changes (use Edit/Write tools for that)
- **Best combined with:** Plan mode for organized exploration + design
- **Efficiency:** 3 well-designed agents beats 10 generic searches
