# JOTP Versioning Policy

This document defines the versioning scheme, compatibility guarantees, and release cadence for JOTP.

---

## Calendar Versioning (CalVer)

JOTP uses [Calendar Versioning](https://calver.org/) with the format:

**Format**: `YYYY.MINOR.PATCH`

- **YYYY**: Full year of release (e.g., `2026`)
- **MINOR**: Feature increment within the year (starting at `1`, incrementing per feature release)
- **PATCH**: Bug-fix increment for a given MINOR (starting at `0`)

This matches the versioning scheme used by the sibling project [DTR](https://github.com/seanchatmangpt/dtr) (`2026.4.1`).

### Examples

```
2026.1.0   - First release of 2026
2026.1.1   - Bug fix for 2026.1.0
2026.2.0   - Second feature release of 2026
2026.2.1   - Bug fix for 2026.2.0
2027.1.0   - First release of 2027
```

### Pre-release identifiers

Append to the base version:

```
2026.1.0-alpha.1   - Early development
2026.1.0-beta.1    - Feature complete, testing phase
2026.1.0-rc.1      - Release candidate
2026.1.0           - Final release
```

---

## Breaking Change Criteria

A change is considered BREAKING if it meets ANY of these criteria:

### API Breaking Changes
1. **Removed or renamed public API**
   - Removed class, interface, method, or field
   - Changed method signature (parameters, return type)
   - Moved class to different package

2. **Behavioral changes**
   - Changed default behavior with no opt-out
   - Changed exception types thrown
   - Changed thread-safety guarantees
   - Changed performance characteristics significantly

3. **Type system changes**
   - Changed generic type parameters
   - Modified sealed type hierarchy
   - Changed record field types

4. **Dependency changes**
   - Increased minimum Java version
   - Added new runtime dependencies
   - Changed license terms

### NOT Breaking Changes

These changes are **NOT** considered breaking:
- Adding new public APIs
- Fixing bugs (even if behavior changes)
- Performance improvements
- Documentation updates
- Internal implementation changes
- Adding new optional parameters with defaults
- Deprecating APIs (still functional)

---

## Deprecation Policy

### Deprecation Process

1. **Announce Deprecation**
   - Mark API as `@Deprecated`
   - Document replacement API
   - Add Javadoc explanation
   - Issue warning in documentation

2. **Grace Period**
   - Minimum: **one MINOR version** (e.g., deprecate in 2026.1.0, remove in 2026.3.0)
   - Recommended: **one calendar year** (e.g., deprecate in 2026.x.y, remove in 2027.x.y)
   - Exception: Security issues may remove immediately

3. **Removal**
   - Remove deprecated API
   - Update migration guide
   - Mention in release notes

### Deprecation Example

```java
/**
 * Sends a message without timeout.
 *
 * @deprecated Use {@link #send(Message, Duration)} with explicit timeout instead.
 *             This method will be removed in version 2027.1.0.
 * @see #send(Message, Duration)
 */
@Deprecated(since = "2026.1.0", forRemoval = true)
public void sendWithoutTimeout(Message message) {
  send(message, Duration.ofSeconds(30));
}
```

### Migration Path

Every deprecation MUST include:
1. Clear explanation of why deprecated
2. Replacement API or approach
3. Code example showing migration
4. Link to detailed migration guide

---

## Backward Compatibility Guarantees

### Compatibility Scope

JOTP guarantees backward compatibility within a MINOR series (e.g., `2026.1.x`):

1. **Public API**
   - All classes in `io.github.seanchatmangpt.jotp` package
   - All public and protected methods
   - All record components
   - All sealed interface permitted subclasses

2. **Behavioral Compatibility**
   - Message passing semantics
   - Supervisor restart strategies
   - Process lifecycle
   - Error handling behavior

3. **Serialization**
   - `ProcRef` serialization format (within MINOR series)
   - Event message serialization (within MINOR series)

### Exclusions

NO compatibility guarantees for:
- Internal implementation classes
- Private APIs
- Experimental features (marked `@Experimental`)
- Incubator modules (e.g., `messaging`, `enterprise`)
- Java 26 preview feature evolution

### Compatibility Testing

Every release MUST validate:
```bash
# Test against previous version's behavior
mvnd verify -Dcompatibility=2026.1.0

# Runs tests that verify:
# - Old code still compiles
# - Old behavior preserved
# - Migration paths work
```

---

## Release Cadence

### Schedule

- **MINOR releases**: Every 2-4 months (new features, improvements)
- **PATCH releases**: As needed (bug fixes, security issues)
- **Year boundary** (e.g., `2026.x.y` → `2027.1.0`): January, aligned with new Java LTS or major platform changes

### Exception Process

Unscheduled releases for:
- **Critical security issues**: Immediate patch release
- **Data loss bugs**: Within 48 hours
- **High-priority fixes**: Within 1 week
- **Other bugs**: Next scheduled release

---

## Version Lifecycle

### Pre-Release Versions

**Alpha**: Early development, unstable API
- Don't use in production
- API may change without notice
- No migration guides provided

**Beta**: Feature complete, testing needed
- API mostly stable
- Minor breaking changes possible
- Migration guides for major changes

**Release Candidate (RC)**: Production-ready pending validation
- API frozen
- Only critical bug fixes
- Full migration guides

### Stable Releases

**Current Stable**: Recommended for production
- Fully tested
- Complete documentation
- Migration guides available

**Previous MINOR**: Supported for 6 months after next MINOR release
- Security patches only
- No new features

**End of Life (EOL)**: No longer supported
- No updates
- Archives available on Maven Central

### Support Matrix

| Version    | Status   | Support Until | Updates       |
|------------|----------|---------------|---------------|
| 2026.1.x   | Current  | 2026-12-31    | All           |
| (previous) | N/A      | N/A           | N/A           |

---

## Maven Central Coordinates

```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>2026.1.0</version>
</dependency>
```

Releases are published to [Maven Central](https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp) via the Sonatype Central Portal.

---

## Dependency Versioning

### Java Version Policy

- **Minimum Java Version**: Java 26 (with preview features)
- **Tested Versions**: Latest Java 26 release
- **Java Upgrade Policy**: May bump minimum version in new-year releases only

### Runtime Dependencies

**Current**: JOTP has ZERO runtime dependencies (pure Java 26)

**Future Policy**: If dependencies are added:
- Must be compatible with Java 26
- Must be stable releases (no alpha/beta)
- Must have compatible licenses
- Must be documented in release notes

### Build Dependencies

Minimum versions:
- Maven 4.0+
- Java 26+
- JUnit 5.10+

---

## Branching Strategy

### Main Branch
- Always targets next MINOR version
- Named `main`
- Merges from feature branches

### Release Tags
- CalVer tags: `v2026.1.0`, `v2026.2.0`, etc.
- Trigger the GitHub Actions release pipeline

### Patch Branches
- Created for hotfixes: `release/2026.1.x`
- Security patches and critical bug fixes only

---

## Pre-Release Validation Checklist

Every release MUST validate:
- [ ] CalVer version correct (YYYY matches release year)
- [ ] No breaking changes in PATCH releases
- [ ] All breaking changes documented in MINOR releases
- [ ] Migration guides created for breaking changes
- [ ] Compatibility tests pass
- [ ] Deprecation policy followed
- [ ] Support matrix updated
- [ ] Maven Central publishing verified

---

## Related Documentation

- [Release Process](release-process.md)
- [Changelog Template](changelog-template.md)
- [Migration Guide Template](migration-guide-template.md)
- [Release History](release-history.md)

---

**Last Updated**: 2026-03-17
**Version**: 2026.1.0
**Maintained By**: Project Lead
