# JOTP Makefile Guide

Comprehensive guide to using the JOTP Makefile for development workflows.

## Quick Start

```bash
# Show all available commands
make help

# Full build with tests
make build && make test

# Quick development cycle
make dev

# Run a specific test
make test-class TEST=ProcTest
```

## Build Commands

### `make build`
Full project build using Maven Daemon (mvnd).
- Cleans previous builds
- Compiles source code
- Generates classes to `target/classes`

```bash
make build
```

### `make compile`
Compile source code only (faster for incremental changes).

```bash
make compile
```

### `make clean`
Remove all build artifacts from `target/` directory.

```bash
make clean
```

### `make rebuild`
Clean followed by a full build.

```bash
make rebuild
```

### `make package`
Create JAR packages including source and javadoc jars.

```bash
make package
```

### `make package-shade`
Create a fat/uber JAR with all dependencies included.

```bash
make package-shade
```

## Test Commands

### `make test`
Run all unit tests (matches `*Test.java` pattern).

```bash
make test
```

### `make test-integration`
Run all integration tests (matches `*IT.java` pattern).

```bash
make test-integration
```

### `make test-all`
Run both unit and integration tests (full verification).

```bash
make test-all
```

### `make test-coverage`
Run tests with JaCoCo coverage report.

```bash
make test-coverage
# Report generated to target/coverage/jacoco/index.html
```

**Note:** JaCoCo is currently disabled in `pom.xml` due to network/certificate issues. Uncomment the jacoco-maven-plugin section to enable.

### `make test-watch`
Watch mode for Test-Driven Development (requires `entr`).

```bash
# Install entr first (macOS)
brew install entr

# Run watch mode
make test-watch
```

Automatically re-runs tests when source files change.

### `make test-benchmark`
Run JMH performance benchmarks.

```bash
make test-benchmark
# Results written to target/benchmark-results.json
```

### `make test-class TEST=MyTest`
Run a single test class.

```bash
make test-class TEST=ProcTest
make test-class TEST=SupervisorTest
```

### `make test-it TEST=MyIT`
Run a single integration test.

```bash
make test-it TEST=AtlasFoundationPatternsIT
```

## Code Quality Commands

### `make format`
Format code using Spotless (Google Java Format, AOSP style).

```bash
make format
```

### `make format-check`
Check code formatting without modifying files.

```bash
make format-check
```

### `make lint`
Alias for `make format-check`.

### `make verify`
Full verification pipeline:
1. Build compilation
2. Run all tests
3. Check formatting

```bash
make verify
```

### `make quality`
Run all quality checks:
1. Format code
2. Run all tests

```bash
make quality
```

## Documentation Commands

### `make docs`
Generate Javadoc API documentation.

```bash
make docs
# Output: target/site/apidocs/index.html
```

### `make docs-serve`
Serve documentation locally using Python's HTTP server.

```bash
make docs-serve
# Opens http://localhost:8000
```

Press Ctrl+C to stop the server.

### `make docs-check`
Validate Javadoc (fails on errors or warnings).

```bash
make docs-check
```

## Utility Commands

### `make shell`
Start JShell REPL with JOTP classes in classpath.

```bash
make shell
```

Useful for quick experimentation with JOTP primitives.

### `make version`
Show project version and runtime information.

```bash
make version
```

Output:
```
Name:    jotp
Version: 2026.1.0
Java:    openjdk version "26-beta" 2026-03-17
Maven:   Apache Maven Daemon 2.0.0-rc-3
```

### `make info`
Show detailed project information including Git status.

```bash
make info
```

### `make deps`
Display Maven dependency tree.

```bash
make deps
```

### `make dogfood`
Run dogfood generation and verification.

```bash
make dogfood
```

Validates that all code generation templates produce compilable code.

## Development Workflow Commands

### `make dev`
Quick development cycle: clean + compile.

```bash
make dev
```

Fastest way to get a clean working directory for development.

### `make fast`
Fast build skipping tests.

```bash
make fast
```

Useful for rapid iteration when you don't need test feedback.

### `make watch`
Watch source files and rebuild on changes (requires `entr`).

```bash
make watch
```

Automatically recompiles when Java files change.

## Release Commands

### `make release`
Prepare for release:
1. Clean build
2. Full verification (build + test + quality)
3. Generate documentation

```bash
make release
```

### `make deploy`
Deploy to Maven Central.

