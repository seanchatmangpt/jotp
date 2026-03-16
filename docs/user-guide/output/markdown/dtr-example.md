# DTR Documentation Example

This document demonstrates how DTR (Documentation Testing Runtime) works in JOTP.

## What is DTR?

DTR is a framework that generates **living documentation** from your tests. When you write tests, you also write documentation that never gets out of sync because it's generated from the same source.

## How It Works

### 1. Write a Test with Documentation Annotations

```java
package io.github.seanchatmangpt.jotp.doctest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DocTestExtension.class)
class ExampleDocIT {

    @DocSection("Basic Usage")
    @DocNote("This test demonstrates the basic usage pattern.")
    @DocCode("""
        var result = 2 + 2;
        assertThat(result).isEqualTo(4);
        """)
    @Test
    void basicExample() {
        var result = 2 + 2;
        assertThat(result).isEqualTo(4);
    }
}
```

### 2. Run the Test

```bash
./mvnw test -Dtest=ExampleDocIT
```

### 3. View Generated Documentation

Open `target/site/doctester/ExampleDocIT.html` in your browser.

## Annotations

### @DocSection

Groups tests into logical sections:

```java
@DocSection("Process Creation")
@Test
void createProcess() { ... }
```

### @DocNote

Adds explanatory text:

```java
@DocNote("A Proc is created with an initial state and a pure handler.")
@Test
void testProc() { ... }
```

### @DocCode

Includes code examples:

```java
@DocCode("""
    var proc = new Proc<>(0, (s, m) -> s + 1);
    proc.tell("increment");
    """)
@Test
void testMessaging() { ... }
```

### @DocWarning

Highlights important warnings:

```java
@DocWarning("Never share Proc state across thread boundaries")
@Test
void testIsolation() { ... }
```

## Generated Output

The DocTestExtension generates Bootstrap-styled HTML with:

- **Color-coded test results** (green=passed, red=failed)
- **Syntax-highlighted code blocks**
- **Organized sections**
- **Responsive design**
- **Test status badges**

## Example Output Structure

```html
<div class="container">
  <h1>ExampleDocIT</h1>
  <p class="text-muted">Generated 2026-03-15 · jOTP DocTest</p>

  <h2 class="mt-4">Basic Usage</h2>

  <div class="card mb-3 test-passed">
    <div class="card-body">
      <h5 class="card-title">
        basicExample()
        <span class="badge bg-success">PASSED</span>
      </h5>

      <p>This test demonstrates the basic usage pattern.</p>

      <pre><code>var result = 2 + 2;
assertThat(result).isEqualTo(4);</code></pre>
    </div>
  </div>
</div>
```

## Benefits

1. **Always Up-to-Date** - Documentation is generated from working tests
2. **Executable Examples** - Every code sample actually runs
3. **Test Coverage** - Documentation tests count toward coverage
4. **Living Documentation** - Tests and docs stay in sync automatically
5. **Easy Maintenance** - Update tests, docs update automatically

## Best Practices

### 1. Use Descriptive Section Names

```java
// Good
@DocSection("Process Creation and Lifecycle")

// Bad
@DocSection("Test 1")
```

### 2. Provide Clear Explanations

```java
@DocNote("""
    A Proc<S,M> represents a lightweight process with:
    - State of type S (private to the process)
    - Messages of type M (sent via tell/ask)
    - A pure handler (S, M) -> S that updates state
    """)
```

### 3. Include Complete, Working Examples

```java
@DocCode("""
    var proc = new Proc<>(0, (s, m) -> s + 1);
    proc.tell("increment");  // State becomes 1
    proc.tell("increment");  // State becomes 2
    Integer state = proc.ask("sync").get(5, SECONDS);
    assertThat(state).isEqualTo(2);
    proc.stop();  // Always clean up
    """)
```

### 4. Add Warnings for Common Pitfalls

```java
@DocWarning("""
    Never expose Proc state directly via getters.
    State should only be observed through ask() to ensure
    thread-safe access to the private process state.
    """)
```

## Integration with CI/CD

### GitHub Actions Example

```yaml
name: Generate Documentation

on:
  push:
    branches: [main]

jobs:
  docs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 26
        uses: actions/setup-java@v3
        with:
          java-version: '26'
          distribution: 'temurin'
      - name: Generate DTR Documentation
        run: ./docs/generate-dtr-docs.sh --publish
      - name: Deploy to GitHub Pages
        uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./docs/user-guide/output/html
```

### Pre-commit Hook

```bash
#!/bin/bash
# .git/hooks/pre-commit

echo "Generating DTR documentation..."
./docs/generate-dtr-docs.sh
git add target/site/doctester/
```

## Advanced Features (Planned)

### Multi-Format Output

Future versions will support:

- **Markdown** - For static site generators
- **LaTeX** - For PDF generation
- **Reveal.js** - For presentation slides
- **JSON** - For structured data APIs

### Cross-References

```java
@DocSection("Process Creation")
@DocSeeAlso("io.github.seanchatmangpt.jotp.doctest.ProcDocIT#messaging")
@Test
void createProcess() { ... }
```

### Custom Templates

```java
@DocTemplate("custom-template.html")
@Test
void customStyledDoc() { ... }
```

## Current Status

### Working
- ✅ DocTestExtension (HTML generation)
- ✅ Basic annotations (@DocSection, @DocNote, @DocCode, @DocWarning)
- ✅ Bootstrap styling
- ✅ Test result tracking

### In Progress
- 🔄 DTR framework integration
- 🔄 Multi-format output
- 🔄 Cross-reference system

### Planned
- 📋 Custom templates
- 📋 Diagram generation
- 📋 API reference linking

## Getting Started

1. **Read the full guide:** `docs/user-guide/README-DTR.md`
2. **Check existing examples:** `src/test/java/io/github/seanchatmangpt/jotp/doctest/`
3. **Run the generation script:** `./docs/generate-dtr-docs.sh`
4. **View output:** `open target/site/doctester/index.html`

## Resources

- **Generation Script:** `docs/generate-dtr-docs.sh`
- **Full Guide:** `docs/user-guide/README-DTR.md`
- **Setup Report:** `docs/DTR-SETUP-REPORT.md`
- **Examples:** `src/test/java/io/github/seanchatmangpt/jotp/doctest/`

---

**Note:** This document was created to demonstrate the DTR concept. Actual documentation generation requires fixing main source compilation issues first (see `docs/DTR-SETUP-REPORT.md`).
