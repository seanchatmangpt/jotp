# Act Troubleshooting Guide for JOTP
# =================================
# Quick solutions for common Act issues

## Common Issues and Solutions

### 1. "Could not find any stages to run"

**Problem**: Act can't find workflow stages.

**Solution**:
```bash
# Check workflow exists
ls -la .github/workflows/publish.yml

# List workflows explicitly
act --list -W .github/workflows/publish.yml

# Try with specific event
act --list -W .github/workflows/publish.yml --event push
```

### 2. Docker Issues

**Problem**: Docker not accessible or permission denied.

**Solution**:
```bash
# Check Docker is running
docker info

# Add user to docker group (Linux)
sudo usermod -aG docker $USER
newgrp docker

# Or use Act with host networking
act --network host -W .github/workflows/publish.yml
```

### 3. Apple Silicon Issues

**Problem**: M1/M2 chip compatibility issues.

**Solution**:
```bash
# Specify architecture
act --container-architecture linux/amd64 -W .github/workflows/publish.yml

# Or use ARM-specific image
act -P ubuntu-latest=catthehacker/ubuntu:act-latest -P ubuntu-latest=catthehacker/ubuntu:arm64
```

### 4. Secrets Not Loading

**Problem**: Secrets are not available in the workflow.

**Solution**:
```bash
# Check secrets file
cat .secrets

# Use environment variables instead
export GITHUB_TOKEN=token
act -W .github/workflows/publish.yml -j build

# Use secret file explicitly
act --secret-file .secrets -W .github/workflows/publish.yml
```

### 5. Build Failures

**Problem**: Maven builds fail in Act.

**Solution**:
```bash
# Check Java version
act -W .github/workflows/publish.yml -j build --env JAVA_VERSION=17

# Use local Maven repo
act -W .github/workflows/publish.yml -j build --env MAVEN_OPTS="-Dmaven.repo.local=./.m2"

# Increase timeout
act -W .github/workflows/publish.yml -j build --timeout=30m
```

### 6. Slow Performance

**Problem**: Act runs are slow.

**Solution**:
```bash
# Use Docker in Docker
act --use-docker-in-docker -W .github/workflows/publish.yml

# Skip pull on subsequent runs
act --pull=policy -W .github/workflows/publish.yml

# Use parallel jobs
act -W .github/workflows/publish.yml -j build,junit
```

## Quick Start Commands

### For M1/M2 Mac Users
```bash
# Use this command for Apple Silicon
act --container-architecture linux/amd64 -W .github/workflows/publish.yml

# Or use specific images
act -P ubuntu-latest=catthehacker/ubuntu:act-latest -P windows-latest=windows-latest:act-latest -W .github/workflows/publish.yml
```

### Basic Testing Commands
```bash
# 1. Check what's available
act --list -W .github/workflows/publish.yml

# 2. Dry run first
act --dryrun -W .github/workflows/publish.yml

# 3. Test build
act -W .github/workflows/publish.yml -j build

# 4. Test everything
act -W .github/workflows/publish.yml

# 5. With secrets
act --secret-file .secrets -W .github/workflows/publish.yml
```

### Performance Commands
```bash
# Fast dry run
act --dryrun --reuse workflows -W .github/workflows/publish.yml

# Parallel testing
act -W .github/workflows/publish.yml -j build,junit

# With local cache
act -W .github/workflows/publish.yml -j build --env MAVEN_OPTS="-Dmaven.repo.local=./.m2"
```

## Configuration Tips

### .actrc File
Create `.actrc` in your project root:
```
--default-event=push
--use-docker-in-docker
--parallel
--rm=false
--pull=never
```

### Platform Selection
For consistent testing:
```yaml
# .act.config.yaml
platforms:
  ubuntu-latest:
    image: catthehacker/ubuntu:act-latest
    options: "--privileged"
  windows-latest:
    image: node:16-buster
```

### Event Configuration
```yaml
# Test push event
act --event push -W .github/workflows/publish.yml

# Test pull request
act --event pull_request -W .github/workflows/publish.yml

# Test workflow dispatch
act --event workflow_dispatch -W .github/workflows/publish.yml
```

## Best Practices

### 1. Always Test Dry-Run First
```bash
act --dryrun -W .github/workflows/publish.yml
```

### 2. Test Individual Jobs
```bash
act -W .github/workflows/publish.yml -j build
act -W .github/workflows/publish.yml -j test
```

### 3. Use Appropriate Platforms
- Use `catthehacker/ubuntu:act-latest` for Ubuntu
- Use specific images for Windows/macOS

### 4. Handle Secrets Properly
- Use `.secrets` file (never commit)
- Test with dummy values first
- Use environment variables for CI

### 5. Monitor Performance
```bash
# Time your tests
time act -W .github/workflows/publish.yml -j build

# Check logs
cat logs/build.log | grep -E "(Time|Duration)"
```

## Getting Help

If you still have issues:

1. **Check Act documentation**: https://nektosact.com/
2. **Look for existing issues**: https://github.com/nektos/act/issues
3. **Run with debug mode**: `act --debug -W .github/workflows/publish.yml`
4. **Check Docker logs**: `docker logs $(docker ps -lq)`

Remember: Act is for local testing. Always test locally before pushing changes!