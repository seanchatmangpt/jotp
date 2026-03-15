# JOTP Release Process

This document outlines the complete process for preparing, testing, publishing, and monitoring JOTP releases.

---

## 🎯 Release Overview

JOTP follows a structured release process to ensure quality, stability, and reliability. All releases go through multiple stages including preparation, testing, documentation, publishing, and post-release monitoring.

**Release Cadence:**
- **Major Releases:** Every 6-12 months (significant features/architecture changes)
- **Minor Releases:** Every 1-3 months (new features, improvements)
- **Patch Releases:** As needed (bug fixes, security fixes)

---

## 📋 Pre-Release Checklist

### 1. Feature Freeze

**Timeline:** 2 weeks before release

- [ ] No new features merged to release branch
- [ ] All planned features implemented
- [ ] Feature branch development complete
- [ ] Release branch created (`release/x.y.z`)

```bash
git checkout -b release/x.y.z
git push -u origin release/x.y.z
```

### 2. Code Quality

**Timeline:** 1 week before release

- [ ] All tests passing (unit, integration, dogfood)
- [ ] Code coverage ≥ [TARGET]% (currently [CURRENT]%)
- [ ] No critical SonarQube issues
- [ ] Code formatted (Spotless)
- [ ] Javadoc complete and validated
- [ ] Static analysis passing

```bash
# Run full test suite
mvnd verify -Ddogfood

# Check coverage
mvnd jacoco:report

# Validate formatting
mvnd spotless:check

# Validate Javadoc
mvnd javadoc:javadoc
```

### 3. Documentation

**Timeline:** 1 week before release

- [ ] Release notes drafted
- [ ] Changelog updated
- [ ] Migration guide created (if breaking changes)
- [ ] API documentation updated
- [ ] Examples reviewed and updated
- [ ] Book content synchronized
- [ ] README version updated

### 4. Dependencies

**Timeline:** 1 week before release

- [ ] All dependencies updated to latest stable versions
- [ ] Security vulnerabilities addressed
- [ ] Dependency licenses reviewed
- [ ] Transitive dependencies validated

```bash
# Check for updates
mvnd versions:display-dependency-updates

# Check for vulnerabilities
mvnd org.owasp:dependency-check-maven:check
```

### 5. Performance

**Timeline:** 1 week before release

- [ ] Benchmarks run and documented
- [ ] Performance regression checks passed
- [ ] Memory leak checks completed
- [ ] Stress tests validated

```bash
# Run benchmarks
mvnd verify -Pbenchmark
```

### 6. Security

**Timeline:** 1 week before release

- [ ] Security audit completed
- [ ] Vulnerability scan passed
- [ ] Signed releases prepared
- [ ] GPG keys validated

```bash
# Run security scan
mvnd org.owasp:dependency-check-maven:check

# Verify GPG keys
gpg --list-keys
```

---

## 🧪 Testing Requirements

### Test Matrix

All releases must pass the following test suites:

#### 1. Unit Tests
**Command:** `mvnd test`
**Requirement:** 100% pass rate
**Duration:** < [TIME] minutes
**Coverage:** ≥ [TARGET]%

#### 2. Integration Tests
**Command:** `mvnd verify -DskipUnitTests`
**Requirement:** 100% pass rate
**Duration:** < [TIME] minutes

#### 3. Dogfood Tests
**Command:** `mvnd verify -Ddogfood`
**Requirement:** 100% pass rate
**Duration:** < [TIME] minutes
**Purpose:** Validate JOTP using JOTP

#### 4. Stress Tests
**Command:** `mvnd verify -Pstress`
**Requirement:** No crashes, acceptable performance degradation
**Duration:** ≥ [TIME] hours
**Load:** [Number] processes, [Number] messages/sec

#### 5. Performance Tests
**Command:** `mvnd verify -Pbenchmark`
**Requirement:** No regression > [PERCENTAGE]%
**Baseline:** [PREVIOUS_VERSION]

### Test Environments

Tests must run on:
- [ ] **Java 26** (primary target)
- [ ] **Multiple OS:** Linux, macOS, Windows
- [ ] **Multiple JVMs:** OpenJDK, GraalVM (if applicable)

### Regression Testing

For each release:
- [ ] Previous release test results compared
- [ ] Performance baseline established
- [ ] Flaky tests identified and addressed
- [ ] Test stability ≥ [PERCENTAGE]%

---

## 📝 Documentation Requirements

### Required Documents

Each release must include:

#### 1. Release Notes (`release-notes-[VERSION].md`)
- [ ] Executive summary
- [ ] Key highlights
- [ ] Breaking changes
- [ ] New features
- [ ] Upgrade instructions
- [ ] Known issues
- [ ] Performance metrics

#### 2. Changelog (`changelog-[VERSION].md`)
- [ ] All changes categorized
- [ ] Commit references
- [ ] Issue links
- [ ] Contributor credits
- [ ] Migration guide links

#### 3. Migration Guide (if breaking changes)
- [ ] Breaking change descriptions
- [ ] Before/after examples
- [ ] Step-by-step migration
- [ ] Testing recommendations
- [ ] Rollback procedures

