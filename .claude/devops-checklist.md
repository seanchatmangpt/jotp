# Pre-commit Checklist

**Version**: 1.0.0-Alpha
**Purpose**: Ensure code quality before committing changes
**Usage**: Review this checklist before every commit/PR

---

## Code Quality

### Formatting

- [ ] **Code formatted with Spotless**
  ```bash
  mvnd spotless:apply -q
  ```
  - All Java files use Google Java Format (AOSP style)
  - No trailing whitespace
  - No mixed tabs/spaces
  - Line length within reason (typically <120 chars)

- [ ] **No Spotless violations**
  ```bash
  mvnd spotless:check
  ```
  - Should pass without errors
  - Auto-formatted by PostToolUse hook

### Style & Conventions

- [ ] **Follows Java 26 best practices**
  - Use sealed types instead of enums where appropriate
  - Use pattern matching instead of instanceof/cast
  - Use records instead of classes for data holders
  - Use virtual threads instead of platform threads
  - Use `var` for inferred types (when type is obvious)

- [ ] **OTP conventions followed**
  - Supervisor trees properly structured
  - Process links/monitors used correctly
  - "Let it crash" philosophy applied
  - Pure state handlers (no side effects)
  - Exit signals trapped when needed

- [ ] **Naming conventions**
  - Class names: PascalCase (e.g., `Proc`, `Supervisor`)
  - Method names: camelCase (e.g., `spawn`, `tell`)
  - Constants: UPPER_SNAKE_CASE (e.g., `MAX_RESTARTS`)
  - Private fields: camelCase (e.g., `mailbox`, `state`)

---

## Testing

### Test Coverage

- [ ] **Unit tests added/updated**
  - New features have unit tests
  - Bug fixes have regression tests
  - Edge cases covered
  - Property-based tests for complex logic

- [ ] **Integration tests added/updated**
  - Cross-component interactions tested
  - Supervisor restart scenarios tested
  - Crash recovery behavior tested

- [ ] **All tests pass**
  ```bash
  mvnd verify -T1C
  ```
  - Unit tests: `mvnd test`
  - Integration tests: `mvnd verify`
  - No skipped or ignored tests (without justification)

### Test Quality

- [ ] **Tests are deterministic**
  - No reliance on timing
  - No shared mutable state between tests
  - Clean up resources in `@AfterEach`

- [ ] **Tests are readable**
  - Given-When-Then structure
  - Descriptive test names
  - Assertions with clear error messages

- [ ] **Tests are fast**
  - No unnecessary sleeps
  - No expensive I/O (use mocking)
  - Parallel execution compatible

---

## Documentation

### Code Documentation

- [ ] **Public APIs have Javadoc**
  ```java
  /**
   * Spawns a new OTP process with the given initial state and message handler.
   *
   * <p>The process runs in a virtual thread and processes messages from its mailbox
   * sequentially using the provided handler function.
   *
   * @param initialState the initial state of the process
   * @param handler the message handler function (state, message) -> newState
   * @param <S> the state type
   * @param <M> the message type
   * @return a new Proc instance
   * @throws IllegalArgumentException if initialState or handler is null
   */
  public static <S, M> Proc<S, M> spawn(S initialState, BiFunction<S, M, S> handler)
  ```

  - All `public` methods have Javadoc
  - `@param` tags for all parameters
  - `@return` tag for return values
  - `@throws` tag for checked exceptions

- [ ] **Complex logic explained**
  - Non-obvious algorithms have comments
  - Trade-offs documented
  - References to external resources (papers, specs)

- [ ] **Code is self-documenting**
  - Good variable/method names
  - No unnecessary comments
  - Explain "why", not "what"

### Project Documentation

- [ ] **README updated** (if applicable)
  - New features mentioned
  - API changes documented
  - Examples updated

