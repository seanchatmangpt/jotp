# Build Issues Troubleshooting Guide

## Java 26 Preview Feature Setup

### Symptoms
- Compilation fails with "preview features are not enabled"
- Error: "features preview is disabled"
- Class format errors related to preview features
- IDE shows red squiggles on valid Java 26 syntax

### Diagnosis Steps

1. **Check Java version:**
   ```bash
   java -version
   # Should show: java version "26" or "openjdk 26"
   ```

2. **Verify preview flags in Maven:**
   ```bash
   grep -A 5 "maven-compiler-plugin" pom.xml
   # Look for: --enable-preview
   ```

3. **Check IDE configuration:**
   - IntelliJ: Settings → Build → Compiler → Java Compiler
   - Eclipse: Project Properties → Java Compiler
   - VS Code: settings.json (java.compile.nullAnalysis.mode)

4. **Verify module descriptor:**
   ```bash
   cat src/main/java/module-info.java
   # Should have: requires java.base; and preview imports
   ```

### Solutions

#### Maven Configuration
Add to `pom.xml`:
```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <release>26</release>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>

<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>--enable-preview</argLine>
    </configuration>
</plugin>
```

#### IDE Configuration (IntelliJ)
```
Settings → Build, Execution, Deployment → Compiler → Java Compiler
→ Additional command line parameters: --enable-preview
```

#### IDE Configuration (VS Code)
```json
{
    "java.compile.nullAnalysis.mode": "automatic",
    "java.configuration.runtimes": [
        {
            "name": "JavaSE-26",
            "default": true,
            "enabled": true
        }
    ]
}
```

### Prevention
- Always run Maven with preview flags: `mvnd compile -Denable-preview`
- Set IDE defaults to enable preview features
- Document in project README
- Use Maven Wrapper with preset configuration

---

## Maven Compilation Errors

### Symptoms
- `CompilationFailureException`
- "cannot find symbol" errors
- "package does not exist" errors
- Module system errors (exports/opens)

### Diagnosis Steps

1. **Check Maven version:**
   ```bash
   mvn -version
   # Should be Maven 4.0+ for Java 26
   ```

2. **Clean build:**
   ```bash
   mvnd clean compile
   ```

3. **Check dependency tree:**
   ```bash
   mvnd dependency:tree
   ```

4. **Verify module path:**
   ```bash
   mvnd compile -X | grep module
   ```

### Solutions

#### Common Module Issues
**Problem:** "package X is not visible"
```java
// module-info.java
module io.github.seanchatmangpt.jotp {
    requires java.base;
    exports io.github.seanchatmangpt.jotp;  // Add this
    opens io.github.seanchatmangpt.jotp to org.junit;  // For testing
}
```

#### Dependency Conflicts
```bash
# Find conflicting dependencies
mvnd dependency:tree -Dverbose

# Exclude transitive dependency
<dependency>
    <groupId>com.example</groupId>
    <artifactId>problematic</artifactId>
    <exclusions>
        <exclusion>
            <groupId>conflicting.group</groupId>
        </exclusion>
    </exclusions>
</dependency>
```

#### Corrupted Local Cache
```bash
# Clear Maven cache
rm -rf ~/.m2/repository
mvnd dependency:purge-local-repository
mvnd dependency:go-offline
```

### Prevention
- Use `mvnd clean install` regularly
- Lock dependency versions in `<dependencyManagement>`
- Run `mvnd dependency:analyze` in CI/CD
- Use Maven Enforcer Plugin for version bans

---

## Spotless Formatting Issues

### Symptoms
- `SpotlessApplyException` during build
- Code formatted differently in IDE
- Pre-commit hook fails
- CI/CD fails on formatting

### Diagnosis Steps

1. **Check Spotless version:**
   ```bash
   grep spotless pom.xml
   ```

2. **Test formatting:**
   ```bash
   mvnd spotless:check
   mvnd spotless:apply
   ```

3. **Compare IDE formatter:**
   - Check if IDE uses Google Java Format
   - Verify import order settings

### Solutions

#### Apply Formatting
```bash
# Fix all files
mvnd spotless:apply

# Fix specific module
mvnd spotless:apply -Pmodule-name

# Dry run to see what would change
mvnd spotless:diff
```

#### IDE Configuration (IntelliJ)
```
Settings → Tools → Spotless
✓ Enable Spotless
✓ Reformat with Spotless
```

#### Skip Temporarily (Not Recommended)
```bash
mvnd verify -Dspotless.skip=true
```

### Prevention
- Enable Spotless hook in `.git/hooks/pre-commit`
- Run `mvnd spotless:apply` before commit
- Configure IDE to use Spotless formatter
- Add Spotless check to PR validation

---

## Dependency Conflicts

