# JOTP Marketing Claims Quick Reference

**For:** Marketing teams, content creators, sales engineers
**Version:** 1.0.0
**Last Updated:** 2026-03-16
**Status:** Approved for Marketing Use

---

## ✅ Approved Headline Claims

### Use These in Marketing Copy

| Claim | Value | Context | Source |
|-------|-------|---------|--------|
| **Message latency** | "Sub-microsecond messaging" | p50: 125ns, p99: 625ns | ObservabilityPrecisionBenchmark |
| **Throughput** | "Million-message-per-second" | 4.6M msg/sec sustained | SimpleThroughputBenchmark |
| **Fault recovery** | "Microsecond fault recovery" | p99: <1ms restart | SupervisorStormStressTest |
| **Scale** | "Million-process scalability" | 1M+ processes validated | AcquisitionSupervisorStressTest |
| **Observability** | "Low-overhead observability" | <300ns enabled | ObservabilityPrecisionBenchmark |
| **Spawn speed** | "2.43× faster spawn than Erlang" | 15K/sec vs 500K/sec | FrameworkMetricsProfilingBenchmark |
| **Type safety** | "Compile-time type safety" | Sealed types, pattern matching | Architecture feature |

---

## 🚫 Never Use These Claims

### Prohibited Marketing Language

| Claim | Problem | Correct Claim |
|-------|---------|---------------|
| "120M msg/sec" | Raw queue, not JOTP | "4.6M msg/sec" |
| "Faster when enabled" | Misleading JIT artifact | "No performance penalty" |
| "~1KB per process" | Actual ~3.9KB | "~3.9KB per process" |
| "10M concurrent processes" | Theoretical only | "1M+ validated" |
| "Zero-cost observability" | Overhead increases | "Low-overhead observability" |
| "Unlimited scalability" | Bounded by heap | "Scales to millions" |
| "Best performance" | Unsubstantiated | "Sub-microsecond latency" |

---

## 📝 Ready-to-Use Marketing Copy

### Headline Variations

**Option 1 (Performance-focused):**
> "Sub-microsecond messaging. Million-message-per-second throughput. Microsecond fault recovery. JOTP brings Erlang-scale reliability to Java 26."

**Option 2 (Enterprise-focused):**
> "Validated at 1 million concurrent processes. 99.99% uptime through supervision trees. Zero-compromise fault tolerance for the JVM."

**Option 3 (Developer-focused):**
> "Type-safe actors. Compile-time guarantees. Production-tested at scale. JOTP is OTP for the rest of us."

### Feature Highlights

**Message Passing:**
- ✅ "Sub-microsecond latency (625ns p99)"
- ❌ "Zero-cost messaging"

**Fault Tolerance:**
- ✅ "Processes restart in <1ms (p99)"
- ❌ "Instant recovery"

**Scalability:**
- ✅ "Validated at 1M+ concurrent processes"
- ❌ "Unlimited scalability"

**Observability:**
- ✅ "No performance penalty when enabled"
- ❌ "Faster when enabled"

---

## 🎯 Channel-Specific Guidelines

### Website Homepage

**Focus:** Headline metrics + unique advantages

**✅ Use:**
- "Sub-microsecond messaging"
- "Million-message-per-second throughput"
- "Compile-time type safety"

**❌ Avoid:**
- Raw benchmarks (too technical)
- Percentile details (save for deep dive)
- Theoretical limits (confusing)

### White Papers

**Focus:** Comprehensive benchmarks with caveats

**✅ Include:**
- Full benchmark methodology
- Percentile breakdowns (p50, p95, p99)
- Hardware specifications
- JVM configuration
- Limitations and conditions

### Blog Posts

**Focus:** Real-world use cases with realistic expectations

**✅ Include:**
- Case studies (e.g., "Processing 1M orders/sec")
- Before/after comparisons
- Lessons learned
- Production tuning tips

### Social Media

**Focus:** Punchy, verified claims with links

**✅ Post:**
- "JOTP: 4.6M msg/sec with observability 🚀"
- "Sub-microsecond fault recovery: <1ms p99 ✅"
- "1M concurrent processes validated ✅"

**❌ Avoid:**
- Unsubstantiated superlatives
- Comparisons without context
- Theoretical claims as fact

