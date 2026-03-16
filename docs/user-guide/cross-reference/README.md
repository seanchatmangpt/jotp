# JOTP DTR Cross-Reference System

**Status**: ✅ Design Complete | 🔄 Implementation In Progress (6%)

A comprehensive cross-reference system for JOTP's Documentation Test Runner (DTR) that enables semantic linking between related documentation sections.

## 🎯 Overview

The cross-reference system allows readers to navigate from basic OTP primitives to advanced enterprise patterns, understanding how concepts relate and compose. Similar to JavaDoc's `@link` but for executable documentation tests.

## 📚 Documentation

| Document | Purpose | Size |
|----------|---------|------|
| **[Quick Start Guide](./cross-reference-quickstart.md)** | Get started in 5 minutes | 361 lines |
| **[Design Specification](./cross-references.md)** | Complete system design | 523 lines |
| **[Cross-Reference Index](./cross-reference-index.md)** | All tests, sections, relationships | 653 lines |
| **[Implementation Examples](./cross-reference-examples.md)** | 9 detailed code examples | 464 lines |
| **[Implementation Summary](./CROSS_REFERENCE_IMPLEMENTATION_SUMMARY.md)** | Progress and next steps | 413 lines |

**Total Documentation**: 2,414 lines

## 🚀 Quick Start

### Add a Cross-Reference in 3 Steps

```java
// Step 1: Explain the concept
ctx.say("Supervisor manages multiple Procs for fault tolerance");

// Step 2: Add cross-reference
ctx.sayRef(
    ProcTest.class,                    // Target test class
    "proc-basic-creation",              // Section anchor
    "See Proc: Basic Process Creation for fundamentals"
);

// Step 3: Continue with code examples
ctx.sayCode("...");
```

### Complete Example

```java
@Test
void circuitBreakerUsesSupervisor(DtrContext ctx) {
    ctx.sayNextSection("CircuitBreaker: CLOSED State");

    ctx.say("CircuitBreaker allows all requests in CLOSED state");

    // CROSS-REFERENCE: Link to Supervisor (implementation)
    ctx.sayRef(
        SupervisorTest.class,
        "supervisor-crash-restart",
        "CircuitBreaker uses Supervisor for fault tolerance"
    );

    ctx.sayCode("""
        var breaker = CircuitBreaker.create("service", 3,
            Duration.ofSeconds(10), Duration.ofMillis(500));
        """);

    // Test implementation...
}
```

## 📊 Current Status

### Implementation Progress

**Phase 1: Core OTP Primitives** (IN PROGRESS)
- ✅ Design complete
- ✅ Documentation complete
- ✅ Example implementations (3 tests)
- 🔄 Add remaining references (12 more to go)

**Phase 2: Enterprise Patterns** (NOT STARTED)
- ⏳ Add anchors to all enterprise tests
- ⏳ Link Enterprise → Core (25 refs)
- ⏳ Link Enterprise → Enterprise (10 refs)

**Phase 3: EIP Patterns** (NOT STARTED)
- ⏳ Add anchors to EIP tests
- ⏳ Document composition (20 refs)

**Phase 4: Validation** (NOT STARTED)
- ⏳ Implement link checker
- ⏳ Generate reference graph
- ⏳ Add quality metrics

### Completed Implementations

1. **SupervisorTest.java** → ProcTest.java
   - Reference: `proc-basic-creation`
   - Type: Prerequisite
   - Location: `crashAndRestartResumesService()`

2. **CircuitBreakerTest.java** → SupervisorTest.java
   - Reference: `supervisor-crash-restart`
   - Type: Implementation
   - Location: `testClosedStateSuccessfulRequest()`

3. **DistributedSagaCoordinatorTest.java** → Multiple
   - References: StateMachine, Supervisor, EventManager
   - Type: Composition
   - Location: `createWithValidConfig_returnsInstance()`

## 🎯 Key Features

### Semantic Link Types

1. **Prerequisite**: "Understand X before Y"
2. **Implementation**: "X uses Y for Z"
3. **Composition**: "X works with Y"
4. **Comparison**: "Compare X with Y"

### Anchor Naming Convention

Use **kebab-case**: `primitive-concept`

Examples:
- `proc-basic-creation` ✓
- `supervisor-one-for-one-strategy` ✓
- `circuit-breaker-closed-state` ✓

### Coverage Targets

- **Total References**: 85+
- **Core OTP**: 15 references
- **Enterprise**: 35 references
- **EIP**: 20 references
- **Other**: 15 references

## 📖 Reference Maps

### Core OTP Primitives

```
Proc (Basic Process)
├─ Supervisor (Fault Tolerance) → Proc
├─ ProcRef (Stable References) → Proc
├─ ProcLink (Bidirectional Linking) → Proc
├─ ProcMonitor (One-way Monitoring) → Proc
├─ StateMachine (Workflows) → Proc
└─ EventManager (Pub/Sub) → Proc
```

### Enterprise Patterns

```
CircuitBreaker (Fault Tolerance)
└─ Supervisor → Proc

Saga (Distributed Transactions)
├─ StateMachine (Orchestration)
├─ Supervisor (Fault Tolerance)
└─ EventManager (Compensation Events)

BulkheadIsolation (Resource Partitioning)
├─ Supervisor → Proc
└─ ProcRef (Stable References)
```

### EIP Patterns

```
ScatterGather (Parallel Processing)
├─ Splitter (Decomposition)
└─ Aggregator (Reassembly)
    └─ Splitter (Source)
```

