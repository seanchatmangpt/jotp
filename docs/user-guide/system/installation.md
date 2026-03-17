# Installation Guide

This guide covers installing JOTP and its prerequisites on any platform.

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java (JDK) | 26+ | `--enable-preview` required |
| Maven | 4.x or mvnd 2.0.0-rc-3 | mvnd recommended (30-40% faster) |
| Git | Any recent version | For cloning the repository |

---

## Automatic Setup (Claude Code Sessions)

If you are using Claude Code on the web, a `SessionStart` hook runs `.claude/setup.sh` automatically. It:

1. Downloads and installs **OpenJDK 26** to `/usr/lib/jvm/openjdk-26`
2. Downloads and installs **mvnd 2.0.0-rc-3** to `/root/.mvnd/`
3. Configures `~/.m2/settings.xml` with proxy credentials (if applicable)
4. Starts the Maven proxy bridge (if `https_proxy` is set)

Nothing manual is needed. To re-run if it failed:

```bash
bash .claude/setup.sh
```

---

## Manual Installation

### Step 1: Install Java 26

**Linux (x86_64):**
```bash
# Download from jdk.java.net/26 or use your package manager
export JAVA_HOME=/usr/lib/jvm/openjdk-26
export PATH=$JAVA_HOME/bin:$PATH

java -version
# openjdk version "26" ...
```

**macOS:**
```bash
brew install --cask temurin@26
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
```

**Windows:**
Download the JDK 26 installer from jdk.java.net and set `JAVA_HOME` in your environment variables.

### Step 2: Install mvnd (Recommended)

```bash
# Download mvnd 2.0.0-rc-3 for Linux x86_64
curl -L https://github.com/apache/maven-mvnd/releases/download/2.0.0-rc-3/\
maven-mvnd-2.0.0-rc-3-linux-amd64.tar.gz | tar xz -C ~/.mvnd/

# Symlink to PATH
ln -sf ~/.mvnd/maven-mvnd-2.0.0-rc-3/bin/mvnd /usr/local/bin/mvnd

mvnd --version
# mvnd 2.0.0-rc-3 (Maven 4.x)
```

Alternatively, use the standard Maven wrapper (`./mvnw`) — all commands work with both.

### Step 3: Clone the Repository

```bash
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp
```

### Step 4: Verify Your Installation

```bash
mvnd verify
# [INFO] BUILD SUCCESS
```

This compiles all sources, runs unit and integration tests, and applies Spotless formatting checks.

---

## Adding JOTP to an Existing Project

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>2026.1.0</version>
</dependency>
```

Enable Java 26 preview features in your compiler plugin:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <release>26</release>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

Add the module to your `module-info.java`:

```java
module com.example.myapp {
    requires io.github.seanchatmangpt.jotp;
}
```

---

## Verifying Your Installation

Run a quick smoke test:

```bash
# Run the full test suite
mvnd verify

# Run a single test class
mvnd test -Dtest=ProcTest

# Start an interactive JShell session
mvnd jshell:run
```

In JShell, try:

```java
/open PRINTING
import io.github.seanchatmangpt.jotp.*;

var proc = Proc.start(state -> msg -> state + 1, 0);
proc.send("tick");
proc.send("tick");
var count = proc.ask(r -> r, java.time.Duration.ofSeconds(1));
System.out.println("Count: " + count);  // Count: 2
```

---

## Troubleshooting

**"Java 26 not found"**
- Verify: `java -version` — must show Java 26
- Fix: `export JAVA_HOME=/usr/lib/jvm/openjdk-26 && export PATH=$JAVA_HOME/bin:$PATH`

**"--enable-preview not recognized"**
- Cause: Wrong JDK version
- Fix: Confirm `javac -version` shows `javac 26`

**"Spotless formatting violations" at compile**
- Fix: `mvnd spotless:apply -q` then retry

**Build is slow**
- Fix: Warm the build cache: `mvnd compile -q -T1C`
- Confirm daemon is running: `jps | grep mvnd`

**"Module not found" after git pull**
- Cause: Stale classpath cache
- Fix: `rm -rf ~/.m2/repository/io/github/seanchatmangpt && mvnd clean compile`

---

*Next: [Getting Started with JOTP](getting-started.md)*
