# JOTP CI/CD Pipeline Guide

This guide provides comprehensive documentation for JOTP's CI/CD infrastructure, including workflow usage, quality gates, security practices, and deployment procedures.

## Table of Contents

1. [Overview](#overview)
2. [Pipeline Architecture](#pipeline-architecture)
3. [Quality Gates](#quality-gates)
4. [Workflow Usage](#workflow-usage)
5. [Security Best Practices](#security-best-practices)
6. [Release Process](#release-process)
7. [Troubleshooting](#troubleshooting)

---

## Overview

JOTP's CI/CD infrastructure is built on GitHub Actions and provides:

- **Multi-JDK validation** (Java 21, 22, 23, 26)
- **Automated testing** (unit, integration, distributed)
- **Performance benchmarking** with regression detection
- **Security scanning** (Snyk, Trivy, OWASP)
- **Docker multi-arch builds** (amd64, arm64)
- **Maven Central publishing** with GPG signing
- **Helm chart publishing** to GitHub Pages
- **Infrastructure validation** (Terraform, Kubernetes)

### Pipeline Philosophy

1. **Fast Feedback**: Quick validation on every commit
2. **Comprehensive Testing**: Multiple test suites catch issues early
3. **Security First**: Automated security scanning at every stage
4. **Production Ready**: All artifacts are validated before release

---

## Pipeline Architecture

### Workflow Structure

```
┌─────────────────────────────────────────────────────────────┐
│                     Build CI (build.yml)                     │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Multi-JDK    │  │ JDK 26       │  │ Docker       │      │
│  │ Tests        │→ │ Preview      │→ │ Build        │      │
│  │ (21/22/23)   │  │ Full Build   │  │ Validation   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                ↓             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Benchmark    │  │ Coverage     │  │ Security     │      │
│  │ Regression   │→ │ Report       │→ │ Scan         │      │
│  │ Detection    │  │ (Jacoco)     │  │ (Trivy/Snyk) │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                          ↓                                    │
│                   ┌──────────────┐                           │
│                   │ Build        │                           │
│                   │ Summary      │                           │
│                   └──────────────┘                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                 Release Pipeline (release.yml)                │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Version      │  │ Maven        │  │ Docker       │      │
│  │ Validation   │→ │ Central      │→ │ Release      │      │
│  │ (Semver)     │  │ Publish      │  │ (Multi-arch) │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                          ↓                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Helm Chart   │  │ GitHub       │  │ Docs         │      │
│  │ Publish      │→ │ Release      │→ │ Deploy       │      │
│  │ (GH Pages)   │  │ Creation     │  │ (Next.js)    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│            Distributed Testing (distributed-test.yml)         │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Kind         │  │ Multi-Node   │  │ Chaos        │      │
│  │ Cluster      │→ │ Deployment   │→ │ Engineering  │      │
│  │ Creation     │  │ (3 nodes)    │  │ Tests        │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                          ↓                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Performance  │  │ Network      │  │ Cleanup      │      │
│  │ Regression   │→ │ Partition    │→ │ Resources    │      │
│  │ Tests        │  │ Tests        │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│           Infra Validation (infra-validation.yml)             │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Terraform    │  │ Helm Chart   │  │ Kubernetes   │      │
│  │ Validate     │→ │ Lint         │→ │ Manifest     │      │
│  │ (tfsec)      │  │ (kubeconform)│  │ Validate     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                          ↓                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Dockerfile   │  │ Security     │  │ IaC          │      │
│  │ Validate     │→ │ Scan         │→ │ Testing      │      │
│  │ (Hadolint)   │  │ (Snyk/Grype) │  │ (Kind)       │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

---

## Quality Gates

### Build Pipeline Gates

All pull requests must pass these gates before merging:

#### 1. **Multi-JDK Compatibility**
```bash
# Tests must pass on JDK 21, 22, 23, and 26
./mvnw test -Djava.version=21
./mvnw test -Djava.version=22
./mvnw test -Djava.version=23
./mvnw test -Djava.version=26
```

**Failure Conditions:**
- Any test fails on any JDK version
- Preview features break backward compatibility

#### 2. **Test Coverage**
```bash
# Jacoco enforces 80% minimum coverage
./mvnw jacoco:check
```

**Thresholds:**
- Line Coverage: **≥80%**
- Branch Coverage: **≥75%**
- Package Coverage: **≥80%**

#### 3. **Performance Benchmarks**
```bash
# JMH benchmarks must stay within variance
./mvnw verify -Pbenchmark
```

**Variance Limits:**
- Throughput: **≤5% variance** from baseline
- Latency: **≤10% variance** from baseline
- Memory: **≤15% variance** from baseline

#### 4. **Security Scanning**
```bash
# Trivy, Snyk, OWASP Dependency Check
# No critical vulnerabilities allowed
# High vulnerabilities require security team approval
```

**Severity Levels:**
- **CRITICAL**: Must fix (blocks merge)
- **HIGH**: Must fix or document exception
- **MEDIUM**: Should fix within 30 days
- **LOW**: Track for next release

#### 5. **Code Quality**
```bash
# Spotless formatting, guard validation
./mvnw spotless:check
./dx.sh validate
```

**Checks:**
- No H_TODO guards in production code
- No H_MOCK guards in production code
- No H_STUB guards in production code
- Code follows Google Java Format (AOSP)

### Release Pipeline Gates

Releases must pass additional gates:

#### 6. **Semantic Versioning**
```bash
# Version must match X.Y.Z format
# Examples: 1.0.0, 2.1.3-beta, 3.0.0-rc.1
```

**Validation:**
- Major.Minor.Patch format required
- Pre-release suffixes allowed (alpha, beta, rc)
- Build metadata allowed (+build.123)

#### 7. **GPG Signing**
```bash
# All artifacts must be signed
# GPG key must be trusted by Maven Central
```

**Requirements:**
- GPG key ID configured in secrets
- Public key published to keyserver
- Signature verification passes

#### 8. **Docker Multi-Architecture**
```bash
# Images built for amd64 and arm64
# All images pass security scan
```

**Platforms:**
- linux/amd64
- linux/arm64

---

## Workflow Usage

### Build CI Workflow

**Triggers:**
- Push to `main`, `develop`, `feature/**`, `release/**`
- Pull requests to `main` or `develop`
- Manual workflow dispatch

**Jobs:**

1. **Multi-JDK Test**: Tests on JDK 21, 22, 23
2. **JDK 26 Preview**: Full validation with preview features
3. **Docker Build**: Image build and smoke tests
4. **Benchmark Validation**: Performance regression detection
5. **Coverage**: JaCoCo report (≥80% required)
6. **Security Scan**: Trivy, OWASP Dependency Check
7. **Build Summary**: Final status check

**Local Testing:**
```bash
# Simulate CI build locally
make verify

# Run with specific JDK
JAVA_HOME=$(/usr/libexec/java_home -v 26) ./mvnw verify

# Run benchmarks
make benchmark-quick
```

### Release Pipeline Workflow

**Triggers:**
- Git tag push: `v*.*.*`
- Manual dispatch with version input

**Jobs:**

1. **Version Validate**: Semantic version validation
2. **Release Build**: Full build with Maven Central publish
3. **Docker Release**: Multi-arch image push
4. **Helm Release**: Chart publish to GitHub Pages
5. **Maven Central**: Publish signed artifacts
6. **GitHub Release**: Create release with artifacts
7. **Docs Deploy**: Deploy documentation to GitHub Pages

**Release Process:**

1. **Prepare Release:**
```bash
# Update version in pom.xml
./mvnw versions:set -DnewVersion=1.0.0

# Commit changes
git commit -am "chore: bump version to 1.0.0"

# Create and push tag
git tag v1.0.0
git push origin v1.0.0
```

2. **Monitor Pipeline:**
```bash
# Watch GitHub Actions for progress
# Pipeline runs automatically on tag push
```

3. **Verify Release:**
```bash
# Check Maven Central (may take 10-30 min)
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/1.0.0/

# Pull Docker image
docker pull jotpframework/jotp:1.0.0

# Install Helm chart
helm install jotp oci://ghcr.io/seanchatmangpt/jotp/charts/jotp --version 1.0.0
```

### Distributed Testing Workflow

**Triggers:**
- Push to `main` or `develop`
- Pull requests
- Weekly schedule (Sundays 2 AM)
- Manual dispatch

**Jobs:**

1. **Create Cluster**: Kind cluster with 3 nodes
2. **Deploy Distributed**: Multi-node JOTP deployment
3. **Chaos Tests**: Pod kill, network latency, CPU/memory stress
4. **Perf Regression**: Distributed benchmarks
5. **Network Partition**: Split-brain resolution tests
6. **Cleanup**: Resource cleanup

**Chaos Scenarios:**

```bash
# Pod kill - random pod termination
kubectl apply -f k8s/chaos/pod-kill.yaml

# Network latency - inject 500ms delay
kubectl apply -f k8s/chaos/network-latency.yaml

# CPU stress - 80% CPU load
kubectl apply -f k8s/chaos/cpu-stress.yaml

# Memory stress - 80% memory pressure
kubectl apply -f k8s/chaos/memory-stress.yaml

# Network partition - isolate worker nodes
kubectl apply -f k8s/chaos/network-partition.yaml
```

### Infrastructure Validation Workflow

**Triggers:**
- Changes to `infra/**`, `helm/**`, `k8s/**`, `docker/**`, `terraform/**`
- Pull requests to `main` or `develop`
- Manual dispatch

**Jobs:**

1. **Terraform Validate**: Format, validate, tfsec, Checkov
2. **Helm Validate**: Lint, template, unit tests
3. **K8s Validate**: Kubeconform, kubeval, deprecated API check
4. **Docker Validate**: Hadolint, Trivy, Docker Bench
5. **Security Scan**: Snyk, Grype, Trufflehog (secrets)
6. **CI/CD Validate**: Workflow syntax, CODEOWNERS, Makefile
7. **IaC Test**: Deploy to Kind cluster

**Local Validation:**

```bash
# Terraform validation
cd terraform/oci-network
terraform fmt -check
terraform init -backend=false
terraform validate
tflint --recursive

# Helm validation
helm lint helm/jotp
helm template jotp helm/jotp --debug > rendered.yaml
kubeconform -summary rendered.yaml

# K8s validation
kubeval k8s/base/*.yaml
kubectl-convert -f k8s/base/ --output-version=v1

# Docker validation
hadolint docker/Dockerfile
docker build -t jotp:test -f docker/Dockerfile .
trivy image --severity HIGH,CRITICAL jotp:test
```

---

## Security Best Practices

### 1. Secret Management

**Never commit secrets:**
```bash
# ❌ BAD - hardcoded secrets
DATABASE_PASSWORD=mysecretpassword

# ✅ GOOD - use environment variables
DATABASE_PASSWORD=${DATABASE_PASSWORD}
```

**GitHub Secrets:**
```bash
# Required secrets for CI/CD
GPG_PRIVATE_KEY          # GPG private key for signing
GPG_KEY_ID               # GPG key ID
GPG_PASSPHRASE           # GPG passphrase
MAVEN_CENTRAL_TOKEN      # Maven Central authentication token
DOCKER_USERNAME          # Docker Hub username
DOCKER_PASSWORD          # Docker Hub password
SNYK_TOKEN               # Snyk security scanner token
```

### 2. Dependency Scanning

**Automated Scanning:**
```bash
# OWASP Dependency Check
./mvnw org.owasp:dependency-check-maven:check

# Snyk scan
snyk test --severity-threshold=high

# Trivy image scan
trivy image jotp:latest --severity HIGH,CRITICAL
```

**Vulnerability Response:**
1. **CRITICAL**: Fix within 24 hours
2. **HIGH**: Fix within 7 days
3. **MEDIUM**: Fix within 30 days
4. **LOW**: Track for next release

### 3. Container Security

**Docker Best Practices:**
```dockerfile
# Use non-root user
USER 1001:1001

# Minimize layers
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Scan for vulnerabilities
trivy image --severity HIGH,CRITICAL jotp:latest
```

**Runtime Security:**
```yaml
# Security context
securityContext:
  runAsNonRoot: true
  runAsUser: 1001
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]
```

### 4. Supply Chain Security

**SBOM Generation:**
```bash
# Generate SBOM for Docker image
docker buildx build --sbom=true --provenance=true .

# Generate SBOM for Maven artifacts
./mvnw cyclonedx:makeAggregateBom
```

**Sigstore Signing:**
```bash
# Sign Docker images with Cosign
cosign sign jotpframework/jotp:1.0.0

# Verify image signature
cosign verify jotpframework/jotp:1.0.0
```

### 5. Code Security

**Guard System:**
```bash
# Run guard validation
./dx.sh validate

# Check for forbidden patterns
# - H_TODO in production code
# - H_MOCK in production code
# - H_STUB in production code
```

**Secret Detection:**
```bash
# Trufflehog secret scanning
trufflehog filesystem ./

# Gitleaks
gitleaks detect --source . --verbose
```

---

## Release Process

### Semantic Versioning

JOTP follows [Semantic Versioning 2.0.0](https://semver.org/):

- **MAJOR**: Incompatible API changes
- **MINOR**: Backward-compatible functionality
- **PATCH**: Backward-compatible bug fixes

**Version Format:**
```
X.Y.Z-PRERELEASE+BUILD

Examples:
1.0.0         # Final release
2.1.3-beta    # Beta pre-release
3.0.0-rc.1    # Release candidate 1
4.5.6+build.123  # Build metadata
```

### Release Checklist

**Pre-Release:**
- [ ] All tests passing on main branch
- [ ] Coverage ≥80%
- [ ] No critical security vulnerabilities
- [ ] Benchmarks within variance thresholds
- [ ] Documentation updated
- [ ] CHANGELOG.md updated
- [ ] Version bumped in pom.xml

**Release:**
- [ ] Git tag created (vX.Y.Z)
- [ ] CI/CD pipeline triggered
- [ ] Maven Central publish succeeded
- [ ] Docker image pushed
- [ ] Helm chart published
- [ ] GitHub release created
- [ ] Documentation deployed

**Post-Release:**
- [ ] Verify Maven Central indexing (10-30 min)
- [ ] Verify Docker image availability
- [ ] Verify Helm chart installation
- [ ] Announce release (GitHub, Twitter, etc.)
- [ ] Update website/documentation

### Release Commands

**Bump Version:**
```bash
# Set new version
./mvnw versions:set -DnewVersion=1.0.0

# Commit changes
git commit -am "chore: bump version to 1.0.0"

# Create tag
git tag -a v1.0.0 -m "Release v1.0.0"

# Push tag (triggers CI/CD)
git push origin v1.0.0
```

**Rollback Release:**
```bash
# Delete GitHub release
gh release delete v1.0.0 -y

# Delete tag
git push origin :refs/tags/v1.0.0
git tag -d v1.0.0

# Note: Maven Central releases cannot be deleted
# Contact Sonatype for support if needed
```

---

## Troubleshooting

### Common Issues

#### 1. **Multi-JDK Tests Failing**

**Problem:** Tests pass on JDK 26 but fail on JDK 21/22/23

**Solutions:**
```bash
# Check for preview feature usage
grep -r "preview" src/main/java/

# Verify backward compatibility
./mvnw test -Djava.version=21

# Fix: Use reflection for preview features
if (Runtime.version().feature() >= 26) {
    // Use preview feature
} else {
    // Fallback implementation
}
```

#### 2. **Coverage Below 80%**

**Problem:** Jacoco reports coverage below 80%

**Solutions:**
```bash
# Generate coverage report
./mvnw jacoco:report

# View report
open target/site/jacoco/index.html

# Add tests for uncovered lines
# Focus on high-impact code paths
```

#### 3. **Benchmark Regression**

**Problem:** Benchmarks show >5% variance

**Solutions:**
```bash
# Re-run benchmarks multiple times
make benchmark-full

# Compare with baseline
python scripts/analyze_variance.py baseline.json current.json

# Common causes:
# - JIT warmup issues
# - GC pressure
# - System load
# - External dependencies
```

#### 4. **Docker Build Failing**

**Problem:** Docker build fails in CI but works locally

**Solutions:**
```bash
# Check Dockerfile syntax
hadolint docker/Dockerfile

# Verify base image
docker pull eclipse-temurin:26-jdk

# Build with --no-cache
docker build --no-cache -f docker/Dockerfile .

# Check for multi-arch issues
docker buildx build --platform linux/amd64,linux/arm64 .
```

#### 5. **Maven Central Publish Failing**

**Problem:** Artifacts fail to publish to Maven Central

**Solutions:**
```bash
# Verify GPG key
gpg --list-secret-keys

# Test GPG signing
echo "test" | gpg --default-key $GPG_KEY_ID --clearsign

# Check Maven settings
cat ~/.m2/settings.xml

# Verify OSSRH credentials
# https://s01.oss.sonatype.org/
```

#### 6. **Kind Cluster Issues**

**Problem:** Distributed tests fail with Kind cluster errors

**Solutions:**
```bash
# Delete stuck cluster
kind delete cluster --name jotp-distributed-test

# Check Kind version
kind version

# Use specific Kubernetes version
kind create cluster --image kindest/node:v1.29.0

# Check cluster logs
kubectl logs -n kube-system -l k8s-app=kube-dns
```

### Debug Mode

**Enable verbose logging:**
```bash
# Maven verbose
./mvnw verify -X

# Docker build verbose
docker build --progress=plain --debug .

# Kubernetes verbose
kubectl get pods -v=9
kubectl describe pod <pod-name>
```

**CI/CD Debug:**
```bash
# Re-run failed workflow with debug logging
gh run rerun <run-id> --debug

# Download workflow logs
gh run download <run-id>

# View logs locally
cat workflow-logs/*.log | less
```

### Getting Help

**Resources:**
- **GitHub Issues**: https://github.com/seanchatmangpt/jotp/issues
- **Documentation**: https://seanchatmangpt.github.io/jotp/
- **Slack**: #jotp-dev channel
- **Email**: seanchatmangpt@users.noreply.github.com

**Debug Checklist:**
1. Check workflow logs in GitHub Actions
2. Review recent changes for breaking modifications
3. Verify environment variables and secrets
4. Test locally with same commands as CI/CD
5. Check for external dependencies (Maven Central, Docker Hub)
6. Review system logs for runtime errors

---

## Appendix

### CI/CD Metrics

**Current Performance:**
- Build time: ~8 minutes
- Test execution: ~5 minutes
- Docker build: ~3 minutes
- Security scan: ~2 minutes
- Total pipeline: ~18 minutes

**SLAs:**
- Pipeline success rate: >95%
- Mean time to recovery (MTTR): <30 minutes
- Release frequency: Weekly

### Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2025-01-15 | Initial CI/CD pipelines |
| 1.1.0 | 2025-02-20 | Added distributed testing |
| 1.2.0 | 2025-03-10 | Added security scanning |

### Related Documentation

- [ARCHITECTURE.md](../ARCHITECTURE.md) - System architecture overview
- [SLA-PATTERNS.md](../SLA-PATTERNS.md) - SRE runbooks
- [CONTRIBUTING.md](../CONTRIBUTING.md) - Contribution guidelines
- [Makefile](../../Makefile) - Build commands reference

---

**Last Updated:** 2025-03-16
**Maintainer:** @seanchatmangpt
