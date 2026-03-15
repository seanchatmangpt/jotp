# Demo Scripts for Universal Act Template
# ========================================
# These scripts help you set up and test the universal Act template

## 🚀 Getting Started

### 1. Quick Demo Setup
```bash
# Create a simple demo project
./setup-demo.sh

# Test the demo
cd demo-app
source ../test-act.sh
```

### 2. Multi-Project Demo
```bash
# Create multiple project types
./multi-project-demo.sh

# Test each project
cd demo-projects/standard-project/standard-app
source ../../../test-act.sh

cd ../spring-boot-project/spring-boot-app
source ../../../test-act.sh

cd ../multi-module-project
source ../../../test-act.sh
```

### 3. Quick Test (Any Project)
```bash
# If you already have a Maven project
cd /path/to/your/project
source ../quick-test.sh
```

## 📁 Demo Projects Created

### Standard Maven Project
- Location: `demo-projects/standard-project/standard-app/`
- Features: Simple Java application with JUnit tests
- Configuration: Universal Act template

### Spring Boot Project
- Location: `demo-projects/spring-boot-project/spring-boot-app/`
- Features: Spring Boot web application
- Configuration: Spring Boot optimized Act config

### Multi-Module Project
- Location: `demo-projects/multi-module-project/`
- Features: Parent project with core and web modules
- Configuration: Multi-module Act config with parallel builds

## 🔧 What Each Script Does

### setup-demo.sh
- Creates a simple Maven project
- Copies universal Act template
- Configures basic application and tests
- Provides test instructions

### multi-project-demo.sh
- Creates three different project types
- Applies project-specific configurations
- Demonstrates template adaptability

### quick-test.sh
- Checks prerequisites (Act, Maven, Docker)
- Runs quick build and dry-run test
- Tests individual jobs if they exist
- Provides next steps for full testing

## 📋 After Running Demos

1. **Review the created projects** to see different configurations
2. **Test each project** using the test-act.sh script
3. **Customize** for your actual project needs
4. **Share** with your team for consistent testing

## 💡 Tips

- The demo projects are safe to modify or delete
- Each project shows different aspects of the universal template
- Use quick-test.sh to verify Act works in your environment
- Check act-troubleshooting.md for any issues