#### 4. API Documentation
- [ ] New APIs documented
- [ ] Modified APIs updated
- [ ] Deprecated APIs marked
- [ ] Examples provided
- [ ] Javadoc complete

#### 5. Version Matrix
- [ ] Compatibility matrix updated
- [ ] Upgrade paths documented
- [ ] Dependency versions listed
- [ ] Support timelines defined

### Documentation Review

- [ ] Technical accuracy verified
- [ ] Grammar and spelling checked
- [ ] Examples tested
- [ ] Links validated
- [ ] Formatting consistent
- [ ] Reviewers approved

---

## 🚀 Release Preparation

### 1. Version Bump

Update version in all locations:

```bash
# Update pom.xml
vi pom.xml  # Update <version> to new version

# Update module-info.java if needed
vi src/main/java/module-info.java

# Update README.md
vi README.md  # Update version references

# Update documentation
vi docs/index.md  # Update version references
```

**Version Format:** `MAJOR.MINOR.PATCH`
- **MAJOR:** Breaking changes
- **MINOR:** New features (backward compatible)
- **PATCH:** Bug fixes (backward compatible)

### 2. Create Release Branch

```bash
# Create release branch from main
git checkout main
git pull origin main
git checkout -b release/x.y.z

# Push to remote
git push -u origin release/x.y.z
```

### 3. Update CHANGELOG.md

```bash
# Add new release section
vi CHANGELOG.md
```

Format:
```markdown
## [x.y.z] - YYYY-MM-DD

### Added
- Feature 1

### Changed
- Change 1

### Deprecated
- Deprecated feature 1

### Removed
- Removed feature 1

### Fixed
- Bug fix 1

### Security
- Security fix 1
```

### 4. Tag Release

```bash
# Create annotated tag
git tag -a vx.y.z -m "Release x.y.z: [Release summary]"

# Push tag
git push origin vx.y.z
```

---

## 📦 Maven Central Publishing

### Prerequisites

- [ ] Sonatype OSSRH account configured
- [ ] GPG key configured and distributed
- [ ] `settings.xml` configured with credentials
- [ ] Nexus staging repository access

### Publishing Steps

#### 1. Deploy to Sonatype OSSRH

```bash
# Clean and build
mvnd clean

# Deploy to Sonatype
mvnd deploy -DskipTests -P release,sonatype-oss-release

# Or use automated GitHub Action
gh workflow run publish-maven-central.yml -f version=x.y.z
```

#### 2. Verify in Nexus Staging