- [ ] **CHANGELOG.md updated** (if applicable)
  - Follow [Keep a Changelog](https://keepachangelog.com/)
  - Add entries under "Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"

- [ ] **Architecture docs updated** (if applicable)
  - New patterns/architecture documented
  - Diagrams updated
  - Integration guides updated

---

## Build & Validation

### Build Checks

- [ ] **Project compiles without errors**
  ```bash
  mvnd compile -T1C
  ```

- [ ] **No warnings in compilation**
  - Check for deprecation warnings
  - Check for unchecked warnings
  - Fix or suppress with justification

- [ ] **Javadoc generates without errors**
  ```bash
  mvnd javadoc:javadoc
  ```

### Guard Validation

- [ ] **No forbidden patterns** (simple-guards.sh)
  ```bash
  bash .claude/hooks/simple-guards.sh
  ```
  - No `TODO`, `FIXME`, `XXX` (H_TODO)
  - No mock/stub implementations in main code (H_MOCK)
  - No empty/placeholder returns (H_STUB)

- [ ] **Dependencies validated**
  ```bash
  mvnd dependency:tree
  ```
  - No unwanted dependencies
  - License compatibility verified
  - Vulnerability scan passed

---

## Breaking Changes

### API Compatibility

- [ ] **Breaking changes documented**
  - CHANGELOG.md updated with "Breaking Changes" section
  - Migration guide provided (if needed)
  - Version bumped correctly (MAJOR version for breaking changes)

- [ ] **Deprecated APIs marked**
  ```java
  /**
   * @deprecated Use {@link #newMethod()} instead.
   * This method will be removed in version 2.0.0.
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  public void oldMethod() {
      // ...
  }
  ```

- [ ] **Binary compatibility checked**
  - japicmp plugin passes (if configured)
  - No signature changes without version bump

### Module System

- [ ] **module-info.java updated** (if applicable)
  - New exports added (if needed)
  - New requires added (if needed)
  - No accidental exports of internal packages

---

## Security

### Code Security

- [ ] **No hardcoded secrets**
  - No passwords, API keys, tokens in code
  - Use environment variables or configuration

- [ ] **Input validation**
  - Validate public API inputs
  - Sanitize data from external sources
  - Use `Objects.requireNonNull` for critical parameters

- [ ] **Error handling**
  - Don't leak sensitive information in error messages
  - Log security-relevant events
  - Fail safely (fail closed)

### Dependencies

- [ ] **Dependency vulnerabilities checked**
  ```bash
  mvnd org.owasp:dependency-check-maven:check
  ```
  - No known high-severity vulnerabilities
  - Vulnerabilities documented or mitigated

---

## Performance

### Performance Considerations

- [ ] **No obvious performance regressions**
  - No O(n²) algorithms where O(n) possible
  - No unnecessary object allocations in hot paths
  - Virtual threads used correctly (no pinning)

- [ ] **Resource cleanup**
  - `AutoCloseable` resources in try-with-resources
  - No memory leaks (listeners, callbacks)
  - Virtual threads cleaned up

### Benchmarking (if applicable)

- [ ] **Benchmarks updated**
  ```bash
  mvnd verify -Pbenchmark
  ```
  - New features benchmarked
  - Performance improvements measured
  - No regressions in baseline benchmarks

---

## Git & Commit Messages

### Commit Hygiene

- [ ] **Commits follow conventional commits**
  ```
  feat: add process monitoring support
  fix: resolve race condition in supervisor restart
  docs: update API documentation for StateMachine
  test: add property-based tests for Result type
  refactor: simplify mailbox implementation
  chore: upgrade to JUnit 5.12.0
  ```

- [ ] **Commit messages are descriptive**
  - Subject line: 50 chars or less
  - Body: What changed and why
  - References: `Closes #123`, `Refs #456`

- [ ] **Commits are atomic**
  - One logical change per commit
  - No unrelated changes
  - No merge commits in feature branches

### Branch Hygiene

- [ ] **Branch name follows conventions**
  - `feature/your-feature-name`
  - `bugfix/your-bugfix-name`
  - `hotfix/your-hotfix-name`
  - `release/1.0.0`

- [ ] **Branch is up to date with main**
  ```bash
  git fetch origin
  git rebase origin/main
  ```

---

## Pre-commit Script

To automate some of these checks, create a git pre-commit hook:

```bash
#!/bin/bash
# .git/hooks/pre-commit

set -e

echo "Running pre-commit checks..."

# Format code
echo "Formatting code..."
mvnd spotless:apply -q

# Run unit tests
echo "Running unit tests..."
mvnd test -q

# Check for forbidden patterns
echo "Checking for forbidden patterns..."
bash .claude/hooks/simple-guards.sh

# All checks passed
echo "✅ Pre-commit checks passed!"
```

Install the hook:

```bash
cp .git/hooks/pre-commit.sample .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

---

## Quick Reference

### Minimal Checklist (Fast Path)

For trivial changes (typos, docs, etc.):

- [ ] Code formatted (`mvnd spotless:apply`)
- [ ] Tests pass (`mvnd test`)
- [ ] No forbidden patterns (`bash .claude/hooks/simple-guards.sh`)

### Standard Checklist (Feature Work)

For typical feature development:

- [ ] Code formatted
- [ ] Tests added/updated
- [ ] All tests pass
- [ ] Javadoc added for public APIs
- [ ] CHANGELOG updated (if breaking)
- [ ] Commits follow conventional commits
- [ ] No forbidden patterns

### Comprehensive Checklist (Release)

For release commits:

- [ ] All items in standard checklist
- [ ] Full build passes (`mvnd verify`)
- [ ] Integration tests pass
- [ ] Breaking changes documented
- [ ] Version bumped correctly
- [ ] Release notes prepared
- [ ] Security audit passed

---

**Last Updated**: 2025-03-14
**Version**: 1.0.0-Alpha
