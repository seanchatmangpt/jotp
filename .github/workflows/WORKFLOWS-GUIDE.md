# JOTP GitHub Actions CI/CD Workflows

This document describes the comprehensive CI/CD workflows for the JOTP project, providing enterprise-grade automation for building, testing, quality checks, and releasing to Maven Central.

## Overview

The JOTP CI/CD pipeline consists of three main workflows:

| Workflow | Purpose | Triggers |
|----------|---------|----------|
| **ci.yml** | Main CI pipeline with build, test, and security scanning | Push to main/develop, PRs, manual |
| **quality.yml** | Code quality checks (Spotless, Checkstyle, PMD, SpotBugs) | Push, PRs, weekly schedule, manual |
| **release.yml** | Deploy to Maven Central and create GitHub releases | Version tags (v*), manual |

## Prerequisites

### Required Secrets

Configure these secrets in your GitHub repository settings (Settings → Secrets and variables → Actions):

#### Maven Central Publishing
```
CENTRAL_USERNAME - Your Sonatype Central Portal username
CENTRAL_TOKEN - Your Sonatype Central Portal access token
```

#### GPG Signing
```
GPG_PRIVATE_KEY - Your GPG private key (including BEGIN/END markers)
GPG_PASSPHRASE - Your GPG key passphrase
GPG_KEY_ID - Your GPG key ID (e.g., ABCD1234)
```

### Required GitHub Token Permissions

The workflows require the following token permissions:
- `contents: read` - Read repository contents
- `contents: write` - Create GitHub releases (release.yml)
- `id-token: write` - OIDC authentication for Maven Central (release.yml)
- `security-events: write` - Upload security scan results (ci.yml)
- `pull-requests: write` - Comment on PRs (ci.yml, quality.yml)

## Workflow Details

### 1. CI Pipeline (ci.yml)

The main CI workflow runs on every push and pull request to ensure code quality and stability.

#### Jobs

##### build
- Builds the project on Java 21, 22, and 26
- Runs unit tests
- Uploads test results on failure

##### code-quality
- Checks code formatting with Spotless
- Validates license headers
- Runs static analysis (Checkstyle)

##### test-coverage
- Runs tests with JaCoCo coverage
- Generates coverage reports
- Comments coverage on PRs

##### security-scan
- Runs Trivy vulnerability scanner
- Uploads results to GitHub Security
- Performs OWASP dependency check

##### integration-tests
- Runs integration tests (Failsafe)
- Uploads test results on failure

##### package
- Packages artifacts (JAR, sources, Javadoc)
- Uploads build artifacts

##### summary
- Generates CI summary report
- Checks overall CI status

#### Usage

```bash
# Automatic triggers
git push origin main
git push origin develop

# Manual trigger via GitHub UI
# Go to Actions → CI Pipeline → Run workflow
```

### 2. Code Quality (quality.yml)

Focused on code quality and maintainability with comprehensive analysis tools.

#### Jobs

##### spotless-check
- Enforces Google Java Format (AOSP style)
- Auto-formats code if check fails
- Uploads formatted code artifact

##### checkstyle-check
- Runs Checkstyle analysis
- Uploads Checkstyle XML reports
- Annotates PR with issues

##### pmd-analysis
- Runs PMD static analysis
- Detects code smells and potential bugs
- Uploads PMD reports

##### spotbugs-scan
- Runs SpotBugs bug detector
- Finds common Java bugs
- Uploads SpotBugs reports

##### dependency-analysis
- Analyzes dependencies for unused declarations
- Checks for dependency updates
- Generates dependency tree

##### license-check
- Validates Apache 2.0 license headers
- Downloads third-party licenses
- Aggregates license information

##### coverage-check
- Enforces coverage thresholds (80%)
- Generates JaCoCo reports
- Comments coverage on PRs

#### Usage

```bash
# Automatic triggers
git push origin main
git push origin develop

# Weekly schedule (Mondays at 9 AM UTC)

# Manual trigger via GitHub UI
# Go to Actions → Code Quality → Run workflow
```

### 3. Release Pipeline (release.yml)

Automates the release process to Maven Central and GitHub releases.

#### Jobs

##### validate-release
- Extracts version from tag or input
- Validates pom.xml structure
- Checks required files (LICENSE, README.md)
- Validates GPG and Maven Central credentials

##### build-artifacts
- Cleans and builds project
- Runs all tests
- Generates Javadoc and source JARs

##### sign-artifacts
- Imports GPG key from secrets
- Signs all artifacts with GPG
- Uploads signed artifacts

##### deploy-to-central
- Downloads signed artifacts
- Deploys to Maven Central Central Portal
- Supports dry-run mode

##### create-github-release
- Generates release notes from commit history
- Creates GitHub release with artifacts
- Links to Maven Central and Javadoc

##### notify-release
- Sends success/failure notifications
- Provides deployment links

#### Usage

##### Automatic Release (Tags)
```bash
# Create and push version tag
git tag v1.0.0
git push origin v1.0.0
```

##### Manual Release
```bash
# Via GitHub UI
# 1. Go to Actions → Release to Maven Central
# 2. Enter version (e.g., 1.0.0)
# 3. Optionally enable dry-run mode
# 4. Click "Run workflow"
```

##### Dry Run Mode
```bash
# Test the release pipeline without actual deployment
# Via GitHub UI: Enable "Perform dry run without actual deployment"
```