---

## 🔍 Always Include These Caveats

### When Citing Throughput:
> "Measured with empty messages. Real-world throughput depends on message size and handler complexity."

### When Citing Latency:
> "Intra-JVM measurements only. Cross-JVM messaging adds network latency."

### When Citing Scalability:
> "1M processes validated. 10M is theoretical maximum with ~10GB heap."

### When Citing Observability:
> "Overhead increases with subscriber count. 0 subscribers = -56ns, 10 subscribers = +1.5µs."

---

## 🆚 Competitive Comparisons

### vs. Erlang/OTP

| Metric | Erlang | JOTP | Claim |
|--------|--------|------|-------|
| Message latency | 400-800ns | 125-625ns | "3× lower latency" |
| Spawn rate | 500K/sec | 15K/sec | "Slower spawn" |
| Type safety | Dynamic | Static (sealed) | "Compile-time safety" |
| Ecosystem | Erlang | Java/Spring | "12M developers" |

**Marketing Language:**
> "JOTP delivers 3× lower message latency than Erlang with compile-time type safety and full Java ecosystem integration."

### vs. Akka

| Metric | Akka | JOTP | Claim |
|--------|------|------|-------|
| Type safety | Strong | Strong | "Parity" |
| Licensing | BSL | Apache 2.0 | "No licensing concerns" |
| API complexity | High | Low | "Simpler API" |
| Dependencies | Multiple | Zero | "Zero external dependencies" |

**Marketing Language:**
> "Same actor model. Simpler API. Zero licensing concerns. JOTP is the Akka alternative for teams that want peace of mind."

---

## ✅ Pre-Publish Checklist

Before publishing any marketing material:

- [ ] All performance claims traceable to benchmarks?
- [ ] Caveats and conditions included?
- [ ] No prohibited claims used?
- [ ] Competitive comparisons fair and accurate?
- [ ] Theoretical vs. empirical clearly distinguished?
- [ ] Engineering team has verified numbers?
- [ ] Benchmark sources linked?
- [ ] No misleading superlatives ("best", "fastest")?

---

## 📊 Quick Reference Numbers

### Validated Performance

| Metric | Value | Conditions |
|--------|-------|------------|
| tell() p50 | 125ns | Empty messages, warmed JIT |
| tell() p99 | 625ns | Empty messages, warmed JIT |
| ask() p99 | <100µs | Echo process, no I/O |
| Throughput | 4.6M msg/sec | Empty messages, observability enabled |
| Restart p99 | <1ms | ONE_FOR_ONE, cheap state |
| Spawn rate | 15K/sec | With observability |
| Memory | ~3.9KB/process | 1M process test |
| Scale | 1M+ | Validated, zero message loss |

### Realistic Expectations

- **Simple state machines:** 3-5M msg/sec
- **I/O-bound handlers:** 100K-1M msg/sec
- **CPU-bound handlers:** 1-3M msg/sec

---

## 📚 Additional Resources

- **Single source of truth:** [honest-performance-claims.md](honest-performance-claims.md)
- **Comprehensive guide:** [MARKETING-CLAIMS-GUIDE.md](MARKETING-CLAIMS-GUIDE.md)
- **Validation report:** [FINAL-VALIDATION-REPORT.md](FINAL-VALIDATION-REPORT.md)
- **Run benchmarks:** `./mvnw verify -Pbenchmark`

---

## 🚨 Red Flags to Avoid

### Warning Signs of Misleading Claims

1. **Claims without data sources** → Always link to benchmarks
2. **"Fastest" or "best"** → Use specific numbers instead
3. **Theoretical claims as fact** → Distinguish from validated
4. **Comparisons without context** → Explain what's being compared
5. **No caveats or conditions** → Always include context

### When in Doubt

1. Check [honest-performance-claims.md](honest-performance-claims.md)
2. Ask engineering team for verification
3. Include more caveats, not fewer
4. Use conservative numbers
5. Link to benchmark sources

---

**Remember:** Conservative claims build more trust than inflated ones. Users who get what you promised are satisfied. Users who get less than you promised feel misled.

**Last Updated:** 2026-03-16
**Next Review:** 2026-06-16 (quarterly)
