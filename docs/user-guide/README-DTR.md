# DTR Documentation Generation Guide

This guide explains how to generate and work with DTR (Documentation Testing Runtime) documentation in JOTP.

## Overview

JOTP uses two documentation testing systems:

1. **DocTestExtension** - Custom JUnit 5 extension that generates Bootstrap-styled HTML documentation from annotated tests
2. **DTR Framework** - External framework (`io.github.seanchatmangpt.dtr:dtr-core`) for advanced multi-format documentation generation

## Current Setup

### DocTestExtension (Working)

The current working system uses custom annotations:

- `@DocSection("Section Name")` - Groups tests into documentation sections
- `@DocNote("Description")` - Adds explanatory text
- `@DocCode("code snippet")` - Includes code examples
- `@DocWarning("Warning message")` - Highlights important warnings

**Example:**

```java
@ExtendWith(DocTestExtension.class)
class ProcDocIT {

    @DocSection("Process Creation")
    @DocNote("A Proc<S,M> is created with an initial state and a pure handler.")
    @DocCode("""
        Proc<Integer, String> counter = new Proc<>(0, (state, msg) -> state + 1);
        assertThat(counter.isRunning()).isTrue();
        counter.stop();
        """)
    @Test
    void createProc() {
        Proc<Integer, String> counter = new Proc<>(0, (state, msg) -> state + 1);
        assertThat(counter.thread().isAlive()).isTrue();
        counter.stop();
    }
}
```

**Output Location:** `target/site/doctester/`

### DTR Framework (Integration in Progress)

The external DTR framework provides advanced features:

- Multi-format output (Markdown, LaTeX, Reveal.js, HTML, JSON)
- Cross-referencing between documentation sections
- RenderMachine for customizable output formats
- Integration with JUnit 5 via `DtrExtension`

**Current Status:** Basic integration is working, but many test files need import fixes:

```java
// Correct import
import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrExtension;

// Usage
@ExtendWith(DtrExtension.class)
class MyTest {
    @Test
    void testWithDocs(DtrContext ctx) {
        ctx.sayNextSection("Section Title");
        ctx.say("Description text");
        ctx.sayCode("code example");
    }
}
```

## Generating Documentation

### Quick Start

```bash
# Run the documentation generation script
./docs/generate-dtr-docs.sh

# View the output
open target/site/doctester/index.html
```

### Manual Generation

```bash
# Generate documentation for a specific test class
./mvnw test -Dtest=ProcDocIT -Dspotless.check.skip=true

# Generate all doctests
./mvnw test -Dtest="*DocIT" -Dspotless.check.skip=true
```

### CI/CD Integration

```bash
# Generate and publish to user-guide
./docs/generate-dtr-docs.sh --publish
```

## Current Documentation Files

### Working DocTest Classes

- `ProcDocIT` - Core Proc process documentation
- `SupervisorDocIT` - Supervisor tree documentation
- `MessageBusDocIT` - Message bus patterns
- `ReactiveChannelDocIT` - Reactive channel patterns

### Files Needing DTR Integration

Many test files use DTR but need import fixes:

- Core OTP tests: `GenServerTest`, `StateMachineTest`, `EventManagerTest`
- Enterprise tests: `CircuitBreakerTest`, `BulkheadIsolationTest`, `BackpressureTest`
- Pattern tests: Various message pattern and enterprise pattern tests
- Dogfood tests: Java 26 feature demonstrations

**Issue:** These files use `import io.github.seanchatmangpt.dtr.DtrContext` instead of the correct `import io.github.seanchatmangpt.dtr.junit5.DtrContext`.

## Output Formats

### Current (HTML)

DocTestExtension generates Bootstrap-styled HTML:

- Single-page documentation per test class
- Responsive design with Bootstrap 5
- Color-coded test results (green=passed, red=failed, gray=disabled)
- Syntax-highlighted code blocks
- Organized by sections

**Location:** `target/site/doctester/<ClassName>.html`

### Planned (Multi-Format)

Future DTR RenderMachine integration will support:

1. **Markdown** (`*.md`) - For static site generators (Hugo, Jekyll)
2. **LaTeX** (`*.tex`) - For PDF generation via pdflatex
3. **Reveal.js** (`*.html`) - For presentation slides
4. **HTML** (`*.html`) - Alternative HTML templates
5. **JSON** (`*.json`) - Structured data for API docs

**Output Structure:**
```
target/dtr-docs/
├── markdown/
├── html/
├── latex/
├── revealjs/
└── json/
```

## Configuration

