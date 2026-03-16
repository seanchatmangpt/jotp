# Innovation 4: Distributed OTP Supervisor — Erlang's Distribution Layer for Java

**Technical Specification**
**Date:** 2026-03-08
**Status:** Proposed
**Codebase:** `org.acme` — Java 25 JPMS library, GraalVM Community CE 25.0.2

## Overview

This specification describes how to extend JOTP's actor model across JVM boundaries with zero API changes for application code. The distribution layer is transparent: `ActorRef<S,M>` works the same whether the actor is local or remote.

The design implements:
- Location-transparent actor references
- Gossip-based cluster membership
- Raft-over-actors for distributed state
- Split-brain resolution with oldest-node-wins
- Remote actor restart via ClusterSupervisor

[Continue with full content from INNOVATION-4-DISTRIBUTED-OTP.md...]