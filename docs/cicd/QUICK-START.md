# JOTP CI/CD Quick Start Guide

## 🚀 Quick Setup

### 1. Required GitHub Secrets

Configure these in your repository settings (`Settings > Secrets and variables > Actions`):

```bash
# GPG Signing (required for Maven Central)
GPG_PRIVATE_KEY        # Base64 encoded GPG private key
GPG_KEY_ID             # GPG key ID (e.g., ABC12345)
GPG_PASSPHRASE         # GPG key passphrase

# Maven Central
MAVEN_CENTRAL_TOKEN    # OSSRH authentication token
MAVEN_CENTRAL_PASSWORD # OSSRH password

# Docker Hub
DOCKER_USERNAME        # Docker Hub username
DOCKER_PASSWORD        # Docker Hub access token

# Optional: Security Scanning
SNYK_TOKEN            # Snyk API token
DISCORD_WEBHOOK       # Discord webhook for notifications
```

### 2. GPG Key Setup

```bash
# Generate GPG key
gpg --full-generate-key
# Select: RSA and RSA (default)
# Key size: 4096
# Valid for: 0 (never expires)
# Real name: Your Name
# Email: your@email.com

# Export key
gpg --armor --export-secret-keys your@email.com > private.key
gpg --armor --export your@email.com > public.key

# Encode for GitHub
base64 -i private.key > private.key.base64

# Upload to GitHub Secrets as GPG_PRIVATE_KEY
cat private.key.base64

# Extract key ID for GPG_KEY_ID
gpg --list-secret-keys --keyid-format LONG
# Copy the key ID after "sec" (e.g., 4096R/ABC12345)

# Publish public key
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver pgp.mit.edu --send-keys YOUR_KEY_ID
```

### 3. Maven Central Setup

1. **Create OSSRH Account:**
   - Sign up at https://central.sonatype.org/
   - Create JIRA ticket for new project access
   - Wait for approval (usually 1-2 business days)

2. **Configure Maven:**
   - Add to `~/.m2/settings.xml`:
   ```xml
   <servers>
     <server>
       <id>ossrh</id>
       <username>${env.MAVEN_CENTRAL_TOKEN}</username>
       <password>${env.MAVEN_CENTRAL_PASSWORD}</password>
     </server>
   </servers>
   ```

3. **Test Deployment:**
   ```bash
   ./mvnw deploy -DskipTests
   ```

---

## 📋 Workflow Usage

### Build CI Pipeline

**Triggers:**
- Push to `main`, `develop`, `feature/**`, `release/**`
- Pull requests
- Manual: `Actions > Build CI > Run workflow`

**What it does:**
- Tests on JDK 21, 22, 23, 26
- Builds Docker image
- Runs JMH benchmarks
- Generates coverage report (≥80% required)
- Scans for vulnerabilities (Trivy, OWASP)

**Local testing:**
```bash
# Full validation
make verify

# Quick test
make test

# Benchmarks
make benchmark-quick
```

### Release Pipeline

**Triggers:**
- Git tag: `git tag v1.0.0 && git push origin v1.0.0`
- Manual: `Actions > Release Pipeline > Run workflow`

**What it does:**
- Validates semantic version
- Publishes to Maven Central
- Pushes Docker image (amd64, arm64)
- Publishes Helm chart
- Creates GitHub release
- Deploys documentation

**Release process:**
```bash
# 1. Update version
./mvnw versions:set -DnewVersion=1.0.0

# 2. Commit changes
git commit -am "chore: bump version to 1.0.0"

# 3. Create tag
git tag -a v1.0.0 -m "Release v1.0.0"

# 4. Push (triggers CI/CD)
git push origin main --tags
```

### Distributed Testing

**Triggers:**
- Push to `main`/`develop`
- Weekly schedule (Sundays 2 AM)
- Manual: `Actions > Distributed Testing > Run workflow`

**What it does:**
- Creates Kind cluster (3 nodes)
- Deploys JOTP cluster
- Runs chaos tests (pod kill, network latency, stress)
- Tests network partition recovery
- Runs distributed benchmarks

**Local testing:**
```bash
# Install Kind
curl -Lo kind https://kind.sigs.k8s.io/dl/v0.22.0/kind-linux-amd64
chmod +x kind && sudo mv kind /usr/local/bin/

# Create cluster
kind create cluster --config k8s/kind-config.yaml

# Deploy
kubectl apply -f k8s/distributed/

# Test
kubectl port-forward svc/jotp-cluster 8080:8080
```

### Infrastructure Validation

**Triggers:**
- Changes to `infra/**`, `helm/**`, `k8s/**`, `docker/**`, `terraform/**`
- Pull requests
- Manual: `Actions > Infrastructure Validation > Run workflow`

**What it does:**
- Validates Terraform (format, lint, security)
- Lints Helm charts
- Validates Kubernetes manifests
- Scans Dockerfiles
- Runs security scans (Snyk, Grype, Trufflehog)

