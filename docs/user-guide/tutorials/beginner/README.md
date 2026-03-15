# JOTP Beginner Tutorial Series

Welcome to the JOTP beginner tutorial series! This comprehensive guide will take you from zero to building concurrent, fault-tolerant applications with JOTP (Java OTP).

## Series Overview

JOTP brings Erlang/OTP's battle-tested concurrency patterns to Java 26. These tutorials teach you how to build scalable, fault-tolerant systems using virtual threads, message passing, and supervision trees.

## Tutorials

### 1. [Getting Started with JOTP](getting-started.md)
**Learn the fundamentals:**
- Install Java 26 with preview features
- Build JOTP from source
- Run your first JOTP process
- Understand virtual threads and sealed interfaces
- Create a "Hello World" example

**Prerequisites:** None
**Duration:** 30 minutes

---

### 2. [Creating Your First Process](first-process.md)
**Deep dive into Proc<S, M>:**
- Create processes with custom state and message types
- Define sealed interface message hierarchies
- Implement message handlers with pattern matching
- Use `tell()` for async, `ask()` for sync communication
- Build a working counter process

**Prerequisites:** Getting Started
**Duration:** 45 minutes

---

### 3. [Message Passing in JOTP](message-passing.md)
**Master asynchronous communication:**
- Understand virtual threads (the foundation of JOTP)
- Learn mailbox-based message delivery
- Design message protocols with sealed interfaces
- Implement async communication patterns
- Build a multi-process chat room

**Prerequisites:** First Process
**Duration:** 60 minutes

---

### 4. [State Management in JOTP](state-management.md)
**Learn immutable state patterns:**
- Design immutable state using records
- Implement state transition patterns
- Manage complex state with nested records
- Use defensive copies and smart constructors
- Build a full-featured todo list application

**Prerequisites:** Message Passing
**Duration:** 60 minutes

---

### 5. [Error Handling in JOTP](error-handling.md)
**Build fault-tolerant systems:**
- Railway-oriented programming with `Result<T,E>`
- The "Let It Crash" philosophy
- Process crashes vs exceptions
- Implement robust error handling patterns
- Build a fault-tolerant payment processing system

**Prerequisites:** State Management
**Duration:** 75 minutes

---

## Learning Path

```
1. Getting Started
   ↓
2. First Process
   ↓
3. Message Passing
   ↓
4. State Management
   ↓
5. Error Handling
   ↓
6. [Supervision Trees] (Intermediate - coming soon)
```

## Key Concepts Covered

- **Virtual Threads**: Lightweight concurrency foundation
- **Proc<S, M>**: Core process abstraction with state and messages
- **Sealed Interfaces**: Type-safe message protocols
- **Pattern Matching**: Elegant message handlers
- **Immutable State**: Records and defensive copying
- **Message Passing**: Async communication via mailboxes
- **Result<T,E>**: Error handling without exceptions
- **Let It Crash**: Fault tolerance through supervision

## Prerequisites

Before starting this series, ensure you have:

- **Java 26** with preview features enabled
- **Maven 4** (or use included Maven wrapper)
- Basic **Java programming** knowledge
- Familiarity with **object-oriented programming**
- Understanding of **lambda expressions**

## Getting Help

- **Documentation**: [JOTP Book](../../book/src/SUMMARY.md)
- **Examples**: [Source Code Examples](../../../src/main/java/io/github/seanchatmangpt/jotp/examples/)
- **GitHub**: [Report Issues](https://github.com/seanchatmangpt/jotp/issues)

## What You'll Build

Throughout this series, you'll build:

1. **Hello World** - Your first JOTP process
2. **Counter** - State management with multiple message types
3. **Chat Room** - Multi-process async communication
4. **Todo List** - Complex state with filtering and updates
5. **Payment System** - Fault-tolerant error handling

## Next Steps After This Series

Once you complete the beginner series, continue with:

- **[Intermediate Series]** - Supervision trees, state machines, event management
- **[Advanced Series]** - Distributed systems, pooling, enterprise patterns
- **[Real-World Examples]** - Complete applications demonstrating best practices

## Contributing

Found a bug or want to improve the tutorials? Contributions welcome!

- Fork the repository
- Make your changes
- Submit a pull request

## License

These tutorials are part of the JOTP project and are available under the same license.

---

**Ready to start?** Begin with [Getting Started with JOTP](getting-started.md)!

**Questions?** Check the [FAQ](../../FAQ.md) or [open an issue](https://github.com/seanchatmangpt/jotp/issues).
