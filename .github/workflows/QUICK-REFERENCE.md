# JOTP CI/CD Workflows - Quick Reference

## 📁 Workflow Files

| File | Purpose | Size |
|------|---------|------|
| `.github/workflows/ci.yml` | Main CI pipeline | 8.6KB |
| `.github/workflows/quality.yml` | Code quality checks | 10KB |
| `.github/workflows/release.yml` | Maven Central publishing | 13KB |
| `.github/workflows/WORKFLOWS-GUIDE.md` | Complete documentation | - |
| `.github/workflows/QUICK-REFERENCE.md` | This file | - |

## 🚀 Quick Start

### 1. Setup Required Secrets

Go to: **Settings → Secrets and variables → Actions**

```bash
# Maven Central
CENTRAL_USERNAME=your-sonatype-username
CENTRAL_TOKEN=your-sonatype-token

# GPG Signing
GPG_PRIVATE_KEY="-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----"
GPG_PASSPHRASE=your-gpg-passphrase
GPG_KEY_ID=ABC12345
```

### 2. Trigger Workflows

#### CI Pipeline (Automatic)
```bash
# Push to main or develop
git push origin main
git push origin develop

# Create pull request
gh pr create --base main
```

#### Quality Checks (Automatic + Weekly)
```bash
# Automatic on push/PR
# Weekly: Mondays at 9 AM UTC
# Manual: Actions → Code Quality → Run workflow
```

#### Release (Manual or Tag-based)
```bash
# Automatic: Create version tag
git tag v1.0.0
git push origin v1.0.0

# Manual: Actions → Release to Maven Central → Run workflow
```

## 📊 Workflow Matrix

| Workflow | Jobs | Time | Artifacts |
|----------|------|------|-----------|
| **ci.yml** | 7 jobs (build, quality, coverage, security, integration, package, summary) | ~10-15 min | Test results, coverage reports |
| **quality.yml** | 8 jobs (spotless, checkstyle, pmd, spotbugs, deps, license, coverage, summary) | ~15-20 min | Analysis reports |
| **release.yml** | 6 jobs (validate, build, sign, deploy, release, notify) | ~10-15 min | JARs, signatures |

## 🔧 Key Features

### CI Pipeline (ci.yml)
- ✅ Multi-version Java testing (21, 22, 26)
- ✅ Unit and integration tests
- ✅ Coverage reporting with JaCoCo
- ✅ Security scanning (Trivy + OWASP)
- ✅ Artifact packaging

### Code Quality (quality.yml)
- ✅ Google Java Format enforcement (Spotless)
- ✅ Checkstyle static analysis
- ✅ PMD code quality checks
- ✅ SpotBugs bug detection
- ✅ Dependency analysis
- ✅ License validation
- ✅ Coverage thresholds (80%)

### Release Pipeline (release.yml)
- ✅ Semantic version validation
- ✅ GPG signing of all artifacts
- ✅ Maven Central deployment
- ✅ GitHub release creation
- ✅ Release notes from commits
- ✅ Dry-run mode support

## 🎯 Common Commands

### Check Workflow Status
```bash
# List recent runs
gh run list --workflow=ci.yml
gh run list --workflow=quality.yml
gh run list --workflow=release.yml

# View specific run
gh run view <run-id>

# Watch logs in real-time
gh run watch
```

### Local Testing with Act
```bash
# Install Act
brew install act  # macOS
curl https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash

# Test CI workflow
act -j build -W .github/workflows/ci.yml

# Test release workflow
act -j validate-release -W .github/workflows/release.yml --dry-run

# List all jobs
act -l -W .github/workflows/ci.yml
```

### Manual Workflow Trigger
```bash
# Using GitHub CLI
gh workflow run ci.yml
gh workflow run quality.yml
gh workflow run release.yml -f version=1.0.0

# Via GitHub Web UI
# Navigate to: Actions → [Workflow Name] → Run workflow
```

## 📦 Artifacts and Reports

