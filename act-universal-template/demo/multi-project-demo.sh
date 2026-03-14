#!/bin/bash

# Multi-Project Demo - Universal Act Template
# ===========================================
# Demonstrate the universal template with different project types

set -e

echo "🏗️  Multi-Project Demo - Universal Act Template"
echo "================================================"

# Base directory
BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$BASE_DIR"

# Clean up previous demos
rm -rf demo-projects
mkdir -p demo-projects

echo "Creating different Maven project types..."
echo "======================================"

# 1. Standard Maven Project
echo "📦 Creating Standard Maven Project..."
cd demo-projects
mkdir standard-project
cd standard-project

mvn archetype:generate \
    -DgroupId=com.example \
    -DartifactId=standard-app \
    -DarchetypeArtifactId=maven-archetype-quickstart \
    -DinteractiveMode=false > /dev/null 2>&1

cd standard-app

# Add basic application
mkdir -p src/main/java/com/example
cat > src/main/java/com/example/App.java << 'EOF'
package com.example;
public class App { public static void main(String[] args) { System.out.println("Standard App!"); } }
EOF

# Copy universal template
cp "$BASE_DIR/../.act.config.yaml" .
cp "$BASE_DIR/../.actrc" .
cp "$BASE_DIR/../.secrets-template" .secrets
cp "$BASE_DIR/../.env-template" .env

echo "✅ Standard project created"

# 2. Spring Boot Project
echo "🌱 Creating Spring Boot Project..."
cd ../
mkdir spring-boot-project
cd spring-boot-project

mvn archetype:generate \
    -DgroupId=com.example \
    -DartifactId=spring-boot-app \
    -DarchetypeArtifactId=maven-archetype-quickstart \
    -DinteractiveMode=false > /dev/null 2>&1

cd spring-boot-app

# Update for Spring Boot
cat >> pom.xml << 'EOF'
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.0</version>
    <relativePath/>
</parent>
EOF

# Add Spring Boot dependency
sed -i '/junit/s/$/\n    <dependency>\n        <groupId>org.springframework.boot</groupId>\n        <artifactId>spring-boot-starter-web</artifactId>\n    </dependency>/' pom.xml

# Create Spring Boot application
mkdir -p src/main/java/com/example
cat > src/main/java/com/example/ExampleApplication.java << 'EOF'
package com.example;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class ExampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
EOF

# Copy Spring Boot specific config
cp "$BASE_DIR/../project-types/spring-boot/.act.config.yaml" .
cp "$BASE_DIR/../.actrc" .

echo "✅ Spring Boot project created"

# 3. Multi-Module Project
echo "🔗 Creating Multi-Module Project..."
cd ../
mkdir multi-module-project
cd multi-module-project

# Create parent POM
cat > pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>multi-module-parent</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>core</module>
        <module>web</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>
</project>
EOF

# Create core module
mkdir -p core/src/main/java/com/example
mkdir -p core/src/test/java/com/example

cat > core/pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>multi-module-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>core</artifactId>
    <name>Core Module</name>
</project>
EOF

cat > core/src/main/java/com/example/Service.java << 'EOF'
package com.example;
public class Service {
    public String getMessage() { return "Hello from Core!"; }
}
EOF

# Create web module
mkdir -p web/src/main/java/com/example
mkdir -p web/src/test/java/com/example

cat > web/pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>multi-module-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>web</artifactId>
    <name>Web Module</name>

    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>core</artifactId>
            <version>1.0.0</version>
        </dependency>
    </dependencies>
</project>
EOF

cat > web/src/main/java/com/example/WebApp.java << 'EOF'
package com.example;
public class WebApp {
    private Service service;
    public WebApp(Service service) { this.service = service; }
    public String getResponse() { return service.getMessage(); }
}
EOF

# Copy multi-module config
cp "$BASE_DIR/../project-types/multi-module/.act.config.yaml" .
cp "$BASE_DIR/../.actrc" .

echo "✅ Multi-module project created"

cd "$BASE_DIR"

echo ""
echo "🎉 Multi-project demo setup complete!"
echo ""
echo "Created project types:"
echo "   1. Standard Maven Project: demo-projects/standard-project/standard-app"
echo "   2. Spring Boot Project: demo-projects/spring-boot-project/spring-boot-app"
echo "   3. Multi-Module Project: demo-projects/multi-module-project"
echo ""
echo "📋 To test each project:"
echo "   cd demo-projects/standard-project/standard-app"
echo "   source ../../../test-act.sh"
echo ""
echo "   cd ../spring-boot-project/spring-boot-app"
echo "   source ../../../test-act.sh"
echo ""
echo "   cd ../multi-module-project"
echo "   source ../../../test-act.sh"
echo ""
echo "🔧 Project-specific configurations already applied:"
echo "   • Standard: Universal template"
echo "   • Spring Boot: Spring-specific env vars and platforms"
echo "   • Multi-Module: Parallel builds and module testing"