## Local Testing with Act

Test workflows locally using [Act](https://github.com/nektos/act):

```bash
# Install Act
brew install act  # macOS
# or
curl https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash

# Run CI workflow
act -j build -W .github/workflows/ci.yml

# Run release workflow (dry-run)
act -j validate-release -W .github/workflows/release.yml

# List all jobs
act -l -W .github/workflows/ci.yml
```

## Configuration

### Java Version

All workflows use Java 26 with the Oracle distribution:

```yaml
env:
  JAVA_VERSION: '26'
  JAVA_DISTRIBUTION: 'oracle'
```

### Maven Configuration

Maven options are configured for optimal performance:

```yaml
env:
  MAVEN_OPTS: >-
    -Xmx2g
    -Xms1g
    -Dmaven.repo.local=${{ github.workspace }}/.m2/repository
    -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
```

### Caching

Maven dependencies are cached using GitHub Actions cache:

```yaml
- name: Set up JDK
  uses: actions/setup-java@v4
  with:
    cache: 'maven'
```

## Monitoring and Troubleshooting

### Check Workflow Status

```bash
# Via GitHub CLI
gh run list --workflow=ci.yml
gh run list --workflow=release.yml

# View specific run
gh run view <run-id>

# View logs
gh run view <run-id> --log
```

### Common Issues

#### Build Failures
- Check Java version compatibility
- Verify all dependencies are available
- Review test logs for specific failures

#### GPG Signing Failures
- Verify GPG key is correctly imported
- Check GPG passphrase matches
- Ensure GPG key ID is correct

#### Maven Central Deployment Failures
- Verify Central Portal credentials
- Check pom.xml configuration
- Ensure GPG signatures are valid
- Verify version doesn't already exist

#### Coverage Check Failures
- Review coverage report in artifacts
- Add tests for uncovered code
- Adjust thresholds if necessary

### Artifacts and Reports

All workflows upload artifacts for inspection:

| Artifact | Retention | Contents |
|----------|-----------|----------|
| test-results-java* | 7 days | Surefire/Failsafe XML reports |
| coverage-report | 7 days | JaCoCo HTML/XML reports |
| checkstyle-report | 7 days | Checkstyle XML results |
| pmd-report | 7 days | PMD analysis reports |
| spotbugs-report | 7 days | SpotBugs analysis reports |
| owasp-dependency-check | 7 days | OWASP vulnerability report |
| dependency-tree | 7 days | Maven dependency tree |
| jotp-artifacts | 7 days | Build JARs |
| signed-artifacts | 7 days | Signed JARs and signatures |

## Best Practices

### Version Management
- Use semantic versioning (SemVer): MAJOR.MINOR.PATCH
- Pre-releases: 1.0.0-Alpha, 1.0.0-Beta
- Snapshots: 1.0.0-SNAPSHOT
- Releases: 1.0.0

### Commit Messages
Follow conventional commits format:
```
feat: Add new supervision tree strategy
fix: Resolve race condition in Proc.ask
docs: Update architecture documentation
test: Add integration tests for StateMachine
```

### Pull Request Workflow
1. Create feature branch from main
2. Make changes and commit
3. Push to GitHub
4. Create pull request
5. Wait for CI and quality checks to pass
6. Address any issues found
7. Request review
8. Merge after approval

### Release Workflow
1. Update version in pom.xml
2. Update CHANGELOG.md
3. Commit changes
4. Create version tag: `git tag v1.0.0`
5. Push tag: `git push origin v1.0.0`
6. Monitor release workflow
7. Verify on Maven Central
8. Announce release

## Security Considerations

### Secret Management
- Never commit secrets to repository
- Use GitHub Secrets for sensitive data
- Rotate GPG keys annually
- Use environment-specific tokens

### Dependency Security
- Regularly update dependencies
- Review OWASP reports
- Address critical vulnerabilities immediately
- Use Dependabot for automated updates

### Code Security
- Enable security scanning on all PRs
- Review Trivy scan results
- Address hardcoded secrets
- Implement secure coding practices

## Performance Optimization

### Parallel Execution
Jobs run in parallel where possible:
- Multiple Java versions tested simultaneously
- Quality checks run in parallel
- Security scans independent of other jobs

### Caching Strategy
- Maven dependencies cached between runs
- Build artifacts cached for deployment
- Local Maven repository optimized

### Build Time Optimization
```yaml
# Multi-threaded builds
./mvnw -T1C  # 1 thread per CPU core

# Skip tests during packaging
./mvnw package -DskipTests

# Offline mode (use cached dependencies)
./mvnw verify --offline
```

## Support and Resources

### Documentation
- [GitHub Actions Documentation](https://docs.github.com/actions)
- [Maven Central Publishing Guide](https://central.sonatype.com/publish)
- [GPG Signing Guide](https://central.sonatype.com/publish/requirements#gpg)
- [Act Documentation](https://nektosact.com/)

### Project-Specific
- [JOTP Architecture](../.claude/ARCHITECTURE.md)
- [JOTP README](../README.md)
- [Maven Publishing Guide](../docs/MAVEN-CENTRAL-PUBLISHING.md)

### Troubleshooting Help
For issues with the workflows:
1. Check workflow logs in GitHub Actions
2. Review this guide
3. Check Maven Central Portal status
4. Open an issue with full error logs

## License

Apache License 2.0 - See [LICENSE](../LICENSE) for details.
