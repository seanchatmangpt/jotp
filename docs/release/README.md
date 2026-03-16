# JOTP Release Documentation

This directory contains comprehensive documentation for managing JOTP releases, including templates, processes, and policies.

---

## 📋 Documentation Files

### Templates

1. **[changelog-template.md](changelog-template.md)**
   - Template for maintaining CHANGELOG.md
   - Follows Keep a Changelog format
   - Includes semantic versioning guidelines

2. **[release-notes-template.md](release-notes-template.md)**
   - Template for release notes
   - Executive summary and technical highlights
   - Migration guide references
   - Known issues and upgrade instructions

3. **[migration-guide-template.md](migration-guide-template.md)**
   - Template for migration guides
   - Breaking changes documentation
   - Before/after code examples
   - Testing and rollback procedures

### Process & Policy

4. **[release-process.md](release-process.md)**
   - Complete release workflow
   - Pre-release preparation checklist
   - Testing requirements
   - Maven Central publishing steps
   - Post-release monitoring

5. **[versioning-policy.md](versioning-policy.md)**
   - Semantic versioning rules
   - Breaking change criteria
   - Deprecation policy and timeline
   - Backward compatibility guarantees
   - Release cadence

### Tracking & History

6. **[upcoming-release.md](upcoming-release.md)**
   - Features in development
   - Planned improvements
   - Roadmap milestones
   - Target dates
   - Contributing opportunities

7. **[release-history.md](release-history.md)**
   - Previous releases summary
   - Version compatibility matrix
   - Upgrade paths
   - Known issues by version
   - Performance history

---

## 🚀 Quick Start

### Preparing a New Release

1. **Copy templates**:
   ```bash
   cp release-notes-template.md release-notes-X.Y.Z.md
   cp migration-guide-template.md migration-guide-X.Y.Z.md
   ```

2. **Update changelog**:
   - Add entry to CHANGELOG.md (root directory)
   - Follow [changelog-template.md](changelog-template.md) format

3. **Follow release process**:
   - See [release-process.md](release-process.md) for complete workflow

### For Contributors

See [upcoming-release.md](upcoming-release.md) for:
- Features planned for next release
- How to contribute
- RFCs (Request for Comments)
- Areas needing help

### For Users

See [release-history.md](release-history.md) for:
- Current stable version
- Upgrade paths from older versions
- Known issues by version
- Compatibility matrix

---

## 📚 Related Documentation

- **[../CHANGELOG.md](../CHANGELOG.md)** - Full changelog
- **[../README.md](../README.md)** - Project overview
- **[../book/src/SUMMARY.md](../book/src/SUMMARY.md)** - User guide
- **[CONTRIBUTING.md](../CONTRIBUTING.md)** - Contributing guidelines

---

## 🔄 Release Workflow

```
1. Planning → upcoming-release.md
2. Development → Feature branches
3. Testing → Pre-release checklist
4. Documentation → release notes + migration guide
5. Release → release-process.md
6. History → release-history.md
```

---

## 📝 Template Usage

### Creating Release Notes

```bash
# Copy template
cp release-notes-template.md release-notes-1.5.0.md

# Edit with release-specific information
# - Executive summary
# - New features
# - Breaking changes
# - Migration guide
# - Known issues
```

### Creating Migration Guide

```bash
# Copy template
cp migration-guide-template.md migration-guide-1.5.0.md

# Edit with breaking changes
# - What changed
# - Migration steps
# - Code examples
# - Testing procedures
```

---

## 🎯 Release Checklists

### Pre-Release
- [ ] All tests passing
- [ ] Documentation complete
- [ ] Release notes written
- [ ] Migration guide ready (if breaking changes)
- [ ] Version updated

### Post-Release
- [ ] Maven Central verified
- [ ] GitHub release published
- [ ] Announcements posted
- [ ] Documentation updated
- [ ] Metrics collected

---

## 📊 Version Status

| Version | Status | Support Until | Release Date |
|---------|--------|---------------|--------------|
| 1.5.0 | ✅ Current | 2026-06-30 | 2025-01-15 |
| 1.4.0 | 🟡 Previous | 2025-12-31 | 2024-10-01 |
| 1.3.0 | ⚠️ EOL | 2025-06-30 | 2024-07-15 |

See [release-history.md](release-history.md) for complete history.

---

## 🤝 Contributing to Releases

Want to help with releases?

1. **Test pre-release versions**: Follow [upcoming-release.md](upcoming-release.md) for beta testing opportunities
2. **Report bugs**: Use GitHub issues with template
3. **Propose features**: Start an RFC in GitHub Discussions
4. **Review PRs**: Help test and review pull requests
5. **Write documentation**: Improve guides and examples

---

## 📧 Contact

- **Release Issues**: [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Release Discussions**: [GitHub Discussions](https://github.com/seanchatmangpt/jotp/discussions)
- **Security Issues**: See [SECURITY.md](../SECURITY.md)

---

**Last Updated**: 2025-01-15
**Maintained By**: Release Manager