### Available Artifacts
| Artifact | Source | Retention |
|----------|--------|-----------|
| `test-results-java*` | CI pipeline | 7 days |
| `coverage-report` | CI + Quality | 7 days |
| `checkstyle-report` | Quality | 7 days |
| `pmd-report` | Quality | 7 days |
| `spotbugs-report` | Quality | 7 days |
| `owasp-dependency-check` | CI | 7 days |
| `jotp-artifacts` | CI + Release | 7 days |
| `signed-artifacts` | Release | 7 days |

### Download Artifacts
```bash
# Using GitHub CLI
gh run download <run-id> -n coverage-report

# Via Web UI
# Actions → [Run] → Artifacts
```

## 🔍 Troubleshooting

### Build Failures
```bash
# Check Java version
java -version  # Should be 26

# Verify Maven
./mvnw -version

# Clean build
./mvnw clean compile -T1C
```

### GPG Signing Issues
```bash
# List GPG keys
gpg --list-secret-keys --keyid-format LONG

# Test signing
echo "test" | gpg --clearsign

# Import key (if needed)
gpg --import private-key.asc
```

### Maven Central Deployment
```bash
# Verify credentials
echo "Test credentials..."  # Check Central Portal

# Test deployment (dry-run)
gh workflow run release.yml -f version=1.0.0-test -f dry_run=true

# Check deployment status
curl -u "$CENTRAL_USERNAME:$CENTRAL_TOKEN" \
  https://central.sonatype.com/api/v1/publisher
```

## 📈 Quality Gates

### Passing Criteria
- ✅ All tests pass (unit + integration)
- ✅ Code formatted with Spotless
- ✅ No critical security vulnerabilities
- ✅ Coverage ≥ 80% (configurable)
- ✅ All licenses validated
- ✅ No critical code quality issues

### Failing Conditions
- ❌ Test failures
- ❌ Spotless formatting violations
- ❌ Critical security issues (CVSS ≥ 7)
- ❌ Coverage below threshold
- ❌ License header violations
- ❌ GPG signing failures

## 🚨 Emergency Procedures

### Rollback Release
```bash
# Via Maven Central Portal
# 1. Login to https://central.sonatype.com
# 2. Find the deployment
# 3. Request drop (if within 24 hours)

# Delete GitHub release
gh release delete v1.0.0 -y
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0
```

### Fix Broken Deployment
```bash
# 1. Fix the issue
git checkout main
# Make fixes...

# 2. Increment version
# Update pom.xml: 1.0.0 → 1.0.1

# 3. Release new version
git tag v1.0.1
git push origin v1.0.1
```

## 📚 Documentation Links

- **Complete Guide**: [WORKFLOWS-GUIDE.md](./WORKFLOWS-GUIDE.md)
- **Project Architecture**: [docs/architecture/README.md](../../docs/architecture/README.md)
- **Maven Publishing**: [docs/release/](../../docs/release/)
- **GitHub Actions Docs**: https://docs.github.com/actions
- **Maven Central**: https://central.sonatype.com

## 🔄 Workflow Status

| Workflow | Status | Last Run |
|----------|--------|----------|
| CI Pipeline | ✅ Active | [View](../../actions) |
| Code Quality | ✅ Active | [View](../../actions) |
| Release | ✅ Active | [View](../../actions) |

## 💡 Tips and Best Practices

1. **Always run CI before creating PRs**
2. **Keep dependencies up to date** (Use Dependabot)
3. **Review quality reports** before merging
4. **Test releases with dry-run first**
5. **Monitor deployment status** after releases
6. **Keep GPG keys secure** (rotate annually)
7. **Review security scans** regularly
8. **Maintain high test coverage** (target: >80%)

## 🆘 Support

For issues or questions:
1. Check [WORKFLOWS-GUIDE.md](./WORKFLOWS-GUIDE.md)
2. Review workflow logs in GitHub Actions
3. Check Maven Central Portal status
4. Open a GitHub issue with details

---

*Last updated: 2025-03-14*
