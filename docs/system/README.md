# JOTP System Documentation

A collection of guides describing how to use JOTP and different aspects of working with Java OTP patterns.

JOTP implements all 15 Erlang/OTP primitives in pure Java 26 — virtual threads, sealed types, structured concurrency, and pattern matching combine to deliver BEAM-equivalent fault tolerance to the world's largest developer ecosystem.

---

## The Nine Guides

| Guide | Description |
|-------|-------------|
| [Installation Guide](installation.md) | Build and install JOTP on any platform; add it as a Maven dependency |
| [Getting Started with JOTP](getting-started.md) | First steps: processes, message passing, supervision |
| [System Principles](system-principles.md) | How to structure, start, and operate a JOTP system |
| [OTP Design Principles](otp-design-principles.md) | The 15 JOTP primitives — `Proc`, `Supervisor`, `StateMachine`, and more |
| [Programming Examples](programming-examples.md) | Practical Java 26 patterns: sealed records, streams, pipelines, error handling |
| [Reference Manual](reference-manual.md) | Module system, language features, and complete API quick reference |
| [Efficiency Guide](efficiency-guide.md) | Virtual thread performance, anti-patterns, benchmarking, and tuning |
| [Interoperability Guide](interoperability.md) | Integrating JOTP with Spring Boot, JDBC, REST APIs, and migrating from Erlang/Akka |
| [Deployment Guide](deployment-guide.md) | Fat JAR, Docker, JVM tuning, health checks, graceful shutdown |

---

## About This Documentation

JOTP documentation follows the [Diataxis framework](https://diataxis.fr/):

- **Tutorials** (`docs/tutorials/`) — learning-oriented, hands-on walkthroughs
- **How-To Guides** (`docs/how-to/`) — task-oriented, solve a specific problem
- **Explanations** (`docs/explanations/`) — understanding-oriented, the *why*
- **Reference** (`docs/reference/`) — lookup-oriented, exact API details

The **System Documentation** guides above sit above this structure — they provide the authoritative end-to-end view of the system, analogous to the Erlang/OTP system guides at erlang.org/doc/system.

For an academic treatment of JOTP's formal equivalence to OTP 28, see the [PhD Thesis](../phd-thesis/otp-28-java26.md).

---

*JOTP is copyright Sean Chatman. Built with Java 26, Maven 4, and mvnd.*
