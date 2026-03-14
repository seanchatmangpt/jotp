# Universal Act Troubleshooting
# =============================
# Solutions for common issues with Act and Maven projects

## Quick Fixes

### 1. "Could not find any stages to run"

**Problem**: Can't find workflow jobs.

**Solution**:
```bash
# Check your workflow file path
ls -la .github/workflows/ci.yml

# List available workflows
act --list -W .github/workflows/ci.yml

# Use explicit event
act --event push -W .github/workflows/ci.yml
```

**Fix for Universal Template**:
1. Edit `.act.config.yaml`
2. Change `workflow: .github/workflows/ci.yml` to your actual workflow file
3. Run `./test-act.sh --help`

### 2. Docker Issues

**Problem**: Docker permission denied or not accessible.

**Solution**:
```bash
# Check Docker is running
docker info

# Add user to docker group (Linux)
sudo usermod -aG docker $USER
newgrp docker

# Use Act with host networking
act --network host -W .github/workflows/ci.yml
```

### 3. Apple Silicon Issues

**Problem**: M1/M2 chip compatibility.

**Solution**:
```bash
# For Apple Silicon Macs
act --container-architecture linux/amd64 -W .github/workflows/ci.yml

# Or add to .actrc
--container-architecture linux/amd64
```

### 4. Secrets Not Loading

**Problem**: Secrets aren't available.

**Solution**:
```bash
# Check secrets file
cat .secrets

# Use environment variables
export GITHUB_TOKEN=token
act -W .github/workflows/ci.yml -j build

# Use explicit secret file
act --secret-file .secrets -W .github/workflows/ci.yml
```

## Common Error Messages

### "No such file or directory" for workflow

**Fix**: Update the workflow path in `.act.config.yaml`
```yaml
# Wrong
workflow: .github/workflows/ci.yml

# Correct
workflow: .github/workflows/publish.yml
```

### "Docker command not found"

**Fix**: Install Docker and add to PATH
```bash
# Check Docker
which docker

# Install if needed
curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh
```

### "Permission denied" on Docker socket

**Fix**: Grant Docker permissions
```bash
# For Linux
sudo usermod -aG docker $USER
newgrp docker

# For Docker Desktop
# Just ensure Docker Desktop is running
```

### "Build failed" / "Tests failed"

**Fix**: Check logs and adjust configuration
```bash
# Check logs
cat logs/build.log
cat logs/test.log

# Run with debug
act --debug -W .github/workflows/ci.yml -j build
```

## Platform-Specific Issues

### Windows

```bash
# Use PowerShell
act --platform windows-latest=node:16-buster -W .github/workflows/ci.yml

# Or use WSL
wsl act --dryrun -W .github/workflows/ci.yml
```

### macOS

```bash
# Intel Mac
act -W .github/workflows/ci.yml

# Apple Silicon Mac
act --container-architecture linux/amd64 -W .github/workflows/ci.yml
```

### Linux

```bash
# Ubuntu/Debian
apt-get install -y docker.io docker-compose
systemctl start docker

# CentOS/RHEL
yum install -y docker docker-compose
systemctl start docker
```

## Performance Optimization

### Speed Up Act Runs

```bash
# Use Docker-in-Docker
act --use-docker-in-docker -W .github/workflows/ci.yml

# Use parallel jobs
act -W .github/workflows/ci.yml -j build,test

# Skip pull on subsequent runs
act --pull=policy -W .github/workflows/ci.yml

# Use local Maven cache
act -W .github/workflows/ci.yml --env MAVEN_OPTS="-Dmaven.repo.local=./.m2"
```

### Reduce Memory Usage

```bash
# Limit memory
export DOCKER_OPTS="--memory 2g"

# Use lightweight containers
act -P ubuntu-latest=docker.io/library/ubuntu:22.04 -W .github/workflows/ci.yml
```

## Maven-Specific Issues

### "Failed to execute goal"

**Fix**: Check Maven configuration
```bash
# Test locally first
mvn clean compile

# With Act
act -W .github/workflows/ci.yml -j build --env MAVEN_OPTS="-Xmx1g"
```

### "Test failures"

**Fix**: Adjust test configuration
```bash
# Skip tests in Act
act -W .github/workflows/ci.yml -j build --env SKIP_TESTS=true

# Run specific tests
act -W .github/workflows/ci.yml -j test --env TESTS="*Test"
```

### "Memory issues"

**Fix**: Increase memory allocation
```bash
# In .act.config.yaml
env:
  MAVEN_OPTS: "-Xmx4g -Xms2g -T1C"
```

## Debugging Steps

### 1. Check Act Installation
```bash
act --version
act --help
```

### 2. Check Docker
```bash
docker --version
docker info
docker run hello-world
```

### 3. Validate Configuration
```bash
# Check YAML syntax
python -c "import yaml; yaml.safe_load(open('.act.config.yaml'))"

# Test Act config
act --list -W .github/workflows/ci.yml
```

### 4. Run Debug Tests
```bash
# Dry-run first
act --dryrun -W .github/workflows/ci.yml

# Debug mode
act --debug -W .github/workflows/ci.yml -j build
```

## Advanced Troubleshooting

### Custom Container Issues
```yaml
# .act.config.yaml
platforms:
  custom:
    image: your-image:tag
    options: "--env VAR=value"
```

### Network Issues
```bash
# Use custom network
act --network custom-network -W .github/workflows/ci.yml

# Or host network
act --network host -W .github/workflows/ci.yml
```

### Volume Issues
```bash
# Custom volumes
act -W .github/workflows/ci.yml \
  --volume /path/to/cache:/cache \
  --volume /path/to/keys:/keys
```

## Getting Help

1. **Check logs**: Always check `logs/` directory
2. **Run debug**: `act --debug`
3. **Check Docker logs**: `docker logs $(docker ps -lq)`
4. **Act documentation**: https://nektosact.com/
5. **GitHub issues**: https://github.com/nektos/act/issues

## Quick Test Command

```bash
# Test if everything works
./test-act.sh --dry-run

# Test specific job
./test-act.sh -j build

# Full test
./test-act.sh
```

Remember: Act is for local testing. Always test locally before pushing changes!