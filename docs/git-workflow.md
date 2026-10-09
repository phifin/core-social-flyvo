# Git workflow and automatic local commits

## Authorization and scope

The repository owner authorizes coding agents to create local commits automatically after completing and verifying requested development work. This applies to future phases, fixes, refactoring and documentation tasks. No repeated permission is needed. A later explicit user instruction overrides this default. Push, remote creation, pull requests, merge, release and deployment are not automatically authorized.

Before work, inspect branch, status and staged/unstaged changes. Preserve unrelated edits and the existing Git identity. Never invent an author identity; report missing identity as a commit blocker. Do not amend, reset, rebase, force-push or bypass hooks without explicit authorization.

## Commit convention

Use `type(scope): imperative summary` in English, with a lowercase type/scope, no trailing period and a subject of at most 72 characters. Scope is required. Types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `perf`, `revert`. Scope names the principal module or concern: `identity`, `account`, `profile`, `platform`, `community`, `graph`, `content`, `media`, `feed`, `notification`, `search`, `moderation`, `deps`, `repo`, `architecture` or `workflow`.

Examples:

```text
chore(repo): establish phase 0 foundation and architecture blueprint
feat(identity): rotate refresh tokens and revoke replayed sessions
fix(identity): reject unlinking the last login method
docs(workflow): define automatic local commit delivery
```

Use a body for nontrivial work: explain why, phase/task, meaningful checks and material limitations. Use `!` and a `BREAKING CHANGE:` footer only for an actual incompatible published contract change. Architecture changes still require the documented ADR process. Never put secrets or personal data in messages.

## Delivery procedure

1. Finish a coherent capability and its documentation/tests. Prefer one commit for a small task; a large phase may use several independently verified vertical-slice commits. Do not make a commit for every file or label unfinished scaffolding as a completed phase.
2. Run relevant targeted checks. An implemented phase requires formatting and full `./mvnw verify`, plus its specified integration/operational checks. Documentation-only changes require diff/links/consistency checks; no redundant application suite is needed when executable files have not changed since successful verification.
3. Inspect `git diff`, `git diff --cached` and status. Stage explicit task-owned files/hunks. Do not use blanket staging in a shared dirty checkout. If unrelated changes are already staged, use path-scoped commit behavior that preserves that index or report the precise blocker; never sweep them into the commit or unstage them silently.
4. Inspect the staged patch for secrets, accidental generated files, unrelated edits and whitespace. Run `git diff --cached --check`. Use a temporary message file with `git commit --file` for multiline messages.
5. Create the local commit without another permission question. Inspect `git show --stat --oneline HEAD` and `git status --short` afterward.
6. Final report includes hashes, delivered behavior, actual checks and any remaining changes/blockers. A failed required check means fix it or report unfinished work; do not commit it as completed work. External provider checks may remain explicitly pending only under the phase's documented readiness policy.

## Threads and branches

One thread writes and commits in a checkout at a time. Other threads may review without editing. Concurrent writers need separate Git worktrees and branches; worktrees isolate the index, not external databases/ports. Agree on API contracts and migration versions before parallel work. Suggested branch names: `feat/phase-1-identity`, `fix/refresh-replay`, `docs/git-workflow`. Do not switch the user's branch automatically merely to match this naming convention. Integration/merge requires explicit authorization and its own verification.

## Enforcement

AGENTS.md and the implementation handoff enforce this delivery policy for agents. No new hook framework or dependency is needed for commit messages. Existing CI remains the code-quality gate; a local commit alone does not prove remote CI passed. If automated message linting becomes necessary, add it as a separate scoped tooling change.

## Initial baseline

The initial Phase 0 commit includes the accepted blueprint, runnable foundation and this workflow. The old staged starter is superseded by the verified social-core bootstrap as part of that explicitly authorized baseline. Subsequent commits must be scoped against this baseline.
