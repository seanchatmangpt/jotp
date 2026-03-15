# Innovation 5: OTP-Native Event Sourcing — Actors as Event-Sourced Aggregates

**Technical Specification**
Date: 2026-03-08
Codebase: `/home/user/java-maven-template/src/main/java/org/acme/`

## Overview

Event sourcing is conceptually simple: instead of storing the current state of an entity, store the sequence of events that produced that state. Yet dominant Java frameworks require days of study before writing a working aggregate.

This specification describes `EventSourcedActor<S,C,E>` — approximately 200 lines of Java with no external dependencies beyond the core OTP primitives. There are no annotations, no classpath scanning, no configuration XML, no framework to learn.

[Continue with full content from INNOVATION-5-EVENT-SOURCING.md...]