1. Login to [https://oss.sonatype.org](https://oss.sonatype.org)
2. Navigate to **Staging Repositories**
3. Find `iogithubseanchatmangpt-<id>`
4. **Close** the repository
5. Wait for validation (checks: signatures, javadoc, poms)
6. **Release** the repository

#### 3. Verify on Maven Central

```bash
# Wait 10-30 minutes for sync
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/x.y.z/

# Verify with Maven
mvnd dependency:get -Dartifact=io.github.seanchatmangpt:jotp:x.y.z
```

### Troubleshooting Publishing

**Common Issues:**

1. **GPG Signature Failed**
   ```bash
   # Verify GPG key
   gpg --list-keys

   # Test signing
   echo "test" | gpg --clearsign

   # Distribute key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```

2. **401 Unauthorized**
   - Check `settings.xml` credentials
   - Verify Sonatype token validity
   - Check account permissions

3. **Staging Repository Validation Failed**
   - Check javadoc errors: `mvnd javadoc:javadoc`
   - Verify POM files
   - Check for missing files

4. **Sync Delay**
   - Maven Central sync can take 10-30 minutes
   - Check Sonatype status page

---

## 🎉 GitHub Release

### Create GitHub Release

1. Navigate to **Releases** page
2. Click **Draft a new release**
3. Tag: `vx.y.z`
4. Title: `JOTP x.y.z`
5. Description: Copy from release notes template
6. Attach binaries (optional)
7. **Publish release**

### Release Announcement

Create announcement with:
- [ ] Release highlights
- [ ] Key features
- [ ] Breaking changes
- [ ] Upgrade link
- [ ] Documentation link

**Channels:**
- GitHub Discussions
- Project website
- Mailing list (if applicable)
- Social media (Twitter, LinkedIn)
- Community forums

---

## 🔍 Post-Release Monitoring

### Immediate Monitoring (First 24 Hours)

#### 1. Maven Central Metrics
- [ ] Download counts
- [ ] Dependency adoption
- [ ] Geographic distribution

#### 2. GitHub Activity
- [ ] New issues filed
- [ ] Issue types (bug, feature, question)
- [ ] Release-specific issues

#### 3. Community Feedback
- [ ] Discussion threads
- [ ] Stack Overflow questions
- [ ] Social media mentions
- [ ] Direct reports

### Issue Triage

Create post-release issue labels:
- `release/x.y.z` - Issues specific to this release
- `regression` - Potential regressions from previous version
- `critical` - Blocking issues requiring immediate attention

**Response SLA:**
- **Critical:** 4 hours
- **High:** 24 hours
- **Medium:** 3 days
- **Low:** 1 week

### Hotfix Process

If critical issue found:

#### 1. Create Hotfix Branch
```bash
git checkout main
git pull origin main
git checkout -b hotfix/x.y.z+1
```

#### 2. Implement Fix
- [ ] Fix the issue
- [ ] Add tests
- [ ] Document fix
- [ ] Update CHANGELOG

#### 3. Release Hotfix
```bash
# Version bump (patch increment)
mvnd versions:set -DnewVersion=x.y.(z+1)

# Commit and tag
git add .
git commit -m "Hotfix: [Issue description]"
git tag -a vx.y.(z+1) -m "Hotfix x.y.(z+1)"

# Push and release
git push origin hotfix/x.y.z+1
mvnd deploy -DskipTests -P release
```

#### 4. Merge Back
```bash
# Merge to main
git checkout main
git merge hotfix/x.y.(z+1)

# Merge to release branch (if exists)
git checkout release/x.y.z
git merge hotfix/x.y.(z+1)
```

### Feedback Collection

After 1 week:
- [ ] Collect user feedback
- [ ] Analyze usage patterns
- [ ] Identify common issues
- [ ] Document lessons learned
- [ ] Update release process if needed

### Metrics to Track

| Metric | Target | Actual |
|--------|--------|--------|
| Download count (week 1) | [Number] | [Number] |
| Critical issues | 0 | [Number] |
| Regression rate | < [PERCENTAGE]% | [PERCENTAGE]% |
| Documentation views | [Number] | [Number] |
| Community engagement | [Number] | [Number] |

---

## 📅 Release Timeline

### Typical Minor Release Timeline

| Phase | Duration | Activities |
|-------|----------|------------|
| Planning | 2 weeks | Feature planning, roadmap updates |
| Development | 4-6 weeks | Feature implementation, bug fixes |
| Feature Freeze | 1 week | No new features, focus on stability |
| Testing | 1 week | Full test suite, performance validation |
| Documentation | 1 week | Release notes, migration guides |
| Release Candidate | 3 days | Final testing, validation |
| Release | 1 day | Publishing, announcements |
| Post-Release | 1 week | Monitoring, bug triage |

**Total:** 8-10 weeks

### Patch Release Timeline

| Phase | Duration | Activities |
|-------|----------|------------|
| Fix Development | 1 week | Bug fix implementation |
| Testing | 2 days | Test suite, validation |
| Documentation | 1 day | Changelog, release notes |
| Release | 1 day | Publishing, announcements |
| Monitoring | 3 days | Issue tracking |

**Total:** 1-2 weeks

---

## ✅ Release Acceptance Criteria

A release is considered successful when:

- [ ] All tests passing (100% pass rate)
- [ ] Zero critical bugs
- [ ] Documentation complete and reviewed
- [ ] Published to Maven Central
- [ ] GitHub release created
- [ ] Announcement published
- [ ] Monitoring dashboard active
- [ ] No regressions detected (first 48 hours)

---

## 🔧 Release Automation

### GitHub Actions Workflows

JOTP uses automated workflows for:

#### 1. Release Workflow
**File:** `.github/workflows/release.yml`
**Triggers:** Manual dispatch with version parameter
**Actions:**
- Create release branch
- Update version numbers
- Run full test suite
- Create git tag
- Publish to Maven Central
- Create GitHub release

#### 2. Publish Workflow
**File:** `.github/workflows/publish-maven-central.yml`
**Triggers:** Tag push
**Actions:**
- Build and test
- Deploy to Sonatype OSSRH
- Close and release staging repository

#### 3. Monitoring Workflow
**File:** `.github/workflows/post-release-monitor.yml`
**Triggers:** Release published
**Actions:**
- Track Maven Central downloads
- Monitor GitHub issues
- Collect metrics

### Automated Changelog

Use conventional commits for automated changelog:

```bash
# Format
<type>(<scope>): <description>

# Types
feat:     New feature
fix:      Bug fix
docs:     Documentation change
style:    Code style change
refactor: Code refactoring
perf:     Performance improvement
test:     Test changes
chore:    Build process changes
```

**Generate changelog:**
```bash
# Using conventional-changelog
npx conventional-changelog -p angular -i CHANGELOG.md -s

# Or using git-cliff
git-cliff --tag vx.y.z > CHANGELOG.md
```

---

## 📚 Related Documentation

- [Versioning Policy](versioning-policy.md)
- [Migration Guide Template](migration-guide-template.md)
- [Release History](release-history.md)
- [Changelog Template](changelog-template.md)
- [Release Notes Template](release-notes-template.md)

---

## 🆘 Support

**Release Issues:** Open a GitHub issue with label `release-process`
**Documentation Issues:** Open a GitHub issue with label `docs`
**Questions:** Start a GitHub Discussion

---

**Last Updated:** [DATE]
**Maintained by:** [@username]
