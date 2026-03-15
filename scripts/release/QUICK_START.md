# JOTP Release Automation - Quick Start

## What Was Created

Production-ready release automation scripts for publishing JOTP to Maven Central.

## Files Created

```
scripts/release/
├── prepare.sh      # Prepare release (version bump, changelog, tag)
├── perform.sh      # Deploy to Maven Central
├── validate.sh     # Validate artifacts (local + remote)
├── rollback.sh     # Rollback failed releases
├── README.md       # Comprehensive documentation
└── QUICK_START.md  # This file

RELEASE.md          # Main release guide at project root
```

## Quick Start Guide

### 1. Prerequisites

```bash
# Check Java version (must be 26+)
java -version

# Check Maven (mvnd recommended)
mvnd --version

# Check GPG
gpg --version

# Set credentials
export OSSRH_TOKEN="your_maven_central_token"
export GPG_KEYNAME="your_gpg_key_id"
export GPG_PASSPHRASE="your_gpg_passphrase"
```

### 2. Prepare Release

```bash
# Prepare a new release (interactive)
./scripts/release/prepare.sh 1.0.0

# What it does:
# - Validates version format
# - Updates pom.xml version
# - Creates CHANGELOG.md
# - Creates release branch
# - Runs tests
# - Creates git tag
```

### 3. Deploy to Maven Central

```bash
# Deploy (interactive)
./scripts/release/perform.sh 1.0.0

# What it does:
# - Builds project
# - Signs artifacts with GPG
# - Validates deployment bundle
# - Deploys to Maven Central
# - Generates deployment report
```

### 4. Validate Deployment

```bash
# Validate (after 10-30 minutes for Central sync)
./scripts/release/validate.sh 1.0.0 --full

# What it checks:
# - Local artifacts (JAR, POM, sources, Javadoc)
# - GPG signatures
# - Maven Central availability
# - Maven dependency resolution
```

### 5. Complete Release

```bash
# Merge release branch to main
git checkout main
git merge release/1.0.0
git push origin main

# Push tag
git push origin v1.0.0

# Create GitHub release
gh release create v1.0.0 --notes "Release 1.0.0"
```

## Rollback (if needed)

```bash
# Rollback everything
./scripts/release/rollback.sh 1.0.0

# Or dry run first
./scripts/release/rollback.sh 1.0.0 --dry-run
```

## Script Features

### prepare.sh

- Version format validation
- Uncommitted changes detection
- Automatic CHANGELOG generation
- Release branch creation
- Test execution
- Git tag creation
- Dry-run mode

**Options:**
- `--dry-run` - Show changes without committing
- `--skip-tests` - Skip test validation
- `--beta` - Mark as beta/prerelease
- `--force` - Bypass safety checks

### perform.sh

- Tag validation
- Version verification
- Build with tests
- GPG signing
- Bundle validation
- Maven Central deployment
- Deployment report

**Options:**
- `--dry-run` - Show what would be deployed
- `--local-only` - Build locally without deploying
- `--skip-sign` - Skip GPG signing (for testing)

### validate.sh

- Local artifact validation
- GPG signature verification
- Javadoc completeness check
- POM structure validation
- Maven Central availability
- Dependency resolution test

**Options:**
- `--local` - Validate local artifacts only
- `--remote` - Validate Maven Central artifacts
- `--full` - Validate both local and remote
- `--verbose` - Show detailed output

### rollback.sh

- Detection of rollback targets
- Local tag deletion
- Remote tag deletion
- Local branch deletion
- Remote branch deletion
- Maven Central rollback instructions
- Previous version restoration

**Options:**
- `--dry-run` - Show what would be rolled back
- `--local-only` - Rollback local changes only
- `--force` - Skip confirmation prompts

## Common Workflows

### Standard Release

```bash
./scripts/release/prepare.sh 1.0.0
./scripts/release/perform.sh 1.0.0
sleep 1800  # Wait 30 minutes for Central sync
./scripts/release/validate.sh 1.0.0 --full
git checkout main && git merge release/1.0.0 && git push origin main
git push origin v1.0.0
gh release create v1.0.0 --notes "Release 1.0.0"
```

### Beta Release

```bash
./scripts/release/prepare.sh 1.0.0-beta --beta
./scripts/release/perform.sh 1.0.0-beta
./scripts/release/validate.sh 1.0.0-beta --full
```

### Emergency Hotfix

```bash
./scripts/release/prepare.sh 1.0.1 --force
./scripts/release/perform.sh 1.0.1
./scripts/release/validate.sh 1.0.1 --full
```

### Test Release (Dry Run)

```bash
./scripts/release/prepare.sh 1.0.0 --dry-run
./scripts/release/perform.sh 1.0.0 --dry-run
./scripts/release/validate.sh 1.0.0 --local
```

## Troubleshooting

### GPG Issues

```bash
# List GPG keys
gpg --list-secret-keys --keyid-format LONG

# Test signing
echo "test" | gpg --clearsign

# Set passphrase
export GPG_PASSPHRASE="your_passphrase"
```

### Maven Central Issues

```bash
# Check token
echo $OSSRH_TOKEN

# Test deployment
curl -u "${OSSRH_TOKEN}:${OSSRH_TOKEN}" \
  https://central.sonatype.com/api/v1/publisher/status
```

### Validation Failures

```bash
# Check local artifacts
ls -lh target/jotp-*.jar

# Rebuild if needed
./scripts/release/perform.sh 1.0.0 --local-only

# Check Maven Central sync
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/1.0.0/
```

## Documentation

- **Quick Start**: This file
- **Detailed Guide**: `scripts/release/README.md`
- **Main Release Guide**: `RELEASE.md` (project root)
- **Release Notes**: `RELEASE_NOTES.md`

## Support

- Issues: https://github.com/seanchatmangpt/jotp/issues
- Discussions: https://github.com/seanchatmangpt/jotp/discussions
- Maven Central: https://central.sonatype.com

## Best Practices

1. **Always test with --dry-run first**
2. **Run full validation after deployment**
3. **Keep GPG keys secure and backed up**
4. **Monitor Maven Central sync (10-30 minutes)**
5. **Test artifact download before announcing**
6. **Keep CHANGELOG.md updated**
7. **Use semantic versioning**
8. **Tag releases in git**
9. **Create GitHub releases**
10. **Have rollback plan ready**

## Versioning

```
MAJOR.MINOR.PATCH[-PRERELEASE]

Examples:
1.0.0         # First stable
1.0.1         # Bug fix
1.1.0         # New features
2.0.0         # Breaking changes
1.0.0-beta    # Prerelease
```

## License

Apache License 2.0 - See LICENSE for details.
