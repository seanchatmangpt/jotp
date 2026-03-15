# JOTP Release Automation Scripts

Production-ready automation scripts for publishing JOTP to Maven Central with proper validation, rollback support, and comprehensive error handling.

## Overview

The release automation consists of four coordinated scripts:

1. **`prepare.sh`** - Prepare release (version bump, changelog, tag creation)
2. **`perform.sh`** - Perform deployment to Maven Central
3. **`validate.sh`** - Validate artifacts (local and remote)
4. **`rollback.sh`** - Rollback failed releases

## Quick Start

### Prerequisites

#### Required Tools

```bash
# Java 26+
java -version  # Should show 26+

# Maven 4 (mvnd) or Maven wrapper
mvnd --version
# OR
./mvnw --version

# Git
git --version

# GPG (for signing)
gpg --version
```

#### Required Credentials

```bash
# Maven Central Token (OSSRH)
export OSSRH_TOKEN="your_token_here"
# OR
export CENTRAL_TOKEN="your_token_here"

# GPG Key
export GPG_KEYNAME="your_key_id"
export GPG_PASSPHRASE="your_passphrase"
```

#### Maven Settings (`~/.m2/settings.xml`)

```xml
<settings>
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

## Release Workflow

### Step 1: Prepare Release

```bash
# Prepare a new release
./scripts/release/prepare.sh 1.0.0

# Prepare a beta release
./scripts/release/prepare.sh 1.0.0-beta --beta

# Dry run to see what would happen
./scripts/release/prepare.sh 2.0.0 --dry-run
```

**What `prepare.sh` does:**
- Validates version format (semantic versioning)
- Checks for uncommitted changes
- Backs up `pom.xml`
- Updates version in `pom.xml`
- Creates/updates `CHANGELOG.md` with git commits
- Creates release branch: `release/{version}`
- Runs full test suite (unless `--skip-tests`)
- Commits version and changelog changes
- Creates annotated git tag: `v{version}`

### Step 2: Perform Deployment

```bash
# Deploy to Maven Central
./scripts/release/perform.sh 1.0.0

# Build and sign locally without deploying
./scripts/release/perform.sh 1.0.0 --local-only

# Dry run to verify deployment process
./scripts/release/perform.sh 1.0.0 --dry-run
```

**What `perform.sh` does:**
- Checks out release tag
- Verifies version in `pom.xml` matches tag
- Builds project with tests
- Generates GPG signatures for all artifacts
- Validates deployment bundle (JAR, POM, sources, Javadoc)
- Deploys to Maven Central via Central Portal
- Verifies deployment on Central Portal
- Generates deployment report

### Step 3: Validate Deployment

```bash
# Validate local artifacts only
./scripts/release/validate.sh 1.0.0 --local

# Validate Maven Central artifacts
./scripts/release/validate.sh 1.0.0 --remote

# Full validation (local + remote)
./scripts/release/validate.sh 1.0.0 --full

# Verbose output
./scripts/release/validate.sh 1.0.0 --full --verbose
```

**What `validate.sh` checks:**

**Local validation:**
- Required files exist (JAR, POM, sources, Javadoc)
- GPG signatures are valid
- Javadoc is complete
- POM structure is correct
- Checksum files present
- Artifact integrity (jar is valid, contains classes)

**Remote validation:**
- Artifacts available on Maven Central
- GPG signatures published
- Version in maven-metadata.xml
- Maven can resolve dependency
- Central Portal shows deployment

### Step 4: Complete Release

After successful validation:

```bash
# Merge release branch to main
git checkout main
git merge release/1.0.0
git push origin main

# Push tag (if not already pushed)
git push origin v1.0.0

# Create GitHub release (optional)
gh release create v1.0.0 --notes "Release 1.0.0"
```

## Rollback Procedures

### Full Rollback

```bash
# Rollback everything (local + remote + Maven Central)
./scripts/release/rollback.sh 1.0.0

# Dry run to see what would be rolled back
./scripts/release/rollback.sh 1.0.0 --dry-run

# Skip confirmation prompts
./scripts/release/rollback.sh 1.0.0 --force
```

**What `rollback.sh` does:**
- Detects what can be rolled back (tags, branches, Maven Central)
- Deletes local git tag
- Deletes remote git tag
- Deletes local release branch
- Deletes remote release branch
- Provides instructions for Maven Central manual rollback
- Restores previous version in `pom.xml`
- Generates rollback report

### Local-Only Rollback

```bash
# Rollback local changes only (keep remote deployment)
./scripts/release/rollback.sh 1.0.0 --local-only
```

### Maven Central Manual Rollback

Maven Central artifacts cannot be automatically removed once synced. Manual steps:

1. Log in to [Central Portal](https://central.sonatype.com)
2. Navigate to your artifact
3. Click "Drop" to request deletion
4. Or contact Sonatype support via [issues.sonatype.org](https://issues.sonatype.org)

**Note:** Dropped artifacts may remain in Maven Central's cache but will be marked as unavailable.

## Troubleshooting

### GPG Signing Issues

**Problem:** GPG signing fails with "secret key not available"

**Solution:**
```bash
# List available GPG keys
gpg --list-secret-keys --keyid-format LONG

# Set GPG_KEYNAME to the key ID (e.g., ABCD1234...)
export GPG_KEYNAME="ABCD1234..."

# Verify key can sign
echo "test" | gpg --clearsign
```

**Problem:** GPG passphrase prompt appears during build

**Solution:**
```bash
# Set GPG passphrase
export GPG_PASSPHRASE="your_passphrase"

