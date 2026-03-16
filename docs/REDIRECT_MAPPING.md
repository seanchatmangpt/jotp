# Documentation Reorganization - Redirect Mapping

This document maps old documentation file paths to their new locations after the master reorganization.

## Root Level Files

| Old Path | New Path | Section |
|----------|----------|---------|
| `README.md` | `docs/user-guide/README.md` | User Guide |
| `RELEASE.md` | `docs/archive/release/RELEASE.md` | Archive |
| `RELEASE_NOTES.md` | `docs/archive/release/RELEASE_NOTES.md` | Archive |
| `LAUNCH-CHECKLIST.md` | `docs/roadmap/implementation/LAUNCH-CHECKLIST.md` | Roadmap |
| `MAKEFILE_GUIDE.md` | `docs/infrastructure/tooling/MAKEFILE_GUIDE.md` | Infrastructure |
| `PERFORMANCE-REGRESSION-ANALYSIS-SUMMARY.md` | `docs/validation/performance/PERFORMANCE-REGRESSION-ANALYSIS-SUMMARY.md` | Validation |
| `benchmark-regression-analysis-report.md` | `docs/validation/performance/benchmark-regression-analysis-report.md` | Validation |
| `REGRESSION-FINAL-ANALYSIS.md` | `docs/validation/performance/REGRESSION-FINAL-ANALYSIS.md` | Validation |
| `act-troubleshooting.md` | `docs/infrastructure/tooling/act-troubleshooting.md` | Infrastructure |
| `chapter-7-case-studies.md` | `docs/archive/legacy/chapter-7-case-studies.md` | Archive |
| `chapter-8-case-studies.md` | `docs/archive/legacy/chapter-8-case-studies.md` | Archive |
| `chapter-9-summary.md` | `docs/archive/legacy/chapter-9-summary.md` | Archive |
| `REORGANIZATION_SUMMARY.md` | `docs/archive/project-history/REORGANIZATION_SUMMARY.md` | Archive |

## Roadmap Directory

| Old Path | New Path | Section |
|----------|----------|---------|
| `roadmap/VERNON_PATTERNS_STATUS.md` | `docs/roadmap/implementation/VERNON_PATTERNS_STATUS.md` | Roadmap |

## Documentation Subdirectories

### Research Consolidation

| Old Path | New Path | Section |
|----------|----------|---------|
| `docs/academic/*` | `docs/research/academic/*` | Research |
| `docs/phd-thesis/*` | `docs/research/phd-thesis/*` | Research |
| `docs/innovations/*` | `docs/research/innovations/*` | Research |
| `docs/innovation/*` | `docs/research/innovation/*` | Research |
| `docs/reports/*` | `docs/research/reports/*` | Research |

### User Guide Consolidation

| Old Path | New Path | Section |
|----------|----------|---------|
| `docs/patterns/*` | `docs/user-guide/patterns/*` | User Guide |
| `docs/tutorials/*` | `docs/user-guide/tutorials/*` | User Guide |
| `docs/reference/*` | `docs/user-guide/reference/*` | User Guide |
| `docs/architecture/*` | `docs/user-guide/architecture/*` | User Guide |
| `docs/system/*` | `docs/user-guide/system/*` | User Guide |

### Validation Consolidation

| Old Path | New Path | Section |
|----------|----------|---------|
| `docs/performance/*` | `docs/validation/performance/*` | Validation |

### Infrastructure Consolidation

| Old Path | New Path | Section |
|----------|----------|---------|
| `docs/testing/*` | `docs/infrastructure/testing/*` | Infrastructure |

### Archive Consolidation

| Old Path | New Path | Section |
|----------|----------|---------|
| `docs/migration/*` | `docs/archive/migration/*` | Archive |
| `docs/release/*` | `docs/archive/release/*` | Archive |
| `docs/project-history/*` | `docs/archive/project-history/*` | Archive |

## Directory Migrations

### Complete Directory Moves

| Old Directory | New Directory | File Count |
|---------------|----------------|-------------|
| `docs/patterns/` | `docs/user-guide/patterns/` | 30 files |
| `docs/tutorials/` | `docs/user-guide/tutorials/` | 15 files |
| `docs/reference/` | `docs/user-guide/reference/` | 36 files |
| `docs/architecture/` | `docs/user-guide/architecture/` | 7 files |
| `docs/system/` | `docs/user-guide/system/` | 10 files |
| `docs/migration/` | `docs/archive/migration/` | 6 files |
| `docs/release/` | `docs/archive/release/` | 8 files |
| `docs/project-history/` | `docs/archive/project-history/` | 10 files |
| `docs/testing/` | `docs/infrastructure/testing/` | 2 files |
| `docs/performance/` | `docs/validation/performance/` | 9 files |
| `docs/reports/` | `docs/research/reports/` | 5 files |

## Link Update Guidelines

When updating documentation links:

1. **User Documentation**: Links to `docs/user-guide/`
   - Examples, tutorials, patterns, reference

2. **Research Materials**: Links to `docs/research/`
   - Thesis, papers, innovations, academic work

3. **Implementation Planning**: Links to `docs/roadmap/`
   - Future work, implementation guides

4. **Production Evidence**: Links to `docs/validation/`
   - Case studies, benchmarks, test results

5. **Development Tooling**: Links to `docs/infrastructure/`
   - CI/CD, testing, build system

6. **Historical Content**: Links to `docs/archive/`
   - Legacy docs, release history, migration guides

## Automated Update Commands

### Update Markdown Links

```bash
# Update absolute links in markdown files
find . -name "*.md" -type f -exec sed -i '' 's|docs/patterns/|docs/user-guide/patterns/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/tutorials/|docs/user-guide/tutorials/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/reference/|docs/user-guide/reference/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/architecture/|docs/user-guide/architecture/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/system/|docs/user-guide/system/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/performance/|docs/validation/performance/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/testing/|docs/infrastructure/testing/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/migration/|docs/archive/migration/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/release/|docs/archive/release/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|docs/project-history/|docs/archive/project-history/|g' {} +
```

### Update Relative Links

```bash
# Update relative links from root
find . -name "*.md" -type f -exec sed -i '' 's|](patterns/|](docs/user-guide/patterns/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|](tutorials/|](docs/user-guide/tutorials/|g' {} +
find . -name "*.md" -type f -exec sed -i '' 's|](reference/|](docs/user-guide/reference/|g' {} +
```

## Validation

After updating links, validate:

```bash
# Find broken links
find . -name "*.md" -type f -exec grep -H "\](.*\.md)" {} \; | while read line; do
  # Extract link path and check if file exists
  # Add validation logic here
done
```

## Migration Statistics

- **Total Files Migrated**: 165 files
- **Root Level Files**: 13 files
- **Directory Consolidations**: 11 directories
- **Empty Directories Removed**: 15 directories
- **New README Files Created**: 6 files

## Notes

- Books directory remains separate: `books/`
- Reactive messaging book preserved: `docs/reactive-messaging-book/`
- Cloud documentation preserved: `docs/cloud/`
- Sequence diagrams preserved: `docs/sequence-diagrams/`

---

**Reorganization Date**: 2026-03-15
**Total Documentation Files**: 376 files
**Sections**: 6 main sections + special collections
