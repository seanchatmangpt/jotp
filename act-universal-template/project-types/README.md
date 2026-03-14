# Project-Specific Act Configurations
# =================================
# Pre-configured setups for popular Maven project types

## Available Configurations

### 1. Spring Boot Projects
- **Directory**: `spring-boot/`
- **Features**: Spring Boot specific settings, auto-configuration, profile management
- **Best for**: Spring Boot applications, microservices

### 2. Quarkus Projects
- **Directory**: `quarkus/`
- **Features**: Quarkus native image support, fast startup, GraalVM
- **Best for**: Quarkus applications, cloud-native apps

### 3. Micronaut Projects
- **Directory**: `micronaut/`
- **Features**: Micronaut specific settings, AOT compilation, reflection-free
- **Best for**: Micronaut applications, serverless

### 4. Multi-Module Projects
- **Directory**: `multi-module/`
- **Features**: Parallel builds, dependency management, module testing
- **Best for**: Large projects with multiple modules

### 5. Standard Java Projects
- **Directory**: `standard/`
- **Features**: Basic Maven configuration, universal settings
- **Best for**: Simple Java applications, libraries

## Usage

### Option 1: Copy Specific Configuration
```bash
# For Spring Boot
cp project-types/spring-boot/.act.config.yaml .
cp project-types/spring-boot/.actrc .

# Edit as needed
```

### Option 2: Use as Reference
```bash
# Copy from reference
cp project-types/spring-boot/.act.config.example ./.act.config.yaml
```

### Option 3: Merge with Universal Template
```bash
# Take the best of both
cp .act.config.yaml .act.config.yaml.backup
cp project-types/spring-boot/.act.config.yaml ./
# Edit to merge with your project settings
```

## Customization Guide

### Adding Your Project Type
1. Create a new directory: `project-types/your-project/`
2. Add `.act.config.yaml` and `.actrc`
3. Create a `README.md` with usage instructions
4. Add this directory to the main README

### Common Customizations
- **Java version**: Adjust `JAVA_VERSION` in `.act.config.yaml`
- **Maven options**: Modify `MAVEN_OPTS` for memory settings
- **Platforms**: Add custom platform definitions
- **Secrets**: Add project-specific secrets
- **Environment**: Add project-specific environment variables

## Integration Guide

### 1. Detect Project Type Automatically
```yaml
# In universal .act.config.yaml
env:
  PROJECT_TYPE: "auto-detected"
```

### 2. Load Configuration Based on Type
```bash
# In test-act.sh
if [[ -f "project-types/$PROJECT_TYPE/.act.config.yaml" ]]; then
    cp "project-types/$PROJECT_TYPE/.act.config.yaml" ./.act.config.yaml
fi
```

### 3. Project-Specific Tests
```bash
# Add project-specific test cases
case $PROJECT_TYPE in
    "spring-boot")
        test_spring_boot_features
        ;;
    "quarkus")
        test_quarkus_native
        ;;
esac
```

## Best Practices

### 1. Keep Generic Settings in Root
- Universal settings should be in the root template
- Project-specific settings in subdirectories

### 2. Use Inheritance
- Base settings in root
- Override in project-specific configs

### 3. Document Everything
- README for each project type
- Examples and use cases
- Common issues and solutions

### 4. Test Each Configuration
- Test with sample projects
- Document requirements
- Include troubleshooting

## Contributing

### To Add a New Project Type:
1. Create `project-types/your-name/`
2. Add configuration files
3. Create documentation
4. Add test examples
5. Submit a PR

### Format Requirements:
- Use consistent naming conventions
- Include README.md
- Follow existing structure
- Test thoroughly

## Example: Adding Spring Boot

```yaml
# project-types/spring-boot/.act.config.yaml
env:
  SPRING_PROFILES_ACTIVE: test
  JAVA_OPTS: "-Xmx2g -Xms1g -Dspring.profiles.active=test"

platforms:
  spring-boot:
    image: springprojectsio/spring-boot-app:latest
    options: "--env SPRING_PROFILES_ACTIVE=test"
```

## Example: Adding Quarkus

```yaml
# project-types/quarkus/.act.config.yaml
env:
  JAVA_OPTS: "-Xmx2g -Xms1g -Dquarkus.native.container-build=true"
  MAVEN_OPTS: "-Xmx2g -Xms1g -Dquarkus.profile=test"

platforms:
  quarkus:
    image: quay.io/quarkus/ubi-quarkus-native-image:latest
```

This structure allows you to maintain both universal and project-specific configurations while keeping everything organized and maintainable.