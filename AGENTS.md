# Engineering rules for social-core

This repository is being built phase by phase from an accepted blueprint. Documentation describes the target architecture; inspect code and phase records before assuming a feature exists.

## Before changing code

- Read `docs/agent-handoff.md` first for the active task, next owner and review findings; follow `docs/thread-workflow.md`. Conversation summaries do not supersede repository contracts.

- Read `README.md`, `docs/implementation-handoff.md`, `docs/architecture.md`, the requested phase in `docs/roadmap.md`, relevant ADRs and subsystem docs. Read `docs/security.md` for any user-facing behavior.
- Inspect existing code, git status and local instructions. Preserve user edits/staged changes. Do not create a parallel abstraction because you did not search for the existing one.
- Work only on the requested phase/task and its necessary prerequisites. Do not implement future phases, scaffold speculative modules, or silently redesign accepted decisions.
- Make minor implementation decisions yourself and document meaningful refinements. Raise conflicts that change product/security/architecture; propose a concrete ADR change rather than improvising.

## Boundaries and code

- Preserve the allowed module dependency DAG. Only another module's `api` package may be imported. Never access its repository/entity/internal application implementation, even for a convenient join.
- Cross-module SQL reads are allowed only through the explicitly published versioned views/consumer allowlist in `docs/database-design.md`. No cross-module JPA relationships. Cross-module writes use owner APIs/events.
- Controllers perform HTTP translation and boundary validation. Application use cases own transactions/policies; domain objects enforce intrinsic invariants. Domain has no Spring services, HTTP, SecurityContext or transport DTO dependencies; Jakarta Persistence annotations are the documented exception.
- Use constructor injection, immutable API/HTTP records where useful, explicit entity mutation and small cohesive use cases. No field injection, universal response wrapper, generic BaseService/BaseController, boilerplate `ServiceImpl`, Lombok `@Data`, or blind entity serialization.
- Create interfaces for real module/technical boundaries. Do not introduce abstractions or dependencies merely because they may be useful someday. Explain every new dependency and follow the phase introduction matrix.
- Core concepts stay application-neutral. No family-specific domain terms, tenant hierarchy or provider credentials inside social profiles.

## Data and consistency

- Every schema change uses a new Flyway migration; never edit an applied migration or use Hibernate auto-update. Use PostgreSQL semantics and real PostgreSQL tests, not H2.
- UUID identifiers, UTC/Instant timestamps, explicit constraints/indexes and version checks follow the schema plan. Uniqueness needs a database constraint, not only a service query.
- One command owns its transaction; use the documented lock order/guards for conflicting mutations. Do not put network/storage calls under business locks or use distributed locks indiscriminately.
- Persist required outbox/jobs/audit in the originating transaction. Do not replace durability with `@Async`. Consumers tolerate duplication/reorder, and all retries are bounded/idempotent.
- Do not silently cache permissions or turn source authorization eventually consistent. Derived counts/projections never grant access. Use batched queries and bounded pagination; no unbounded lists or N+1 fetching.

## Security

- UUID knowledge is not permission. Check actor, ownership, scope, current membership/relationships/blocks and audience through the owning policy for every resource path and mutation.
- Never auto-link identities by email, expose tokens in URLs/logs/events, trust role/owner fields from request JSON, or return private data in search/notification/media fallbacks.
- Follow the exact refresh replay, CSRF, provider proof and media version-pinning contracts. Do not weaken a denial path to make a happy-path test pass.
- Use parameterized SQL, typed allowlisted DTOs, bounded inputs/quotas/timeouts and secure framework/library crypto. No custom cryptographic protocols.
- No secrets/real personal data in source, fixtures, logs or docs. Security/moderation actions need attributable audit and current permission checks.

## Verification and delivery

- Behavioral changes require meaningful tests; bug fixes require regression tests. Include denied access and invalid state, and named concurrency/retry cases where relevant.
- Run targeted tests, then formatting and `./mvnw verify` for an implemented phase. Docker/Testcontainers failures are failures, not permission to skip integration tests. Report any unrun/blocked check honestly.
- Architecture tests must pass; do not add exclusions to hide a violation. Test published SQL views and SQL/object policy parity when affected.
- Update the implemented OpenAPI contract, schema/domain/subsystem documentation, operational configuration and phase completion record. Architectural changes require a new/superseding ADR.
- Report what changed, why, tests/evidence and material limitations. Keep scope reviewable. Automatically create local commits for completed, verified work according to `docs/git-workflow.md`; this is standing user authorization and requires no further confirmation. Do not push, publish, deploy or contact external people unless explicitly authorized.

The implementation definition of done and frozen/flexible decisions are in `docs/implementation-handoff.md`.

## Git delivery

- Read `docs/git-workflow.md`. Use Conventional Commits and commit completed, verified task-scoped changes before the final report. Never claim completion without reporting the commit hash or an exact commit blocker.
- Inspect the index before staging; stage explicit owned paths/hunks only. Never include unrelated user/other-agent changes, discard them, or use blanket staging in a shared dirty checkout.
- Do not commit failing or unverified implementation as completed work. Do not bypass hooks, amend commits, rewrite history, force-push or change Git identity automatically.
- One writer per checkout/index; concurrent implementation needs separate worktrees/branches. Commit authorization does not authorize push or merge.

## Sequential thread handoff

- The user runs one thread at a time in this checkout. Use `docs/agent-handoff.md` as the fixed mailbox; no agent spawning or external messaging is implied.
- Before returning, update the mailbox with actual code commit, checks, limitations and next owner. Preserve reviewer findings and respond to each ID; only the main reviewer marks them resolved or accepts a phase.
- Commit the handoff documentation after the code commit so the mailbox can reference its real hash. Never invent a self-referential report commit hash. A review-only task may commit documentation with outstanding code findings clearly recorded.