```bash
make deploy
```

**Prerequisites:**
- GPG keys configured
- Central Portal credentials in `~/.m2/settings.xml`
- See `CENTRAL-PORTAL-DEPLOYMENT-GUIDE.md`

### `make deploy-dry-run`
Test deployment process without publishing.

```bash
make deploy-dry-run
```

Useful for validating deployment configuration.

## Debug Commands

### `make debug-info`
Show comprehensive debug information.

```bash
make debug-info
```

### `make check-requirements`
Verify all prerequisites are installed.

```bash
make check-requirements
```

Checks:
- Java version (must be 26)
- Maven Daemon (mvnd)
- GPG (optional)

## Advanced Commands

### `make snapshot-build`
Build snapshot version with snapshot profile.

```bash
make snapshot-build
```

### `make release-build`
Build release version with release profile.

```bash
make release-build
```

### `make show-profiles`
List available Maven profiles.

```bash
make show-profiles
```

### `make show-settings`
Display Maven settings from `~/.m2/settings.xml`.

```bash
make show-settings
```

## Central Portal Commands

### `make setup-central`
Interactive guide for setting up Maven Central credentials.

```bash
make setup-central
```

### `make verify-central`
Test connection to Maven Central.

```bash
make verify-central
```

## Common Workflows

### Full Development Cycle

```bash
# 1. Clean start
make dev

# 2. Make changes (edit files)

# 3. Run tests
make test

# 4. Run integration tests
make test-integration

# 5. Verify everything
make verify
```

### Test-Driven Development

```bash
# Terminal 1: Watch mode
make test-watch

# Terminal 2: Watch and rebuild
make watch

# Edit files - tests run automatically
```

### Release Preparation

```bash
# 1. Ensure clean state
make clean

# 2. Full verification
make verify

# 3. Generate documentation
make docs

# 4. Prepare release
make release

# 5. Deploy (when ready)
make deploy
```

### Quick Fix and Test

```bash
# Fast build after a small change
make fast

# Run specific test
make test-class TEST=ProcTest

# Or run all tests
make test
```

## Configuration

The Makefile uses these configurable variables (set in Makefile or environment):

- `JAVA_HOME` - Path to Java 26 installation (default: `/usr/lib/jvm/openjdk-26`)
- `MAVEN` - Maven command (default: `mvnd`)
- `MAVEN_OPTS` - Maven options (default: `-T1C --no-transfer-progress`)
- `PROJECT_VERSION` - Extracted from `pom.xml`

Override environment variables:

```bash
JAVA_HOME=/custom/java26 make build
MAVEN=mvnw make test
```

## Troubleshooting

### "mvnd: command not found"

Install Maven Daemon or use `mvnw` wrapper:

```bash
# Use Maven wrapper
MAVEN=./mvnw make build

# Or install mvnd
brew install mvnd  # macOS
```

### "Java 26 not found"

Ensure Java 26 is installed and JAVA_HOME is set:

```bash
# Check Java version
java -version

# Set JAVA_HOME temporarily
export JAVA_HOME=/usr/lib/jvm/openjdk-26

# Or permanently (add to ~/.zshrc)
echo 'export JAVA_HOME=/usr/lib/jvm/openjdk-26' >> ~/.zshrc
```

### Tests fail with "--enable-preview not recognized"

Wrong Java version in use. Verify Java 26:

```bash
make check-requirements
java -version  # Should show openjdk 26 (OpenJDK 26)
```

### "entr: command not found"

Install entr for watch mode:

```bash
# macOS
brew install entr

# Linux
sudo apt-get install entr
```

### Spotless format violations

```bash
# Auto-format all code
make format

# Then rebuild
make build
```

## Tips

1. **Use `make dev` for fastest feedback** - Skips tests, just compiles
2. **Use `make verify` before commits** - Catches most issues
3. **Use `make test-class` for focused testing** - Faster than full test suite
4. **Enable JaCoCo for coverage** - Uncomment plugin in `pom.xml`
5. **Use `make shell` for experimentation** - JShell with JOTP in classpath

## See Also

- [CLAUDE.md](./CLAUDE.md) - Project overview and architecture
- [CENTRAL-PORTAL-DEPLOYMENT-GUIDE.md](./CENTRAL-PORTAL-DEPLOYMENT-GUIDE.md) - Deployment guide
- [README.md](./README.md) - Project README