### Maven Dependencies

```xml
<dependency>
    <groupId>io.github.seanchatmangpt.dtr</groupId>
    <artifactId>dtr-core</artifactId>
    <version>2026.4.1</version>
    <scope>test</scope>
</dependency>
```

### Build Configuration

The generation script handles:

1. Compiling test classes
2. Running DocTestExtension tests
3. Organizing output by format
4. Optionally publishing to user-guide

### Excluding Tests

Some test files are excluded from compilation in `pom.xml`:

```xml
<excludes>
    <!-- Messaging system - experimental -->
    <exclude>io/github/seanchatmangpt/jotp/messaging/**</exclude>
</excludes>
```

## Workflow Integration

### Development Workflow

1. Write tests with `@DocSection`, `@DocNote`, `@DocCode` annotations
2. Run `./docs/generate-dtr-docs.sh` to generate documentation
3. View output in `target/site/doctester/`
4. Commit both test code and generated documentation

### Pre-commit Hook (Optional)

Add to `.git/hooks/pre-commit`:

```bash
#!/bin/bash
# Generate docs before commit
./docs/generate-dtr-docs.sh
git add target/site/doctester/
```

### CI/CD Pipeline

```yaml
# Example GitHub Actions
- name: Generate DTR Documentation
  run: ./docs/generate-dtr-docs.sh --publish

- name: Deploy to GitHub Pages
  uses: peaceiris/actions-gh-pages@v3
  with:
    github_token: ${{ secrets.GITHUB_TOKEN }}
    publish_dir: ./docs/user-guide/output/html
```

## Troubleshooting

### Import Errors

**Problem:** `cannot find symbol: class DtrContext`

**Solution:** Fix imports to use `io.github.seanchatmangpt.dtr.junit5.DtrContext`

### Compilation Failures

**Problem:** Test file has syntax errors or missing dependencies

**Solution:**
1. Check if test class is excluded in `pom.xml`
2. Verify all imports are correct
3. Run `./mvnw test-compile` to see detailed errors

### No Output Generated

**Problem:** Tests pass but no HTML files created

**Solution:**
1. Ensure `@ExtendWith(DocTestExtension.class)` is present
2. Check that tests have `@DocSection` or similar annotations
3. Verify `target/site/doctester/` directory exists

### Spotless Format Issues

**Problem:** Spotless check fails during documentation generation

**Solution:** Use `-Dspotless.check.skip=true` flag:

```bash
./mvnw test -Dtest=ProcDocIT -Dspotless.check.skip=true
```

## Best Practices

### Writing Documentation Tests

1. **Use descriptive section names:**
   ```java
   @DocSection("Process Creation") // Good
   @DocSection("Test 1") // Bad
   ```

2. **Provide clear explanations:**
   ```java
   @DocNote("A Proc<S,M> is created with an initial state S and a pure handler (S, M) -> S.")
   ```

3. **Include complete, working examples:**
   ```java
   @DocCode("""
       var proc = new Proc<>(0, (s, m) -> s + 1);
       proc.tell("increment");
       proc.stop();
       """)
   ```

4. **Add warnings for common pitfalls:**
   ```java
   @DocWarning("Never share Proc state across thread boundaries")
   ```

### Organizing Documentation

1. Group related tests in sections
2. Use meaningful test class names (append `DocIT` suffix)
3. Cross-reference related sections
4. Keep examples simple and focused

## Migration from Existing Tests

### Converting Plain Tests to DocTests

**Before:**
```java
@Test
void testProcCreation() {
    Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
    assertThat(p.isRunning()).isTrue();
    p.stop();
}
```

**After:**
```java
@DocSection("Process Creation")
@DocNote("A Proc<S,M> is created with an initial state and a pure handler.")
@DocCode("""
    Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
    assertThat(p.isRunning()).isTrue();
    p.stop();
    """)
@Test
void testProcCreation() {
    Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
    assertThat(p.isRunning()).isTrue();
    p.stop();
}
```

## Resources

- **DTR GitHub:** https://github.com/seanchatmangpt/doctester
- **Example DocTests:** `src/test/java/io/github/seanchatmangpt/jotp/doctest/`
- **Generated Docs:** `target/site/doctester/`
- **Generation Script:** `docs/generate-dtr-docs.sh`

## Status

- ✅ DocTestExtension working
- ✅ HTML output generation
- ✅ Basic documentation examples
- 🔄 DTR framework integration (in progress)
- 🔄 Multi-format output support (planned)
- 🔄 Cross-referencing system (planned)
