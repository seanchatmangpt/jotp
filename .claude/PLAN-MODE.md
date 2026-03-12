# Plan Mode Workflow

Plan mode organizes complex, multi-step tasks into a structured five-phase development process. It ensures thorough planning before implementation and clear alignment with user intent.

## When to Use Plan Mode

Use plan mode for:
- **Complex features** requiring 3+ implementation steps
- **Architectural decisions** affecting multiple components
- **Significant refactoring** across the codebase
- **Uncertain requirements** needing exploration before design
- **Multi-file changes** requiring coordination

Skip plan mode for:
- Simple bug fixes (single file, single change)
- Trivial features (typo fixes, single-line updates)
- Routine maintenance (dependency updates, doc tweaks)

## Five-Phase Workflow

### Phase 1: Explore

**Goal:** Understand the user's request and the codebase.

**Actions:**
- Read relevant files and understand existing code
- Search for similar patterns or existing implementations
- Identify reusable components
- Ask clarifying questions if requirements are ambiguous

**Tools:**
- `Read` — Read files to understand structure
- `Glob` — Find files by pattern
- `Grep` — Search code content
- `Agent (Explore)` — Launch specialized exploration agents

**Outcome:**
- Clear understanding of requirements
- Map of affected code areas
- List of existing utilities/patterns to reuse

---

### Phase 2: Design

**Goal:** Architect a solution based on exploration findings.

**Actions:**
- Design the implementation approach
- Consider trade-offs and alternatives
- Break complex tasks into manageable steps
- Estimate effort and identify risks

**Tools:**
- `Agent (Plan)` — Launch plan agent to architect solution
- Create detailed step-by-step implementation plan

**Outcome:**
- Step-by-step implementation plan
- List of files to modify
- Identified dependencies and critical paths

---

### Phase 3: Review

**Goal:** Validate assumptions and ensure alignment with user intent.

**Actions:**
- Review the design plan with the user
- Ask clarifying questions if needed
- Adjust plan based on feedback
- Confirm approach before implementation

**Tools:**
- `AskUserQuestion` — Ask for feedback or clarification
- `Read` — Re-read critical files if needed

**Outcome:**
- User approval of implementation approach
- Resolved questions or concerns
- Final, agreed-upon plan

---

### Phase 4: Write Final Plan

**Goal:** Document the final implementation plan in a plan file.

**Action:**
- Write comprehensive plan to `.claude/plans/*.md`
- Include Context section explaining the "why"
- List files to be modified/created
- Reference reusable utilities found during exploration
- Describe verification/testing strategy

**Plan file structure:**

```markdown
# Plan: [Brief Title]

## Context
[Explain the problem/need, why this change matters, intended outcome]

## Implementation Plan

### Phase 1: [Task Area]
- Step 1
- Step 2
- Step 3

### Phase 2: [Task Area]
- Step 1
- Step 2

## Files to Modify
- `path/to/file.java` — description of changes
- `path/to/another.java` — description of changes

## Verification
- How to test the changes
- What to check end-to-end
- Expected outcomes

## Notes
- Any special considerations
- Known limitations
```

**Outcome:**
- Comprehensive, reviewable plan file
- Ready for user approval

---

### Phase 5: Exit Plan Mode

**Goal:** Request user approval before implementation.

**Action:**
- Call `ExitPlanMode` to indicate plan is ready
- User reviews and approves plan
- Claude Code exits plan mode and begins implementation

**Outcome:**
- User-approved implementation plan
- Permission to execute

---

## Plan Files

**Location:** `.claude/plans/`

**Naming:** Plan files are auto-named by the system (e.g., `synthetic-tumbling-jellyfish.md`).

**Format:** Markdown (`.md`)

**Content:**
- Context explaining the problem and approach
- Implementation steps, broken down by phase
- Files to be modified
- Verification section

**Retention:** Plan files are saved for reference and form a history of decisions made.

---

## Example: Adding a New Feature

### User Request
> "Add a new authentication module to handle JWT tokens"

### Phase 1: Explore
```
Claude explores:
- Existing authentication code
- JWT library availability
- Security patterns in the codebase
- Test infrastructure
```

### Phase 2: Design
```
Plan agent creates:
- Architecture: JWT validation strategy
- Files to create: JwtValidator.java, JwtTest.java
- Integration points: where to plug in validator
- Testing approach: unit tests + integration tests
```

### Phase 3: Review
```
Claude asks:
- "Should we support token refresh?"
- "Where should validation happen? In a filter or explicit calls?"
```

### Phase 4: Write Plan
```
Plan file created at .claude/plans/jwt-auth-feature.md
```

### Phase 5: Exit Plan Mode
```
ExitPlanMode called
User approves plan
Claude implements and commits changes
```

---

## Commands & Shortcuts

**Launching plan mode:**
The user initiates plan mode—Claude Code detects the request and enters planning phase automatically.

**In plan mode:**
- Only read-only tools are available (Read, Glob, Grep, Agent)
- No edits or commits are made
- Only the plan file (`.claude/plans/*.md`) can be edited

**Exiting plan mode:**
- Call `ExitPlanMode` when plan is finalized
- Request user approval
- Proceed to implementation

---

## Best Practices

1. **Ask early, ask often** — Use `AskUserQuestion` to clarify ambiguities
2. **Reference concrete files** — Include file paths and line numbers in exploration
3. **Reuse existing code** — Identify and leverage utilities already in the codebase
4. **Break down complexity** — Large tasks should be split into phases
5. **Explain the "why"** — Context section should justify design decisions
6. **Provide verification steps** — Include concrete steps to test the implementation

---

## Tips

- **Don't skip Phase 3** — Always ask for feedback before finalizing the plan
- **Keep plans concise** — Plan should be scannable; details can be filled in during implementation
- **Link to code** — Reference existing code and patterns to show you've done homework
- **Include trade-offs** — Explain why you chose this approach over alternatives