### Symptoms
- `NoSuchMethodError` at runtime
- `ClassNotFoundException`
- Multiple versions of same dependency on classpath
- AbstractMethodError during instantiation

### Diagnosis Steps

1. **Find conflicting dependencies:**
   ```bash
   mvnd dependency:tree -Dverbose | grep "conflict"
   ```

2. **Check effective POM:**
   ```bash
   mvnd help:effective-pom > effective-pom.xml
   ```

3. **Analyze classpath:**
   ```bash
   mvnd dependency:build-classpath -Dmdep.outputFile=classpath.txt
   cat classpath.txt | tr ':' '\n' | sort | uniq -d
   ```

4. **Use JAR analyzer:**
   ```bash
   # Find which JAR contains a class
   for jar in ~/.m2/repository/**/*.jar; do
       unzip -l $jar | grep ClassName && echo $jar
   done
   ```

### Solutions

#### Explicit Version Management
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.junit</groupId>
            <artifactId>junit-bom</artifactId>
            <version>5.11.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

#### Exclude Transitive Dependency
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>library</artifactId>
    <exclusions>
        <exclusion>
            <groupId>conflicting.group</groupId>
            <artifactId>bad-artifact</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

#### Use Enforcer Plugin
```xml
<plugin>
    <artifactId>maven-enforcer-plugin</artifactId>
    <executions>
        <execution>
            <id>enforce-versions</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <bannedDependencies>
                        <excludes>
                            <exclude>org.old:*:*:*:*</exclude>
                        </excludes>
                    </bannedDependencies>
                    <dependencyConvergence/>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Prevention
- Use BOM (Bill of Materials) imports
- Regularly run `mvnd dependency:analyze`
- Enable Enforcer Plugin in builds
- Document version requirements in README

---

## Module System Problems

### Symptoms
- "module X not found" errors
- "split package" errors
- "package X is not declared in module"
- "class X is inaccessible"
- Runtime `ModuleLayer` errors

### Diagnosis Steps

1. **Check module descriptor:**
   ```bash
   cat src/main/java/module-info.java
   ```

2. **Test module path:**
   ```bash
   java --show-module-resolution --enable-preview --module-path $(mvnd dependency:build-classpath -q) -m io.github.seanchatmangpt.jotp/io.github.seanchatmangpt.jotp.Main
   ```

3. **Check for split packages:**
   ```bash
   find target/classes -name "*.class" | cut -d/ -f1-3 | sort | uniq -d
   ```

4. **Verify exports/opens:**
   ```bash
   jdeps --module-path target/classes --generate-open-module target/module-info.java
   ```

### Solutions

#### Fix Split Packages
**Problem:** Same package name in multiple JARs
```xml
<plugin>
    <artifactId>maven-enforcer-plugin</artifactId>
    <configuration>
        <rules>
            <banDuplicatePomDependencyVersions/>
            <bannedDependencies>
                <excludes>
                    <exclude>split.package:*</exclude>
                </excludes>
            </bannedDependencies>
        </rules>
    </configuration>
</plugin>
```

#### Fix Missing Exports
```java
// module-info.java
module io.github.seanchatmangpt.jotp {
    requires java.base;

    // Export public API
    exports io.github.seanchatmangpt.jotp;

    // Open for reflection (testing, frameworks)
    opens io.github.seanchatmangpt.jotp to org.junit;

    // Require other modules
    requires org.slf4j;
}
```

#### Fix Automatic Module Naming
```bash
# If dependency lacks module-info.java, add automatic name
jar --file=dependency.jar --describe-module
# Manual: Add META-INF/MANIFEST.MF with Automatic-Module-Name
```

### Prevention
- Always include `module-info.java` in new modules
- Use `jdeps` to verify module graph
- Test with `--show-module-resolution`
- Document module graph in architecture docs

---

## Quick Reference

### Common Maven Commands
```bash
# Clean build
mvnd clean compile

# Skip tests
mvnd install -DskipTests

# Force update snapshots
mvnd install -U

# Debug compilation
mvnd compile -X | tee build.log

# Offline build
mvnd -o install
```

### Java Version Check
```bash
# Set JAVA_HOME explicitly
export JAVA_HOME=/path/to/jdk-26
export PATH=$JAVA_HOME/bin:$PATH

# Verify
java -version
javac -version
```

### IDE Reset
```bash
# IntelliJ
rm -rf .idea/
rm -rf target/

# Eclipse
rm -rf .project .classpath .settings/

# VS Code
rm -rf .vscode/

# Regenerate
mvnd eclipse:eclipse  # or mvnd idea:idea
```

---

## Related Issues

- **Runtime Issues:** If code compiles but fails at runtime, see `runtime-issues.md`
- **Performance:** If build is slow, see `performance-issues.md`
- **Testing:** If tests fail to compile, see `testing-issues.md`
