# Cross-Reference Quick Start Guide

A quick reference for adding cross-references to JOTP DTR tests.

## The Basics

### What is a Cross-Reference?

A cross-reference is a semantic link from one documentation section to another, helping readers navigate related concepts.

```java
ctx.sayRef(
    TargetTestClass.class,           // Test class containing the section
    "section-anchor-name",           // Anchor of the section
    "Description of relationship"    // Why this reference matters
);
```

### When to Add Cross-References

**Add a reference when**:
- ✅ Explaining something that builds on another concept
- ✅ Showing how a pattern uses a primitive
- ✅ Contrasting two approaches
- ✅ Documenting pattern composition

**Don't add a reference when**:
- ❌ The relationship is tenuous
- ❌ It creates a circular dependency
- ❌ The target doesn't exist yet

## Quick Examples

### Example 1: Prerequisite (must understand first)

```java
ctx.say("Supervisor manages multiple Procs for fault tolerance");

// Reference: Prerequisite
ctx.sayRef(
    ProcTest.class,
    "proc-basic-creation",
    "See Proc: Basic Process Creation for process lifecycle fundamentals"
);
```

### Example 2: Implementation (how it's built)

```java
ctx.say("CircuitBreaker uses Supervisor for fault tolerance");

// Reference: Implementation
ctx.sayRef(
    SupervisorTest.class,
    "supervisor-crash-restart",
    "Built using Supervisor for automatic restart on failure"
);
```

### Example 3: Composition (works with)

```java
ctx.say("Saga combines StateMachine, Supervisor, and EventManager");

// Reference: Composition (multiple)
ctx.sayRef(
    StateMachineTest.class,
    "statemachine-gen-statem-contract",
    "Uses StateMachine for orchestration state"
);

ctx.sayRef(
    SupervisorTest.class,
    "supervisor-one-for-one-strategy",
    "Uses Supervisor for step fault tolerance"
);
```

## Anchor Naming

Use **kebab-case**: `primitive-concept`

**Good examples**:
- `proc-basic-creation` ✓
- `supervisor-one-for-one-strategy` ✓
- `circuit-breaker-closed-state` ✓

**Bad examples**:
- `basicCreation` ✗ (use kebab-case)
- `stuff` ✗ (too generic)
- `proc` ✗ (too vague)

## Common Patterns

### Core OTP → Core OTP

```java
// Supervisor references Proc
ctx.sayRef(ProcTest.class, "proc-basic-creation", "Prerequisite");
```

### Enterprise → Core

```java
// CircuitBreaker references Supervisor
ctx.sayRef(SupervisorTest.class, "supervisor-crash-restart", "Implementation");
```

### EIP → EIP

```java
// Aggregator references Splitter
ctx.sayRef(SplitterTest.class, "splitter-message-decomposition", "Composition");
```

### EIP → Core

```java
// ScatterGather references Splitter and Aggregator
ctx.sayRef(SplitterTest.class, "splitter-message-decomposition", "Scatter phase");
ctx.sayRef(AggregatorTest.class, "aggregator-message-correlation", "Gather phase");
```

## Reference Types

### 1. Prerequisite
"Understand X before Y"

```java
ctx.sayRef(
    ProcTest.class,
    "proc-basic-creation",
    "Prerequisite: Understand basic process creation first"
);
```

### 2. Implementation
"X uses Y for Z"

```java
ctx.sayRef(
    SupervisorTest.class,
    "supervisor-crash-restart",
    "Built using: Supervisor for fault tolerance"
);
```

### 3. Composition
"X works with Y"

```java
ctx.sayRef(
    StateMachineTest.class,
    "statemachine-transitions",
    "Works with: StateMachine for orchestration"
);
```

### 4. Comparison
"Compare X with Y"

```java
ctx.sayRef(
    DynamicRouterTest.class,
    "router-content-evaluation",
    "Compare with: Dynamic Router for runtime-changing routes"
);
```

## Step-by-Step Guide

### Step 1: Identify the Target

Find the test class and section you want to reference:

```java
// Target: SupervisorTest.java
@Test
void crashAndRestartResumesService(DtrContext ctx) {
    ctx.sayNextSection("Supervisor: Crash and Restart Recovery");
    // This is the section we want to reference
}
```

### Step 2: Create the Anchor

Add anchor to `@DocSection` or use section title:

```java
// Anchor: "supervisor-crash-restart"
ctx.sayNextSection("Supervisor: Crash and Restart Recovery");
```