# Or use gpg-agent with preset passphrase
echo "your_passphrase" | gpg --pinentry-mode loopback --passphrase-fd 0 --clearsign
```

### Maven Central Deployment Issues

**Problem:** 401 Unauthorized during deployment

**Solution:**
```bash
# Verify OSSRH_TOKEN is set
echo $OSSRH_TOKEN

# Test credentials
curl -u "${OSSRH_TOKEN}:${OSSRH_TOKEN}" \
  https://central.sonatype.com/api/v1/publisher/status

# Regenerate token if needed at:
# https://central.sonatype.com/publishing/admin
```

**Problem:** Deployment times out or fails

**Solution:**
```bash
# Check Maven Central status
curl https://central.sonatype.com/api/v1/publisher/status

# Retry deployment
./scripts/release/perform.sh 1.0.0

# Or use local-only build and deploy manually
./scripts/release/perform.sh 1.0.0 --local-only
# Then manually upload via Central Portal UI
```

### Artifact Validation Issues

**Problem:** Artifacts not found on Maven Central after deployment

**Solution:**
```bash
# Wait 10-30 minutes for Central sync
# Then validate again
./scripts/release/validate.sh 1.0.0 --remote

# Check maven-metadata.xml
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/maven-metadata.xml

# Test with Maven
mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:1.0.0
```

**Problem:** Javadoc validation fails

**Solution:**
```bash
# Regenerate Javadoc locally
mvn javadoc:javadoc -T1C

# Check for broken references
mvn javadoc:javadoc -T1C -Ddoclint=all

# Fix issues in source code, then rebuild
```

### Git and Branch Issues

**Problem:** Release branch already exists

**Solution:**
```bash
# Delete existing branch
git branch -D release/1.0.0

# Or use existing branch
git checkout release/1.0.0

# Or force recreate with prepare script
./scripts/release/prepare.sh 1.0.0 --force
```

**Problem:** Tag already exists

**Solution:**
```bash
# Delete local and remote tags
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0

# Recreate tag
./scripts/release/prepare.sh 1.0.0
```

## Advanced Usage

### Custom Maven Central Repository

If not using Sonatype Central Portal:

```bash
# Set custom repository URL
export MAVEN_CENTRAL_URL="https://your-custom-repo.com"

# Update pom.xml distributionManagement
# Then deploy normally
./scripts/release/perform.sh 1.0.0
```

### Build Profiles

```bash
# Deploy with specific Maven profile
mvn deploy -P release -P custom-profile

# Skip tests during deployment
mvn deploy -DskipTests=true

# Increase Maven memory
export MAVEN_OPTS="-Xmx2g -Xms1g"
./scripts/release/perform.sh 1.0.0
```

### Automated Release Pipeline

```bash
#!/bin/bash
VERSION="1.0.0"

# Prepare
./scripts/release/prepare.sh $VERSION || exit 1

# Deploy
./scripts/release/perform.sh $VERSION || exit 1

# Wait for Maven Central sync
echo "Waiting 60 seconds for Maven Central sync..."
sleep 60

# Validate
./scripts/release/validate.sh $VERSION --full || exit 1

echo "Release $VERSION completed successfully!"
```

## Release Checklist

### Pre-Release

- [ ] All tests passing locally
- [ ] Code reviewed and approved
- [ ] Documentation updated
- [ ] CHANGELOG.md updated with release notes
- [ ] Version number follows semantic versioning
- [ ] GPG key available and not expired
- [ ] OSSRH_TOKEN valid and not expired

### Release

- [ ] Run `prepare.sh` to create release branch and tag
- [ ] Review changelog and version changes
- [ ] Run `perform.sh` to deploy to Maven Central
- [ ] Monitor Central Portal for deployment status
- [ ] Run `validate.sh --remote` after 10-30 minutes
- [ ] Test artifact download with Maven

### Post-Release

- [ ] Merge release branch to main
- [ ] Push tag to GitHub
- [ ] Create GitHub release with notes
- [ ] Update website/documentation with new version
- [ ] Announce release to community
- [ ] Monitor for issues with new release
- [ ] Start next development iteration

## Versioning Scheme

JOTP follows [Semantic Versioning 2.0.0](https://semver.org/):

### Format

```
MAJOR.MINOR.PATCH-PRERELEASE
```

### Examples

- `1.0.0` - First stable release
- `1.1.0` - New features (backward compatible)
- `1.1.1` - Bug fixes (backward compatible)
- `2.0.0` - Breaking changes
- `2.0.0-beta` - Prerelease version

### Bump Guidelines

- **MAJOR**: Breaking changes, API changes
- **MINOR**: New features, backward compatible additions
- **PATCH**: Bug fixes, minor improvements

### Pre-release Identifiers

- `alpha` - Early development, incomplete features
- `beta` - Feature complete, testing phase
- `rc` - Release candidate, final testing
- `SNAPSHOT` - Development version (not for release)

## Support

### Documentation

- [Main README](../../README.md)
- [RELEASE_NOTES.md](../../docs/release/RELEASE_NOTES.md)
- [Maven Central Publishing Guide](https://central.sonatype.org/publish/)

### Issues

Report issues with release scripts at:
https://github.com/seanchatmangpt/jotp/issues

### Resources

- [Maven Central Portal](https://central.sonatype.com)
- [Sonatype Publishing Guide](https://central.sonatype.org/publish/)
- [GPG Documentation](https://gnupg.org/documentation/)
- [Semantic Versioning](https://semver.org/)

## License

Apache License 2.0 - See [LICENSE](../../LICENSE) for details.
