# File Redirect Mapping

This document maps old file locations to new locations after the March 2026 documentation reorganization. Use this to update external links and bookmarks.

## Documentation Structure Reorganization

### Root-Level Files → New Locations

| Old Path | New Path | Status |
|----------|----------|--------|
| `VISION-2030.md` | `docs/visions/VISION-2030.md` | Moved |
| `LAUNCH-CHECKLIST.md` | `docs/release/LAUNCH-CHECKLIST.md` | Moved |
| `CLAUDE.md` | `docs/development/CLAUDE.md` | Moved |
| `RELEASE.md` | `docs/release/RELEASE.md` | Moved |
| `RELEASE_NOTES.md` | `docs/release/RELEASE_NOTES.md` | Moved |

### `.claude/` Directory → New Locations

The `.claude/` directory has been removed as part of the cleanup. Content has been redistributed:

| Old Path | New Path | Status |
|----------|----------|--------|
| `.claude/ARCHITECTURE.md` | `docs/architecture/README.md` | Moved |
| `.claude/AGENTS.md` | `docs/development/AGENTS.md` | Moved |
| `.claude/HOOKS.md` | `docs/development/HOOKS.md` | Moved |
| `.claude/PLAN-MODE.md` | `docs/development/PLAN-MODE.md` | Moved |
| `.claude/SKILLS.md` | `docs/development/SKILLS.md` | Moved |
| `.claude/SLA-PATTERNS.md` | `docs/architecture/enterprise/sla-patterns.md` | Moved |
| `.claude/INTEGRATION-PATTERNS.md` | `docs/architecture/enterprise/integration-patterns.md` | Moved |
| `.claude/SESSION-SUMMARY.md` | `docs/development/SESSION-SUMMARY.md` | Moved |
| `.claude/ARCHITECTURE_PART4A.md` | `docs/architecture/enterprise/architecture-part4a.md` | Moved |
| `.claude/BENCHMARK-REPORTING-SUMMARY.md` | `docs/validation/benchmark-reporting-summary.md` | Moved |
| `.claude/devops-checklist.md` | `docs/infrastructure/devops-checklist.md` | Moved |
| `.claude/settings.json` | `docs/development/settings.json` | Moved |
| `.claude/rules/java-source.md` | `docs/development/java-source-rules.md` | Moved |
| `.claude/branch-validation-prompt.md` | `docs/development/branch-validation-prompt.md` | Moved |
| `.claude/gonogo-refactor-prompt.md` | `docs/development/gonogo-refactor-prompt.md` | Moved |
| `.claude/ralph-loop.local.md` | `docs/development/ralph-loop.md` | Moved |
| `.claude/hooks/simple-guards.sh` | `docs/development/hooks/simple-guards.sh` | Moved |
| `.claude/setup.sh` | `docs/development/setup.sh` | Moved |

### Main Documentation Reorganization

#### Tutorials
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/tutorials/01-getting-started.md` | `docs/tutorials/beginner/getting-started.md` | Moved |
| `docs/tutorials/02-first-process.md` | `docs/tutorials/beginner/first-process.md` | Moved |
| `docs/tutorials/03-virtual-threads.md` | `docs/tutorials/intermediate/virtual-threads.md` | Moved |
| `docs/tutorials/04-supervision-basics.md` | `docs/tutorials/intermediate/supervision-basics.md` | Moved |

#### How-To Guides
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/how-to/*` | `docs/user-guide/how-to/*` | Moved |
| `docs/how-to/building-autonomous-systems.md` | `docs/user-guide/how-to/building-autonomous-systems.md` | Moved |

#### Explanations
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/explanations/*` | `docs/user-guide/explanations/*` | Moved |

#### Reference Documentation
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/reference/api-proc.md` | `docs/reference/api/proc.md` | Moved |
| `docs/reference/api-supervisor.md` | `docs/reference/api/supervisor.md` | Moved |
| `docs/reference/api-statemachine.md` | `docs/reference/api/statemachine.md` | Moved |
| `docs/reference/api-eventmanager.md` | `docs/reference/api/eventmanager.md` | Moved |

#### PhD Thesis
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/phd-thesis-otp-java26.md` | `docs/research/phd-thesis/thesis.md` | Moved |
| `docs/phd-thesis/chapter6-empirical-results.md` | `docs/research/phd-thesis/phd-thesis-chapter6-empirical-results.md` | Moved |
| `docs/phd-thesis/chapter9-discussion.md` | `docs/research/phd-thesis/phd-thesis-chapter9-discussion.md` | Moved |

#### Cloud Deployment (Archived)
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/cloud/*` | `docs/archive/cloud-deployment/cloud/*` | Archived |

#### Project History
| Old Path | New Path | Status |
|----------|----------|--------|
| `docs/project-history/refactoring-2026-03-12/` | `docs/archive/refactoring-2026-03-12/` | Moved |

### Books Directory
| Old Path | New Path | Status |
|----------|----------|--------|
| `book/src/*` | `docs/user-guide/` | Consolidated |
| `books/jotpops/` | `docs/books/jotpops/` | Moved |
| `books/jotp-patterns/` | `docs/books/jotp-patterns/` | Moved |

### GitHub Actions Workflows
| Old Path | New Path | Status |
|----------|----------|--------|
| `.github/workflows/README.md` | `.github/workflows/WORKFLOWS-GUIDE.md` | Consolidated |
| `.github/workflows/QUICK-REFERENCE.md` | `.github/workflows/WORKFLOWS-QUICK-REFERENCE.md` | Renamed |

## External Link Updates

If you have external links pointing to the old locations, please update them using the mapping above. The most common changes are:

1. **Root-level docs** → `docs/` subdirectories
2. **`.claude/`** → `docs/development/` or specialized locations
3. **`docs/how-to/`** → `docs/user-guide/how-to/`
4. **`docs/explanations/`** → `docs/user-guide/explanations/`
5. **`docs/phd-thesis/`** → `docs/research/phd-thesis/`
6. **`docs/cloud/`** → `docs/archive/cloud-deployment/cloud/`

## Automatic Redirects

For web-deployed documentation, consider implementing automatic redirects using:

- **GitHub Pages**: Use `_redirects` file or Jekyll redirects
- **Static Site Generators**: Use redirect plugins
- **Web Servers**: Use 301 redirects in nginx/Apache configuration

## Validation Status

- ✅ README.md cross-references updated
- ✅ Main documentation index updated
- 🔄 Individual documentation files being updated
- 🔄 GitHub Actions workflow references being updated
- 🔄 External link validation pending

## Notes

- Some files may have been consolidated or split during reorganization
- Check the [docs/index.md](docs/index.md) for the current documentation structure
- Use [docs/](docs/) as the entry point for all documentation
- Archived content is preserved in `docs/archive/` for historical reference

**Last Updated:** March 15, 2026
**Reorganization Version:** 2026.03
