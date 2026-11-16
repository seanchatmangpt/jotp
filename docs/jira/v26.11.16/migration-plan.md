# jotp migration plan v26.11.16

Standing: UNVERIFIED

## Identity
- path: /Users/sac/jotp
- remote: https://github.com/seanchatmangpt/jotp.git
- branch@sha: validate-fault-tolerance@25f3765 (NO upstream configured)
- last commit: 2026-03-17; commits: 188; size: 1491 MB (`docs/` = 1.4 GB, `.git` = 24 MB)
- languages/build: Java 26 / Maven (Justfile, Makefile, Containerfiles, docker-compose)
- nested repos: submodules `reactive-messaging-patterns` (-6cc2476, not initialized) and
  `yawl` (-d40eda7, not initialized) — both 0 B on disk

## Observed state
- dirty: 1 (` M .java-version`); untracked: 10 (`Justfile`, `docs/jira/`,
  `src/test/.../GlobalSequenceServiceStressTest.java`, ...)
- stash count: 135 (census) — very large, contents unknown
- unpushed local branches: `validate-fault-tolerance` (current, no upstream), `wip-changes`
- ahead/behind: n/a (no upstream); build dirs: `target/` absent
- local data: `.env.cloud.example`, `.env.jotp.example`, `.env.template` (templates only)
- `docs/` is 1.4 GB — far larger than source; likely benchmark/site assets

## What it is
JOTP — Java OTP framework implementing the 15 Erlang/OTP primitives on virtual threads,
supervision trees, sealed types; includes chaos-engineering and distributed-validation reports.

## Classification
DORMANT (census agrees; no commits since 2026-03-17).

## Migration risk
- 135 stashes and 2 local-only branches could hold real work
- `validate-fault-tolerance` has no upstream so may be unpushed
- untracked stress test + Justfile
Rate: MEDIUM

## Recommended action
COMMIT-PUSH
1. `cd /Users/sac/jotp && git log --oneline origin/main..validate-fault-tolerance | head`
2. `git add -A && git commit -m "wip: validate-fault-tolerance snapshot (pre-migration)"`
3. `git push -u origin validate-fault-tolerance && git push -u origin wip-changes`
4. DECISION: 135 stashes — `git stash list | head -20` (operator only; banned for lanes), then
   either `git bundle create /Volumes/ARCHIVE/jotp.bundle --all` (bundles do NOT carry stashes)
   or convert worthwhile stashes to branches via `git stash branch`.
5. `du -sh docs/* | sort -rh | head` — DECISION: what in 1.4 GB `docs/` is tracked vs junk.
6. `rm -rf target` if present; reclone on new machine.

## First commands on the new machine
`git clone https://github.com/seanchatmangpt/jotp.git && cd jotp && git submodule update --init && mvn -q verify`

## See Also
- ../v26.9.19/
- /Users/sac/docs/jira/v26.11.16/README.md
