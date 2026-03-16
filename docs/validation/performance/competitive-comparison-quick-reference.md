# Competitive Comparison Claims - Quick Reference

**Purpose:** Quick reference for validated vs invalid competitive comparison claims

---

## ✅ VALIDATED CLAIMS (Safe to Use)

| Claim | JOTP | Competitor | Source | Notes |
|-------|------|------------|--------|-------|
| **Spawn Throughput** | 1.25M/sec | 512K/sec (Erlang) | phd-thesis-chapter6-empirical-results.md | Same hardware, JMH, statistical significance |
| **Observability Overhead** | 456ns | 1-5µs (Akka) | ANALYSIS-09-industry-comparison.md | Industry benchmarks cited, feature-gated |
| **Memory per Process** | 1.2KB | 312 bytes (Erlang) | ARCHITECTURE.md | Trade-off for type safety documented |
| **Type Safety** | Compile-time | Runtime (Erlang) | ARCHITECTURE.md | Sealed types vs pattern matching |
| **Talent Pool** | 12M developers | 500K (Erlang), 2M (Akka) | README.md | LinkedIn 2026 data |

---

## ❌ INVALID CLAIMS (Do Not Use)

| Claim | Problem | Correct Value | Action |
|-------|---------|---------------|--------|
| **120M msg/sec throughput** | Raw queue, not JOTP | 4.6M msg/sec | Remove or correct |
| **17.3× lower message latency** | tell() vs call/2 (different ops) | 2-5× (tell vs send) | Remove or correct |
| **80× faster crash recovery** | Unknown legacy system | Move to case studies | Not a framework comparison |
| **10M concurrent processes** | Theoretical, not tested | 1M+ tested | Use empirical value |

---

## ⚠️ CONDITIONAL CLAIMS (Use With Qualification)

| Claim | Qualification Required | Why |
|-------|----------------------|-----|
| **23.8% faster supervisor restart** | "for minimal state processes" | Different state complexity not tested |
| **2.85M spawns/sec (Go)** | "Go lacks OTP primitives" | Goroutines ≠ supervised processes |
| **1KB/process memory** | "estimated, not JFR-profiled" | Actual memory varies by state object |

---

## RECOMMENDED NARRATIVE FOR ORACLE

**What to Say:**

> "JOTP delivers Erlang-equivalent fault tolerance with Java 26 advantages:
>
> 1. **2.43× faster process spawning** than Erlang (1.25M vs 512K spawns/sec) - validated on same hardware with statistical significance testing
>
> 2. **2-11× lower observability overhead** than Akka (456ns vs 1-5µs) - unique zero-cost feature-gated design
>
> 3. **Compile-time type safety** - sealed types prevent entire classes of bugs that Erlang catches at runtime
>
> 4. **Native Java ecosystem** - 12M developers vs 500K Erlang developers, seamless Spring Boot integration
>
> 5. **Production-grade performance** - 4.6M msg/sec sustained throughput, sub-microsecond messaging latency, 1M+ concurrent processes tested"

**What NOT to Say:**

> ❌ "120M msg/sec throughput" (raw queue, not JOTP)
> ❌ "17.3× lower latency than Erlang" (apples-to-oranges)
> ❌ "80× faster crash recovery" (unknown legacy system)
> ❌ "10M concurrent processes" (not tested)

---

## CORRECTION CHECKLIST

**Before Oracle Review:**

- [ ] Remove 120M msg/sec claim from ARCHITECTURE.md
- [ ] Correct 17.3× latency claim in academic/README.md
- [ ] Move McLaren 80× claim to case studies
- [ ] Change 10M processes to "1M+ tested"
- [ ] Qualify supervisor restart claim
- [ ] Add context to Go comparison
- [ ] Verify all claims have source citations

---

**Status:** ⚠️ 4 critical corrections needed
**Confidence:** High (documented evidence)
**Next Step:** Apply corrections from competitive-comparison-validation.md