## 🔧 How to Use

### For Readers

1. Start with [Quick Start Guide](./cross-reference-quickstart.md)
2. Follow cross-references to learn concepts
3. Use [Cross-Reference Index](./cross-reference-index.md) to find related tests
4. Explore [Implementation Examples](./cross-reference-examples.md) for patterns

### For Contributors

1. Read [Design Specification](./cross-references.md) for architecture
2. Follow [Quick Start Guide](./cross-reference-quickstart.md) to add refs
3. Check [Cross-Reference Index](./cross-reference-index.md) for existing anchors
4. Use [Implementation Examples](./cross-reference-examples.md) as templates
5. Update [Implementation Summary](./CROSS_REFERENCE_IMPLEMENTATION_SUMMARY.md) with progress

### For Maintainers

1. Monitor [Implementation Summary](./CROSS_REFERENCE_IMPLEMENTATION_SUMMARY.md) for progress
2. Validate references with link checker (Phase 4)
3. Generate reference graph visualization (Phase 4)
4. Review reference quality metrics (Phase 4)

## 📋 Best Practices

### DO ✅

1. **Place references after explanations** - Context first, then links
2. **Use descriptive anchor names** - Kebab-case, specific and clear
3. **Group related references** - Multiple refs about same topic
4. **Provide relationship context** - Explain WHY the reference matters
5. **Update documentation** - When adding refs, document in index

### DON'T ❌

1. **Don't reference before explaining** - Readers need context first
2. **Don't create circular refs** - A→B→A creates confusion
3. **Don't reference non-existent sections** - Creates broken links
4. **Don't over-reference** - Too many links dilute value
5. **Don't use generic anchors** - "basic-stuff" is not helpful

## 🎓 Learning Paths

### Beginner Path
1. Proc: Basic Process Creation
2. Proc: Message Passing
3. Supervisor: Crash and Restart
4. [Build Simple Application](../tutorials/beginner/06-building-simple-application.mdx)

### Intermediate Path
1. StateMachine: OTP Contract
2. EventManager: Typed Events
3. CircuitBreaker: Fault Tolerance
4. [Build Supervision Trees](../tutorials/advanced/building-supervision-trees.mdx)

### Advanced Path
1. Saga: Compensating Transactions
2. Bulkhead: Resource Isolation
3. ScatterGather: EIP Composition
4. [Distributed Systems](../tutorials/advanced/distributed-jotp-systems.mdx)

## 🔗 Related Documentation

- **[JOTP Architecture](../architecture/distributed/distributed-otp.mdx)** - System design
- **[User Guide](../README.md)** - Main documentation
- **[API Reference](../reference/api/proc.mdx)** - API documentation
- **[Migration Guide](../migration/from-akka.mdx)** - Migrating from Akka

## 📈 Metrics

### Documentation Coverage
- **Total Lines**: 2,414
- **Test Classes Mapped**: 40+
- **Sections Documented**: 200+
- **Anchors Defined**: 100+
- **References Implemented**: 5

### Implementation Progress
- **Phase 1 (Core)**: 20% complete (3/15 refs)
- **Phase 2 (Enterprise)**: 0% complete (0/35 refs)
- **Phase 3 (EIP)**: 0% complete (0/20 refs)
- **Phase 4 (Validation)**: 0% complete

### Quality Metrics
- **Broken Links**: 0 (validated manually)
- **Circular Dependencies**: 0 (DAG maintained)
- **Orphaned Sections**: TBD (needs validator)
- **Generic References**: 0 (all specific)

## 🛠️ Tools (TODO)

### Phase 4: Validation Pipeline

1. **Link Checker** - Verifies all referenced anchors exist
2. **Cycle Detector** - Prevents circular dependencies
3. **Orphan Detector** - Finds sections with no inbound refs
4. **Quality Analyzer** - Flags generic or tenuous links
5. **Graph Generator** - Visualizes reference relationships

## 🤝 Contributing

### Adding Cross-References

1. Check [Cross-Reference Index](./cross-reference-index.md) for existing anchors
2. Follow [Quick Start Guide](./cross-reference-quickstart.md)
3. Use [Implementation Examples](./cross-reference-examples.md) as templates
4. Update [Implementation Summary](./CROSS_REFERENCE_IMPLEMENTATION_SUMMARY.md)

### Creating New Documentation Sections

1. Create unique, descriptive anchor (kebab-case)
2. Document in [Cross-Reference Index](./cross-reference-index.md)
3. Add references FROM related sections
4. Verify no circular dependencies

## 📝 Changelog

### 2025-03-15
- ✅ Design complete (5 documents, 2,414 lines)
- ✅ Example implementations (3 test classes, 5 references)
- ✅ Quick start guide for contributors
- 🔄 Phase 1 implementation in progress

### Next Steps
1. Complete Phase 1: Core OTP references (12 more)
2. Start Phase 2: Enterprise pattern references
3. Implement validation tools (Phase 4)

## 📧 Support

For questions or issues:
1. Check [Quick Start Guide](./cross-reference-quickstart.md)
2. Review [Implementation Examples](./cross-reference-examples.md)
3. Consult [Design Specification](./cross-references.md)
4. Contact documentation team

---

**Version**: 1.0.0
**Status**: Design Complete, Implementation In Progress (6%)
**Last Updated**: 2025-03-15
**Maintainer**: Documentation Team
