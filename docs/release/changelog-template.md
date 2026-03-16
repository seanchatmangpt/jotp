# Changelog Template

All notable changes to JOTP will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- <!-- New features go here -->
- Example: Added `Proc.timeout(Duration)` for message delivery timeouts

### Changed
- <!-- Improvements to existing functionality -->
- Example: Improved supervisor restart performance by 40%

### Deprecated
- <!-- Features that will be removed in future releases -->
- Example: `Proc.sendWithoutTimeout()` is deprecated in favor of `Proc.send()`

### Removed
- <!-- Features removed in this release -->
- Example: Removed deprecated `ProcMonitor.simple()` method

### Fixed
- <!-- Bug fixes -->
- Example: Fixed race condition in `ProcRegistry.unregister()`

### Security
- <!-- Security fixes -->
- Example: Fixed potential mailbox overflow vulnerability

## [X.Y.Z] - YYYY-MM-DD

### Breaking Changes
- <!-- Describe breaking changes with migration guidance -->
- **BREAKING**: `Proc<S,M>` now requires explicit message type parameter `M`
  - Migration: Update all `Proc` declarations to include message type
  - See [Migration Guide](migration-guide-X.Y.Z.md) for details

### Added
- <!-- List new features -->

### Changed
- <!-- List improvements -->

### Deprecated
- <!-- List deprecations -->

### Removed
- <!-- List removals -->

### Fixed
- <!-- List fixes -->

### Security
- <!-- List security fixes -->

## [Previous Versions]

<!-- Maintain historical changelog entries here -->

---

## Changelog Format Guidelines

### Section Definitions

**Added**: New features
**Changed**: Changes to existing functionality
**Deprecated**: Soon-to-be removed features
**Removed**: Features removed in this release
**Fixed**: Bug fixes
**Security**: Security vulnerability fixes

### Breaking Changes Format

Each breaking change MUST include:
1. Clear description of what changed
2. Migration path or alternative approach
3. Link to detailed migration guide if needed

Example:
```markdown
### Breaking Changes
- **BREAKING**: `Supervisor.restartStrategy` changed from enum to sealed interface
  - Migration: Replace `RestartStrategy.ONE_FOR_ONE` with `OneForOne.INSTANCE`
  - Rationale: Enables custom restart strategies
  - See [Migration Guide](migration-guide-X.Y.Z.md#supervisor-restart-strategy)
```

### Contributors Section

For each release, acknowledge contributors:

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Contributors
@username - Description of contributions
@username2 - Description of contributions

Full contributor list: [CONTRIBUTORS.md](../CONTRIBUTORS.md)
```

### Linking Issues

Reference related issues:
```markdown
- Added timeout support for `Proc.ask()` (#123)
- Fixed supervisor child restart logic (#456)
```

### Migration Guide References

For breaking changes, always reference a migration guide:
```markdown
See [Migration Guide X.Y.Z](migration-guide-X.Y.Z.md) for complete migration instructions.
```
