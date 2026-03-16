# JOTP Test Archive

This directory organizes tests by maturity level and execution profile.

## Structure

### test-infrastructure/
Test infrastructure and utilities (32 tests)
- Framework testing helpers
- Assertion libraries
- Test fixtures
- Mock implementations

### experimental/
Experimental v2.0 features (62 tests)
- Cutting-edge OTP primitives
- Experimental patterns
- API preview features
- May be unstable or changing

### performance/
Performance and stress tests (27 tests)
- Load testing
- Breaking point analysis
- Resource exhaustion scenarios
- Latency benchmarks
- Uses 120s timeout

### broken/
Malformed or debug tests
- Tests with known issues
- Debugging scenarios
- Works-in-progress
- Not for production validation

## Execution

```bash
# Core tests only (default)
mvnd test

# Include test infrastructure
mvnd test -Parchive-infra

# Include experimental features
mvnd test -Parchive-experimental

# Include stress tests
mvnd test -Parchive-stress

# All archived tests
mvnd test -Parchive-all

# Only broken tests (for debugging)
mvnd test -Parchive-broken
```

## Profiles

See pom.xml lines 444-558 for profile definitions.