### Step 3: Add the Reference

In your test, add `ctx.sayRef()`:

```java
ctx.say("CircuitBreaker uses Supervisor for fault tolerance");

ctx.sayRef(
    SupervisorTest.class,
    "supervisor-crash-restart",
    "See Supervisor: Crash and Restart Recovery"
);
```

### Step 4: Add Import (if needed)

```java
import io.github.seanchatmangpt.jotp.test.SupervisorTest;
```

### Step 5: Test It

Run the test and verify the reference appears in documentation:

```bash
mvnd test -Dtest=CircuitBreakerTest
```

## Common Mistakes

### Mistake 1: Reference Before Explanation

```java
// BAD
ctx.sayRef(ProcTest.class, "proc-basic-creation");
ctx.say("Supervisor manages multiple Procs");

// GOOD
ctx.say("Supervisor manages multiple Procs");
ctx.sayRef(ProcTest.class, "proc-basic-creation", "See Proc for basics");
```

### Mistake 2: Circular References

```java
// BAD: A → B → A
// In ProcTest.java
ctx.sayRef(SupervisorTest.class, "supervisor-basics", "See Supervisor");

// In SupervisorTest.java
ctx.sayRef(ProcTest.class, "proc-basics", "See Proc");

// GOOD:单向依赖
// In SupervisorTest.java
ctx.sayRef(ProcTest.class, "proc-basics", "Prerequisite: Proc basics");
```

### Mistake 3: Generic Anchors

```java
// BAD
ctx.sayRef(ProcTest.class, "basic", "See basic stuff");

// GOOD
ctx.sayRef(ProcTest.class, "proc-basic-creation", "See Proc: Basic Process Creation");
```

## Quick Reference Card

### Syntax
```java
ctx.sayRef(Class, "anchor", "description");
```

### Placement
```java
ctx.say("Explanation first");
ctx.sayRef(...);  // Then reference
```

### Naming
```java
"primitive-specific-concept"  // kebab-case
```

### Types
- Prerequisite: "Prerequisite: ..."
- Implementation: "Built using: ..."
- Composition: "Works with: ..."
- Comparison: "Compare with: ..."

## Common Cross-References

### Supervisor → Proc
```java
ctx.sayRef(ProcTest.class, "proc-basic-creation", "Prerequisite");
```

### CircuitBreaker → Supervisor
```java
ctx.sayRef(SupervisorTest.class, "supervisor-crash-restart", "Implementation");
```

### Saga → StateMachine + Supervisor
```java
ctx.sayRef(StateMachineTest.class, "statemachine-gen-statem-contract", "Uses");
ctx.sayRef(SupervisorTest.class, "supervisor-one-for-one-strategy", "Uses");
```

### Aggregator → Splitter
```java
ctx.sayRef(SplitterTest.class, "splitter-message-decomposition", "Reassembles");
```

## Validation

### Check for Broken Links
```bash
# TODO: Implement link checker
# For now, manually verify:
# 1. Target class exists
# 2. Anchor exists in target class
# 3. Import is present
```

### Check for Circular Dependencies
```bash
# TODO: Implement cycle detector
# For now, manually check:
# 1. A → B → A cycles
# 2. Longer cycles A → B → C → A
```

### Check for Orphaned Sections
```bash
# TODO: Implement orphan detector
# For now, check if sections have:
# 1. Inbound references from other tests
# 2. Or are foundational (no refs needed)
```

## Resources

### Full Documentation
- **Design**: `/docs/user-guide/cross-references.md`
- **Index**: `/docs/user-guide/cross-reference-index.md`
- **Examples**: `/docs/user-guide/cross-reference-examples.md`
- **Summary**: `/docs/user-guide/CROSS_REFERENCE_IMPLEMENTATION_SUMMARY.md`

### Existing Cross-References
- SupervisorTest.java → ProcTest.java
- CircuitBreakerTest.java → SupervisorTest.java
- DistributedSagaCoordinatorTest.java → StateMachineTest.java + SupervisorTest.java + EventManagerTest.java

### Anchor Registry
See `/docs/user-guide/cross-reference-index.md` for complete list of 100+ anchors.

## Need Help?

1. **Check examples**: Look at existing cross-references in test files
2. **Read design docs**: Understand the system architecture
3. **Ask**: Check with documentation team for complex cases

---

**Quick Start Version**: 1.0
**Last Updated**: 2025-03-15
**Maintainer**: Documentation Team
