# JOTP Versioning Policy

This document defines the versioning scheme, compatibility guarantees, and release cadence for JOTP.

---

## Semantic Versioning

JOTP follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html):

**Format**: `MAJOR.MINOR.PATCH`

- **MAJOR**: Incompatible API changes
- **MINOR**: Backwards-compatible functionality additions
- **PATCH**: Backwards-compatible bug fixes

**Pre-release identifiers**: `-alpha.1`, `-beta.1`, `-rc.1`

**Build metadata**: `+sha.abc1234`

### Examples

```
1.0.0      - Initial stable release
1.1.0      - New features, backwards compatible
1.1.1      - Bug fix, backwards compatible
2.0.0      - Breaking changes
2.0.0-rc.1 - Release candidate for 2.0.0
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
   - Minimum: **one minor version** (e.g., deprecate in 1.0, remove in 2.0)
   - Recommended: **one major version** (e.g., deprecate in 1.0, remove in 3.0)
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
 *             This method will be removed in version 2.0.0.
 * @see #send(Message, Duration)
 */
@Deprecated(since = "1.5.0", forRemoval = true)
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

JOTP guarantees backward compatibility for:

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
   - `ProcRef` serialization format (within major version)
   - Event message serialization (within major version)

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
mvnd verify -Dcompatibility=X.Y.Z

# Runs tests that verify:
# - Old code still compiles
# - Old behavior preserved
# - Migration paths work
```

---

## Release Cadence

### Schedule

- **Major Releases**: Every 6-12 months (significant new features)
- **Minor Releases**: Every 2-3 months (new features, improvements)
- **Patch Releases**: As needed (bug fixes, security issues)

### Time-Based Releases

Target release schedule:
- **Q1 (January-March)**: Major release (odd years: 1.0.0, 3.0.0)
- **Q2 (April-June)**: Minor releases
- **Q3 (July-September)**: Major release (even years: 2.0.0, 4.0.0)
- **Q4 (October-December)**: Minor releases

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

**Current Stable (X.Y.Z)**: Recommended for production
- Fully tested
- Complete documentation
- Migration guides available

**Previous Stable (X.Y-1.Z)**: Supported for 6 months
- Security patches only
- No new features

**End of Life (EOL)**: No longer supported
- No updates
- Archives available on Maven Central

### Support Matrix

| Version | Status        | Support Until | Updates          |
|---------|---------------|---------------|------------------|
| 2.x.x   | Current       | 2026-12-31    | All              |
| 1.5.x   | Previous      | 2026-06-30    | Security only    |
| 1.4.x   | EOL           | 2025-12-31    | None             |

---

## Dependency Versioning

### Java Version Policy

- **Minimum Java Version**: Java 26 (with preview features)
- **Tested Versions**: Latest Java 26 release
- **Java Upgrade Policy**: May bump minimum version in major releases only

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
- Always targets next minor/major version
- Named `main`
- Merges from feature branches

### Release Branches
- Created for each release: `release/X.Y.Z`
- Stabilization only (no new features)
- Merged back to main after release

### LTS Branches
- Long-term support branches: `lts/X.Y.x`
- For major versions only
- Security patches only

### Hotfix Branches
- Created from release tags: `hotfix/X.Y.Z+1`
- Merge to main and release branch

---

## Release Naming

### Version Number Assignment

**Major Version Decision Factors**:
- Breaking changes to core API
- Removal of deprecated APIs
- Significant architectural changes
- Java version bump

**Minor Version Decision Factors**:
- New features (backward compatible)
- Significant improvements
- New modules or components
- Major documentation updates

**Patch Version Decision Factors**:
- Bug fixes
- Security fixes
- Documentation corrections
- Build improvements

### Pre-Release Versioning

```
1.0.0-alpha.1    - First alpha
1.0.0-alpha.2    - Second alpha
1.0.0-beta.1     - First beta
1.0.0-beta.2     - Second beta
1.0.0-rc.1       - First release candidate
1.0.0-rc.2       - Second release candidate
1.0.0            - Final release
```

---

## Compliance & Validation

### Pre-Release Validation Checklist

Every release MUST validate:
- [ ] Semantic versioning compliance
- [ ] No breaking changes in minor/patch
- [ ] All breaking changes documented
- [ ] Migration guides created
- [ ] Compatibility tests pass
- [ ] Deprecation policy followed
- [ ] Support matrix updated

### Automated Checks

```bash
# Verify version compliance
mvnd verify -Dversion-compliance

# Checks for:
# - Undocumented breaking changes
# - API additions without version update
# - Deprecation without timeline
# - Compatibility issues
```

---

## Related Documentation

- [Release Process](release-process.md)
- [Changelog Template](changelog-template.md)
- [Migration Guide Template](migration-guide-template.md)
- [Release History](release-history.md)

---

**Last Updated**: 2025-01-15
**Version**: 1.0.0
**Maintained By**: Project Lead
