# JOTP Release Guide

Complete guide for releasing JOTP to Maven Central, including versioning, deployment procedures, and troubleshooting.

## Quick Reference

### Essential Commands

```bash
# Prepare a release
./scripts/release/prepare.sh 1.0.0

# Deploy to Maven Central
./scripts/release/perform.sh 1.0.0

# Validate deployment
./scripts/release/validate.sh 1.0.0 --full

# Rollback if needed
./scripts/release/rollback.sh 1.0.0
```

### Release Checklist

- [ ] Pre-release validation complete
- [ ] Version bumped in pom.xml
- [ ] CHANGELOG.md updated
- [ ] Tag created and pushed
- [ ] Deployed to Maven Central
- [ ] Artifacts validated on Central
- [ ] GitHub release created
- [ ] Documentation updated

## Prerequisites

### System Requirements

- **Java 26+** (required for Java 26 features)
- **Maven 4** (mvnd recommended) or Maven wrapper
- **Git** (for version control and tagging)
- **GPG** (for artifact signing)

### Credentials Required

1. **Sonatype Central Portal Account**
   - Sign up at: https://central.sonatype.com
   - Create a User Token (OSSRH_TOKEN)
   - Store token in: `export OSSRH_TOKEN=your_token`

2. **GPG Key Pair**
   - Generate key: `gpg --full-generate-key`
   - Export key ID: `gpg --list-secret-keys --keyid-format LONG`
   - Set environment: `export GPG_KEYNAME=your_key_id`

3. **GitHub Access Token** (optional, for GitHub releases)
   - Create token with `repo` scope
   - Set environment: `export GH_TOKEN=your_token`

### Maven Configuration

Create or update `~/.m2/settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>central</id>
      <username>${env.OSSRH_TOKEN}</username>
      <password>${env.OSSRH_TOKEN}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>gpg</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <properties>
        <gpg.keyname>${env.GPG_KEYNAME}</gpg.keyname>
        <gpg.passphrase>${env.GPG_PASSPHRASE}</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
</settings>
```

## Versioning

### Semantic Versioning

