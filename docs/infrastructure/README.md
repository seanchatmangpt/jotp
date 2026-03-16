# JOTP Infrastructure Documentation

This directory contains documentation about JOTP's development infrastructure, code generation, and tooling.

## Overview

JOTP's infrastructure supports automated development workflows, code generation, and comprehensive testing utilities.

## Infrastructure Components

### Code Generation
- **[code-generation.md](./code-generation.md)**: Automated code generation strategies
  - Boilerplate reduction
  - Pattern-based generation
  - Consistency enforcement

### Diagram Generation
- **[diagrams/](./diagrams/)**: Comprehensive C4 architecture diagrams
  - System context diagrams
  - Container architecture
  - Component-level details
  - Code structure visualization
  - Message flow diagrams
  - Error handling flows

#### Key Diagram Files:
- **[MANIFEST.md](./diagrams/MANIFEST.md)**: Complete diagram catalog
- **[GENERATION_SUMMARY.md](./diagrams/GENERATION_SUMMARY.md)**: Generation methodology
- **[DIAGRAMS-MANIFEST.md](./diagrams/DIAGRAMS-MANIFEST.md)**: Diagram organization

#### Diagram Categories:
- **Level 1**: System context (c4-jotp-01-system-context.puml)
- **Level 2**: Container architecture (c4-jotp-02-containers.puml)
- **Level 3**: Component breakdown (c4-jotp-03-*.puml)
- **Level 4**: Class-level details (c4-jotp-04-*.puml)
- **Dynamic**: Message flows, state transitions, error recovery

### Testing Infrastructure
- **[PHASE7_TESTING_UTILITIES_SUMMARY.md](./PHASE7_TESTING_UTILITIES_SUMMARY.md)**: Testing utilities summary
  - Test framework overview
  - Utility functions catalog
  - Best practices

## Development Tooling

### Build System
- **Maven 4**: Modern build system with multi-module support
- **Maven Daemon (mvnd)**: Persistent JVM for 30% faster builds
- **Spotless**: Automated code formatting (Google Java Format)
- **JPMS**: Java Platform Module System support

### Code Quality
- **Checkstyle**: Static analysis
- **PMD**: Code quality checks
- **SpotBugs**: Bug detection
- **JUnit 5**: Modern testing framework
- **AssertJ**: Fluent assertion library

### Documentation Generation
- **PlantUML**: Diagram generation from text
- **Javadoc**: API documentation
- **MDBook**: Book format documentation
- **Hugo**: Static site generator

## CI/CD Pipeline

### Continuous Integration
- **GitHub Actions**: Automated testing on every commit
- **Multi-JDK Testing**: Java 21, 22, 23-ea, 26-ea
- **Matrix Builds**: Test across multiple configurations
- **Coverage Reporting**: JaCoCo integration

### Continuous Deployment
- **Maven Central**: Automated publishing
- **GitHub Releases**: Release automation
- **Docker Images**: Container builds
- **Documentation Sites**: Auto-deployment

## Development Workflow

### Setting Up Infrastructure
```bash
# Clone repository
git clone https://github.com/seanchatmangpt/jotp.git

# Install dependencies (auto-downloads mvnd)
./bin/mvndw install

# Run full build
./mvnw verify
```

### Code Generation Workflow
```bash
# Generate diagrams from PlantUML
cd docs/diagrams
plantuml -tpuml *.puml

# Generate code from patterns (if applicable)
mvnd generate-sources
```

### Testing Infrastructure
```bash
# Unit tests
mvnd test

# Integration tests
mvnd verify

# Dogfood validation
mvnd verify -Ddogfood

# Performance tests
mvnd test -Dtest=stress.*
```

## Maintenance

### Updating Infrastructure
1. **Dependencies**: Regularly update Maven dependencies
2. **Plugins**: Keep Maven plugins current
3. **Templates**: Maintain code generation templates
4. **Diagrams**: Keep architecture diagrams synchronized

### Monitoring
- **Build Times**: Track build performance
- **Test Coverage**: Maintain >80% coverage
- **Code Quality**: Monitor static analysis results
- **Documentation**: Keep docs synchronized with code

## Contributing to Infrastructure

When improving JOTP infrastructure:
1. Maintain backward compatibility
2. Document changes thoroughly
3. Update relevant tests
4. Consider cross-platform compatibility

---

**See also**: [Roadmap](../roadmap/) for infrastructure improvement plans, [User Guide](../user-guide/) for usage documentation.
