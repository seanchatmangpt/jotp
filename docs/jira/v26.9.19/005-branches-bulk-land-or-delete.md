# jotp: triage 9 PR-less unmerged remote branches

- Standing: OPEN
- Created: 2026-09-19 (v26.9.19 gh survey wave)
- Source: origin branches not merged into `main` with no open PR
- Evidence: `git branch -r --no-merged origin/main`: claude/add-apache-license-GaVj0 claude/add-claude-documentation-mNoze claude/add-license-headers-aN2sh claude/add-vision-2030-utils-ooFE2 claude/complete-project-TAAD5 claude/jotp-java-architecture-2pHF0 claude/setup-java-26-build-2mDTp claude/setup-maven-package-NfF4L claude/setup-redis-postgres-maven-Gyuwi

## Work to complete
- Triage each branch: land (open a PR) or delete (`git push origin --delete <branch>`). Work in batches; record decisions in History.

## Acceptance
- `git branch -r --no-merged origin/main` is empty after `git fetch --prune`.

## History
- 2026-09-19 | OPEN | survey found 9 PR-less branches | full list above | triage pending