JOTP follows [Semantic Versioning 2.0.0](https://semver.org/):

```
MAJOR.MINOR.PATCH[-PRERELEASE]
```

- **MAJOR**: Breaking changes (e.g., `1.0.0` → `2.0.0`)
- **MINOR**: New features, backward compatible (e.g., `1.0.0` → `1.1.0`)
- **PATCH**: Bug fixes, backward compatible (e.g., `1.0.0` → `1.0.1`)
- **PRERELEASE**: Alpha, beta, RC (e.g., `1.0.0-beta`)

### Version Examples

```
1.0.0         # First stable release
1.0.1         # Bug fix release
1.1.0         # Feature release
1.1.0-beta    # Beta prerelease
1.1.0-rc.1    # Release candidate
2.0.0         # Major version with breaking changes
2.0.0-alpha   # Alpha for next major version
```

### When to Bump Versions

| Change Type | Version Bump | Example |
|------------|-------------|---------|
| Bug fixes | PATCH | `1.0.0` → `1.0.1` |
| New features | MINOR | `1.0.0` → `1.1.0` |
| Breaking changes | MAJOR | `1.0.0` → `2.0.0` |
| Pre-releases | PRERELEASE | `1.0.0` → `1.0.0-beta` |

## Release Process

### Phase 1: Pre-Release Preparation

#### 1.1 Verify Build Quality

```bash
# Run full test suite
./mvnw clean verify -T1C

# Check code quality
./mvnw spotless:check
./mvnw javadoc:javadoc

# Run integration tests
./mvnw verify -DskipTests=false
```

#### 1.2 Update Documentation

- Update `CHANGELOG.md` with release notes
- Update `RELEASE_NOTES.md` if needed
- Verify README.md is current
- Update version-specific documentation

#### 1.3 Finalize Version

Determine the next version based on changes:

```bash
# Check current version
grep -A 1 "<artifactId>jotp</artifactId>" pom.xml | grep "<version>"

# Decide on version bump based on changes
# - Bug fixes only → PATCH (1.0.0 → 1.0.1)
# - New features → MINOR (1.0.0 → 1.1.0)
# - Breaking changes → MAJOR (1.0.0 → 2.0.0)
```

### Phase 2: Prepare Release

#### 2.1 Run Prepare Script

```bash
# Prepare release (interactive)
./scripts/release/prepare.sh 1.0.0

# Prepare beta release
./scripts/release/prepare.sh 1.0.0-beta --beta

# Dry run to see changes without committing
./scripts/release/prepare.sh 1.0.0 --dry-run
```

**What happens:**
- Validates version format
- Checks for uncommitted changes
- Backs up `pom.xml`
- Updates version in `pom.xml`
- Creates `CHANGELOG.md` with git commit history
- Creates release branch: `release/{version}`
- Runs full test suite
- Commits changes
- Creates annotated tag: `v{version}`

#### 2.2 Review Changes

```bash
# Review the release branch
git log main..release/1.0.0

# Review version change
git diff main..release/1.0.0 -- pom.xml

# Review changelog
git diff main..release/1.0.0 -- CHANGELOG.md
```

#### 2.3 Adjust if Needed

```bash
# Make additional changes
git checkout release/1.0.0
# ... make changes ...
git add .
git commit -m "Additional release adjustments"

# Update tag
git tag -d v1.0.0
git tag -a v1.0.0 -m "Release 1.0.0"
```

### Phase 3: Deploy Release

#### 3.1 Run Deploy Script

```bash
# Deploy to Maven Central
./scripts/release/perform.sh 1.0.0

# Local build only (no deployment)
./scripts/release/perform.sh 1.0.0 --local-only

# Dry run
./scripts/release/perform.sh 1.0.0 --dry-run
```

**What happens:**
- Checks out release tag
- Verifies version matches
- Builds project with tests
- Generates GPG signatures
- Validates deployment bundle
- Deploys to Maven Central
- Verifies deployment
- Generates deployment report

#### 3.2 Monitor Deployment

```bash
# Check deployment report
cat target/deployment-report-1.0.0.txt

# Visit Central Portal
# https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp/1.0.0
```

#### 3.3 Wait for Sync

Maven Central sync takes **10-30 minutes**:

```bash
# Wait and validate
sleep 1800  # 30 minutes
./scripts/release/validate.sh 1.0.0 --remote
```

### Phase 4: Validate Release

#### 4.1 Run Validation Script

```bash
# Full validation (local + remote)
./scripts/release/validate.sh 1.0.0 --full

# Verbose output
./scripts/release/validate.sh 1.0.0 --full --verbose
```

**What is validated:**

**Local:**
- Required files exist (JAR, POM, sources, Javadoc)
- GPG signatures are valid
- Javadoc is complete
- POM structure is correct
- Artifact integrity

**Remote:**
- Artifacts available on Maven Central
- GPG signatures published
- Maven can resolve dependency
- Central Portal shows deployment

#### 4.2 Manual Verification

```bash
# Test Maven can download artifact
mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:1.0.0

# Download and verify artifact
curl -O https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/1.0.0/jotp-1.0.0.jar
jar tf jotp-1.0.0.jar | grep module-info.class
```

### Phase 5: Complete Release

#### 5.1 Merge Release Branch

```bash
# Checkout main
git checkout main

# Merge release branch
git merge release/1.0.0

# Push to GitHub
git push origin main
```

#### 5.2 Push Tag

```bash
# Push tag to GitHub
git push origin v1.0.0
```

#### 5.3 Create GitHub Release

```bash
# Using GitHub CLI
gh release create v1.0.0 \
  --title "JOTP 1.0.0" \
  --notes-file RELEASE_NOTES.md

# Or manually via GitHub web UI
# https://github.com/seanchatmangpt/jotp/releases/new
```

#### 5.4 Update Next Development Version

```bash
# Bump to next development version
./scripts/release/prepare.sh 1.1.0-SNAPSHOT

# Or manually update pom.xml
# <version>1.1.0-SNAPSHOT</version>

# Commit version bump
git add pom.xml
git commit -m "Bump version to 1.1.0-SNAPSHOT"
git push origin main
```

### Phase 6: Post-Release

#### 6.1 Update Documentation

- Update website with new version
- Announce release on community channels
- Update migration guides if needed
- Archive previous release notes

#### 6.2 Monitor for Issues

- Monitor GitHub Issues for release-specific bugs
- Monitor Maven Central download stats
- Gather user feedback
- Prepare for next release cycle

## Rollback Procedures

### When to Rollback

- Deployment fails with critical errors
- Artifacts are corrupted or incomplete
- Wrong version was released
- Security vulnerabilities discovered

### Rollback Steps

```bash
# Full rollback
./scripts/release/rollback.sh 1.0.0

# Review what will be rolled back
./scripts/release/rollback.sh 1.0.0 --dry-run

# Local-only rollback
./scripts/release/rollback.sh 1.0.0 --local-only
```

### Manual Rollback

If automated script fails:

```bash
# Delete local tag
git tag -d v1.0.0

# Delete remote tag
git push origin :refs/tags/v1.0.0

# Delete local branch
git branch -D release/1.0.0

# Delete remote branch
git push origin :refs/heads/release/1.0.0

# Restore previous version
git checkout main
# ... manually revert pom.xml changes ...
```

### Maven Central Rollback

Maven Central artifacts require manual deletion:

1. Log in to https://central.sonatype.com
2. Navigate to the artifact
3. Click "Drop" to request deletion
4. Wait for Sonatype to process request
5. Contact support if urgent: https://issues.sonatype.org

**Note:** Dropped artifacts may remain in caches but will be marked as unavailable.

## Troubleshooting

### Common Issues

#### Issue: GPG signing fails

**Symptoms:**
```
gpg: signing failed: Inappropriate ioctl for device
```

**Solutions:**
```bash
# Set GPG TTY
export GPG_TTY=$(tty)

# Use loopback mode
echo "your_passphrase" | gpg --pinentry-mode loopback --passphrase-fd 0 --clearsign

# Or set in pom.xml
<gpgArguments>
  <arg>--pinentry-mode</arg>
  <arg>loopback</arg>
</gpgArguments>
```

#### Issue: Maven Central 401 Unauthorized

**Symptoms:**
```
Return code is: 401, ReasonPhrase: Unauthorized
```

**Solutions:**
```bash
# Verify OSSRH_TOKEN is set
echo $OSSRH_TOKEN

# Regenerate token at: https://central.sonatype.com/publishing/admin
# Update environment variable
export OSSRH_TOKEN="new_token"

# Verify token works
curl -u "${OSSRH_TOKEN}:${OSSRH_TOKEN}" \
  https://central.sonatype.com/api/v1/publisher/status
```

#### Issue: Artifacts not found after deployment

**Symptoms:**
- Maven Central returns 404
- `mvn dependency:get` fails

**Solutions:**
```bash
# Wait for sync (10-30 minutes)
sleep 1800

# Validate deployment
./scripts/release/validate.sh 1.0.0 --remote

# Check Central Portal
curl https://central.sonatype.com/api/v1/publisher/deployments

# Check maven-metadata.xml
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/maven-metadata.xml
```

#### Issue: Javadoc validation fails

**Symptoms:**
```
[ERROR] Error fetching URL: https://docs.oracle.com/en/java/javase/26/docs/api/
```

**Solutions:**
```bash
# Generate javadoc with offline mode
./mvnw javadoc:javadoc -DofflineLinks=true

# Or skip doclint temporarily
./mvnw javadoc:javadoc -Ddoclint=none

# Fix broken references in source code
# Then regenerate
```

#### Issue: Git tag already exists

**Symptoms:**
```
fatal: tag 'v1.0.0' already exists
```

**Solutions:**
```bash
# Delete and recreate tag
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0

# Or use existing tag
git checkout v1.0.0
```

### Getting Help

If you encounter issues not covered here:

1. **Check the script README**: `scripts/release/README.md`
2. **Review deployment reports**: `target/deployment-report-*.txt`
3. **Check GitHub Actions**: https://github.com/seanchatmangpt/jotp/actions
4. **Search existing issues**: https://github.com/seanchatmangpt/jotp/issues
5. **Create new issue**: Include logs and error messages

## Best Practices

### Pre-Release

1. **Test thoroughly**: Run full test suite locally
2. **Review changes**: Ensure all intended changes are included
3. **Update documentation**: Keep docs in sync with code
4. **Check dependencies**: Verify no security vulnerabilities
5. **Backup**: Ensure you can rollback if needed

### During Release

1. **Use dry-run mode**: Test with `--dry-run` first
2. **Monitor deployment**: Watch Central Portal for status
3. **Validate artifacts**: Run validation scripts
4. **Be patient**: Maven Central sync takes time
5. **Keep logs**: Save deployment reports for reference

### Post-Release

1. **Announce promptly**: Let users know about new release
2. **Monitor issues**: Watch for release-specific bugs
3. **Update guides**: Keep documentation current
4. **Gather feedback**: Learn from user experience
5. **Plan next iteration**: Start thinking about next version

## Release Automation vs. Manual Release

### Automated Release (Recommended)

**Pros:**
- Consistent process
- Less error-prone
- Faster
- Better documentation
- Easier rollback

**Cons:**
- Requires initial setup
- Script maintenance needed

**When to use:**
- Regular releases
- Multiple maintainers
- CI/CD integration

### Manual Release

**Pros:**
- Full control
- No script dependencies
- Flexible for edge cases

**Cons:**
- Error-prone
- Time-consuming
- Inconsistent
- Harder to rollback

**When to use:**
- Emergency hotfixes
- Script failures
- One-time special cases

## Continuous Integration

### GitHub Actions Workflow

JOTP includes GitHub Actions workflows for automated releases:

- `.github/workflows/release.yml` - Main release pipeline
- `.github/workflows/publish.yml` - Maven Central publishing
- `.github/workflows/gpg-key-management.yml` - GPG key rotation

These workflows automatically:
- Run tests on multiple Java versions
- Build and sign artifacts
- Deploy to Maven Central
- Create GitHub releases
- Send notifications

### Manual GitHub Actions Trigger

```bash
# Trigger release workflow manually
gh workflow run release.yml \
  -f version=1.0.0 \
  -f pre_release=false \
  -f dry_run=false
```

## Resources

### Official Documentation

- [Maven Central Publishing Guide](https://central.sonatype.org/publish/)
- [Apache Maven POM Reference](https://maven.apache.org/pom.html)
- [GPG Documentation](https://gnupg.org/documentation/)
- [Semantic Versioning](https://semver.org/)

### JOTP Documentation

- [Main README](README.md)
- [RELEASE_NOTES.md](RELEASE_NOTES.md)
- [Scripts README](scripts/release/README.md)
- [Architecture Guide](.claude/ARCHITECTURE.md)

### External Tools

- [GitHub CLI](https://cli.github.com/)
- [Maven Daemon (mvnd)](https://maven.apache.org/mvnd/)
- [Sonatype Central Portal](https://central.sonatype.com)

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
