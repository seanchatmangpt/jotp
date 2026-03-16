# Innovation 3: Actor-Per-Request HTTP Server — Structural Isolation at the Request Level

**Status:** Technical Specification
**Date:** 2026-03-08
**Codebase context:** `org.acme` — Java 25 JPMS library, GraalVM Community CE 25.0.2, `--enable-preview`

## Overview

Every mainstream Java HTTP server shares at least one resource across requests: thread pools, schedulers, or execution contexts. This shared ownership is the root cause of a class of failure modes — leaked `ThreadLocal` state, cascading handler panics, and "noisy neighbor" latency spikes — that no amount of application-level discipline can fully eliminate.

The Actor-Per-Request architecture makes isolation structural: each HTTP request is its own `Actor<RequestState, HttpMsg>`, supervised by a dedicated `RequestSupervisor`, running on exactly one virtual thread.

[Continue with full content from INNOVATION-3-ACTOR-HTTP.md...]