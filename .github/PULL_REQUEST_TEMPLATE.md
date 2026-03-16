---
name: Pull Request
about: Submit changes for review
title: '[<type>] <brief description>'
labels: ''
assignees: ''
---

## PR Description
<!-- Describe your changes in detail -->
<!-- What problem does this PR solve? -->
<!-- What is the rationale for the design decisions? -->

## Type of Change
<!-- Mark the relevant option with an 'x' -->

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Performance improvement
- [ ] Code refactoring
- [ ] CI/CD changes
- [ ] Other (please describe):

## Related Issues
<!-- Link to related issues or PRs -->
<!-- Fixes #<issue_number> -->
<!-- Related to #<issue_number> -->

## Changes Made
<!-- Bullet list of changes -->
-
-
-

## Testing
<!-- Describe the tests you ran and how you validated the changes -->
<!-- Include test results, benchmark results, or manual testing steps -->

### Unit Tests
- [ ] All existing tests pass
- [ ] New tests added for new functionality
- [ ] Coverage maintained at >80%

### Integration Tests
- [ ] Integration tests pass
- [ ] Distributed tests pass (if applicable)

### Benchmarks
- [ ] Performance benchmarks run
- [ ] No regression detected (>5% variance)
- [ ] Benchmark results attached

### Manual Testing
<!-- Describe any manual testing performed -->

## Documentation
- [ ] Code is self-documenting with clear naming
- [ ] Javadoc added/updated for public APIs
- [ ] User guide updated (if applicable)
- [ ] Architecture docs updated (if applicable)

## Security and Compliance
- [ ] No hardcoded secrets or credentials
- [ ] No dependencies with known vulnerabilities
- [ ] Changes follow security best practices
- [ ] Guard system validation passes (./dx.sh validate)

## Checklist
- [ ] Code follows project style guide (Spotless formatting applied)
- [ ] Commit messages follow conventional commits format
- [ ] PR title follows semantic conventions
- [ ] Self-review completed
- [ ] CI/CD pipeline passing
- [ ] Ready for review

## Additional Context
<!-- Any other information, screenshots, or context that helps reviewers -->
<!-- Include architectural decision records (ADRs) if applicable -->

## Performance Impact
<!-- Describe any performance implications -->
- [ ] No performance impact
- [ ] Improved performance (describe improvements)
- [ ] Performance degradation (explain and justify)

## Breaking Changes
<!-- List any breaking changes and migration path -->
<!-- If none, state "None" -->

---

## Reviewer Notes
<!-- Guidance for reviewers - what to focus on -->
<!-- Critical areas that need careful review -->
<!-- Test scenarios to verify -->

## Deployment Notes
<!-- Special considerations for deployment -->
<!-- Configuration changes required -->
<!-- Migration steps needed -->

## Merge Instructions
<!-- Any specific instructions for merging -->
<!-- "Squash and merge" recommended -->
<!-- Delete branch after merge -->
