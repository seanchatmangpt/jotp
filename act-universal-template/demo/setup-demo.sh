#!/bin/bash

# Demo Setup Script - Universal Act Template
# ========================================
# This script sets up a demonstration Maven project to test the universal Act template

set -e

echo "🚀 Setting up Universal Act Template Demo..."
echo "============================================"

# Create demo project directory
mkdir -p demo-project
cd demo-project

# Initialize a simple Maven project
mvn archetype:generate \
    -DgroupId=com.example \
    -DartifactId=demo-app \
    -DarchetypeArtifactId=maven-archetype-quickstart \
    -DinteractiveMode=false

cd demo-app

# Create a simple application
mkdir -p src/main/java/com/example
cat > src/main/java/com/example/App.java << 'EOF'
package com.example;

/**
 * Demo application for testing universal Act template
 */
public class App {
    public static void main(String[] args) {
        System.out.println("Hello from Universal Act Demo!");
        System.out.println("Testing Act with universal template...");
    }

    public String getGreeting() {
        return "Hello from Universal Act Demo!";
    }
}
EOF

# Create a test
mkdir -p src/test/java/com/example
cat > src/test/java/com/example/AppTest.java << 'EOF'
package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for demo application
 */
public class AppTest {
    @Test
    void testGreeting() {
        App app = new App();
        assertEquals("Hello from Universal Act Demo!", app.getGreeting());
    }

    @Test
    void testMain() {
        // Simple test to ensure main method runs
        assertDoesNotThrow(() -> App.main(new String[]{}));
    }
}
EOF

# Add some project metadata
cat >> pom.xml << 'EOF'
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
EOF

# Update dependencies to use JUnit 5
sed -i 's/junit/junit-jupiter/' pom.xml
sed -i 's/<version>4.11</<version>5.9.1</' pom.xml

# Copy universal Act template files
echo "📋 Copying Universal Act Template..."
cd ../..

# Copy template files to demo project
cp .act.config.yaml demo-app/
cp .actrc demo-app/
cp .secrets-template demo-app/.secrets
cp .env-template demo-app/.env

echo "✅ Demo project created successfully!"
echo ""
echo "📂 Demo Project Location: $(pwd)/demo-app"
echo ""
echo "🚀 To test with Act:"
echo "   cd demo-app"
echo "   source ../test-act.sh"
echo ""
echo "📝 Test different project types:"
echo "   cd demo-app"
echo "   cp ../project-types/spring-boot/.act.config.yaml ."
echo "   source ../test-act.sh"
echo ""
echo "🔧 Customize for your project:"
echo "   1. Edit .act.config.yaml for your project type"
echo "   2. Update .env with your environment variables"
echo "   3. Copy .secrets and fill in your secrets"
echo "   4. Run: source ../test-act.sh"