**Local validation:**
```bash
# Terraform
cd terraform/oci-network
terraform fmt -check
terraform init -backend=false
terraform validate
tflint --recursive

# Helm
helm lint helm/jotp
helm template jotp helm/jotp --debug | kubeconform -summary

# K8s
kubeval k8s/base/*.yaml

# Docker
hadolint docker/Dockerfile
docker build -t jotp:test -f docker/Dockerfile .
trivy image jotp:test
```

---

## 🔧 Common Tasks

### Create a Release

```bash
# 1. Ensure all tests pass
make verify

# 2. Update CHANGELOG.md
vim CHANGELOG.md

# 3. Bump version
./mvnw versions:set -DnewVersion=1.0.0

# 4. Commit and tag
git commit -am "chore: bump version to 1.0.0"
git tag -a v1.0.0 -m "Release v1.0.0"

# 5. Push (triggers release pipeline)
git push origin main --tags

# 6. Monitor pipeline
gh run watch

# 7. Verify Maven Central (10-30 min later)
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/1.0.0/
```

### Trigger a Build

```bash
# Push to feature branch
git checkout -b feature/new-feature
git commit -am "feat: add new feature"
git push origin feature/new-feature

# Or manually via GitHub CLI
gh workflow run build.yml
```

### Debug a Failed Build

```bash
# Download logs
gh run view --log
gh run download <run-id>

# Re-run with debug logging
gh run rerun <run-id> --debug

# Local debug
./mvnw verify -X
docker build --progress=plain --debug .
```

### Update Dependencies

```bash
# Check for updates
./mvnw versions:display-dependency-updates

# Update specific dependency
./mvnw versions:use-latest-releases -Dincludes=org.junit:junit-bom

# Let Dependabot handle it
# Wait for Monday 9 AM automatic PR
```

---

## 📊 Monitoring

### Pipeline Status

```bash
# List recent runs
gh run list --workflow=build.yml --limit 10

# Watch specific run
gh run watch <run-id>

# View summary
gh run view <run-id>
```

### Status Badges

Add to README.md:

```markdown
[![Build CI](https://github.com/seanchatmangpt/jotp/actions/workflows/build.yml/badge.svg)](https://github.com/seanchatmangpt/jotp/actions/workflows/build.yml)
[![Release](https://github.com/seanchatmangpt/jotp/actions/workflows/release.yml/badge.svg)](https://github.com/seanchatmangpt/jotp/actions/workflows/release.yml)
[![Distributed Tests](https://github.com/seanchatmangpt/jotp/actions/workflows/distributed-test.yml/badge.svg)](https://github.com/seanchatmangpt/jotp/actions/workflows/distributed-test.yml)
[![Infra Validation](https://github.com/seanchatmangpt/jotp/actions/workflows/infra-validation.yml/badge.svg)](https://github.com/seanchatmangpt/jotp/actions/workflows/infra-validation.yml)
```

### Notifications

**Discord Setup:**
1. Create Discord webhook
2. Add to GitHub Secrets: `DISCORD_WEBHOOK`
3. Notifications sent for:
   - Release success/failure
   - Build failures
   - Security vulnerabilities

---

## 🐛 Troubleshooting

### Common Issues

**1. GPG Signing Failure**
```bash
# Verify GPG key
gpg --list-secret-keys --keyid-format LONG

# Test signing
echo "test" | gpg --default-key YOUR_KEY_ID --clearsign

# Resend public key
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

**2. Maven Central Timeout**
```bash
# Wait 10-30 minutes for indexing
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/

# Check Sonatype staging
https://s01.oss.sonatype.org/
```

**3. Docker Build Fails**
```bash
# Check Dockerfile syntax
hadolint docker/Dockerfile

# Build locally
docker build -f docker/Dockerfile --no-cache .

# Check multi-platform support
docker buildx build --platform linux/amd64,linux/arm64 .
```

**4. Kind Cluster Issues**
```bash
# Delete stuck cluster
kind delete cluster --name jotp-distributed-test

# Check Kind version
kind version

# Create with specific Kubernetes version
kind create cluster --image kindest/node:v1.29.0
```

**5. Coverage Below 80%**
```bash
# Generate report
./mvnw jacoco:report

# View report
open target/site/jacoco/index.html

# Find uncovered lines
grep -A 5 "Missed" target/site/jacoco/index.html
```

---

## 📚 Further Reading

- [Full Pipeline Guide](/Users/sac/jotp/docs/cicd/PIPELINE-GUIDE.md)
- [Delivery Summary](/Users/sac/jotp/docs/cicd/DELIVERY-SUMMARY.md)
- [Architecture Documentation](/Users/sac/jotp/docs/ARCHITECTURE.md)
- [SLA Patterns](/Users/sac/jotp/docs/SLA-PATTERNS.md)

---

## 🆘 Getting Help

- **GitHub Issues**: https://github.com/seanchatmangpt/jotp/issues
- **Documentation**: https://seanchatmangpt.github.io/jotp/
- **Email**: seanchatmangpt@users.noreply.github.com

---

**Last Updated:** 2025-03-16
**Maintainer:** @seanchatmangpt
