# Universal Act Template for Maven Projects
# =========================================
# A complete Act (Automated Continuous Testing) configuration template for any Maven project

## 🚀 Quick Start

1. **Copy the template to your project:**
   ```bash
   # For any Maven project
   cp act-universal-template/.act.config.yaml .
   cp act-universal-template/.actrc .
   cp act-universal-template/.env-template .env
   cp act-universal-template/.secrets-template .secrets
   cp act-universal-template/test-act.sh .
   chmod +x test-act.sh
   ```

2. **Set up your environment:**
   ```bash
   # Edit .env with your project settings
   vim .env
   ```

3. **Run Act testing:**
   ```bash
   source test-act.sh
   ```

## 📂 Template Structure

```
act-universal-template/
├── .act.config.yaml          # Main Act configuration
├── .actrc                   # Act command-line settings
├── .env-template            # Environment variables template
├── .secrets-template        # Secrets template
├── test-act.sh             # Comprehensive testing script
├── act-troubleshooting.md   # Troubleshooting guide
├── project-types/          # Project-specific configurations
│   ├── spring-boot/        # Spring Boot optimized config
│   ├── quarkus/           # Quarkus native image support
│   ├── micronaut/         # Micronaut specific settings
│   ├── multi-module/      # Multi-module project support
│   └── standard/          # Standard Maven projects
└── demo/                  # Demo scripts and examples
    ├── setup-demo.sh      # Create demo project
    ├── quick-test.sh      # Quick test script
    └── multi-project-demo.sh  # Multiple project types
```

## 🎯 Key Features

### ✅ Universal Compatibility
- Works with any Maven project (standard, Spring Boot, Quarkus, Micronaut, multi-module)
- Automatic project type detection
- Adaptive configuration based on project structure

### 🚀 Performance Optimized
- Parallel builds with `-T2C`
- Docker-in-Docker for container compatibility
- Cached Maven dependencies
- Performance monitoring and reporting

### 🔒 Security Best Practices
- Secret management template
- Secure credential handling
- GPG signing support for artifacts

### 🌍 Multi-Platform Support
- Linux (Ubuntu, CentOS)
- Windows (via containers or WSL)
- macOS (Intel & Apple Silicon)
- Container architecture options

### 📊 Comprehensive Testing
- Individual job testing
- Full workflow simulation
- Dry-run verification
- Performance benchmarking

## 📋 Usage Examples

### 1. Standard Maven Project
```bash
# Copy universal template
cp act-universal-template/.act.config.yaml .
cp act-universal-template/test-act.sh .

# Configure your project
cp .env-template .env
vim .env  # Update JAVA_VERSION, MAVEN_OPTS, etc.

# Run tests
source test-act.sh
```

### 2. Spring Boot Project
```bash
# Copy Spring Boot specific configuration
cp act-universal-template/project-types/spring-boot/.act.config.yaml .
cp act-universal-template/.actrc .

# Configure Spring Boot settings
vim .env  # Update SPRING_PROFILES_ACTIVE, etc.

# Run tests with Spring Boot optimization
source test-act.sh
```

### 3. Multi-Module Project
```bash
# Copy multi-module configuration
cp act-universal-template/project-types/multi-module/.act.config.yaml .

# Test specific modules
source test-act.sh -j core-build
source test-act.sh -j web-test
```

### 4. Quick Test (Any Project)
```bash
# Quick verification
source test-act.sh --dry-run
source test-act.sh --help  # See all options
```

## 🔧 Configuration

### Environment Variables (.env)
```bash
# Java and Maven
JAVA_VERSION=17
MAVEN_OPTS="-Xmx2g -Xms1g -T1C"

# Project specific
PROJECT_NAME=my-app
SKIP_TESTS=false
BUILD_TIMEOUT=30m

# CI/CD
CI_ENABLED=true
SKIP_DEPLOY=true
DRY_RUN=true
```

### Secrets (.secrets)
```bash
# Required for deployment
GITHUB_TOKEN=your-token
GPG_PRIVATE_KEY=-----BEGIN PGP...
GPG_PASSPHRASE=your-passphrase

# Optional
MAVEN_USERNAME=your-username
MAVEN_PASSWORD=your-password
```

### Project-Specific Configuration
The template includes optimized configurations for:
- **Spring Boot**: Profile management, actuator endpoints
- **Quarkus**: Native image support, fast startup
- **Micronaut**: AOT compilation, reflection-free
- **Multi-Module**: Parallel builds, module isolation
- **Standard**: Universal settings for any Maven project

## 🛠️ Advanced Usage

### Custom Workflow Files
```yaml
# .act.config.yaml
workflow: .github/workflows/your-workflow.yml
workflow_runs:
  - workflow: ".github/workflows/ci.yml"
    event_data:
      ref: refs/heads/your-branch
```

### Custom Platforms
```yaml
# Add your custom platform
platforms:
  your-platform:
    image: your-image:tag
    options: "--env VAR=value"
```

### Performance Tuning
```bash
# Increase parallelism
export BUILD_PARALLEL="-T4C"

# Limit memory
export MAVEN_OPTS="-Xmx4g -Xms2g"

# Use SSD cache
export MAVEN_OPTS="$MAVEN_OPTS -Dmaven.repo.local=/fast/path/.m2"
```

## 🐛 Troubleshooting

Common issues and solutions:

### "Could not find any stages to run"
- Check your workflow file path in `.act.config.yaml`
- Run `act --list -W .github/workflows/ci.yml`

### Docker Issues
- Ensure Docker is running: `docker info`
- Use host network: `act --network host`

### Secrets Not Loading
- Check `.secrets` file exists and is readable
- Verify variable names match your workflow

### Performance Issues
- Increase Docker memory: `export DOCKER_OPTS="--memory 4g"`
- Use Docker-in-Docker: `act --use-docker-in-docker`

See [act-troubleshooting.md](act-troubleshooting.md) for more detailed solutions.

## 📚 Documentation

- [Project Types](project-types/README.md) - Project-specific configurations
- [Troubleshooting Guide](act-troubleshooting.md) - Common issues and solutions
- [Demo Scripts](demo/) - Setup and testing examples

## 🔗 Related Resources

- [Act Documentation](https://nektosact.com/)
- [Maven Central Portal](https://central.sonatype.com/)
- [GitHub Actions Documentation](https://docs.github.com/actions)

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch
3. Add tests for your changes
4. Update documentation
5. Submit a pull request

## 📄 License

This template is provided as-is for testing and development purposes.

---

**Happy testing with Act! 🚀**