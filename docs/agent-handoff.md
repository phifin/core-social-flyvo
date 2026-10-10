# Agent handoff — Phase 2

## Routing

- Task: phase-2-communities
- Round: 1
- State: READY_FOR_REVIEW
- Last writer: development
- Next owner: main reviewer
- User assignment: 2026-10-10; explicitly authorized Phase 2 implementation.
- Baseline HEAD: `306465b635a2f4a0f6c676aa1b1a2105a4aed140` on `master`.
- Accepted Phase 1 code: `7aa6b47510f0c51cd959b54b0d46827a7266dba0`.
- Phase 2 code commit: `f1cde63f06a10f93ac8fa6391c0f678af9600ab1`.
- Initial worktree/index: clean; history and accepted review independently inspected.
- Prior mailbox: [accepted Phase 1 review](handoffs/phase-1-review-fixes.md).
- Protocol: [thread workflow](thread-workflow.md), [Git workflow](git-workflow.md).

## Requested scope and implementation report

Phase 2 only. Delivered PUBLIC/PRIVATE community lifecycle/discovery/settings/
archive, OPEN/REQUEST/INVITE_ONLY membership admission, invitations/requests and
collision/history, leave/removal/rejoin, current role hierarchy/transfer, member
suspend/ban/unban, policy and shared/exclusive guards, attributable moderation
action/audit/outbox, V0009–V0011, published community APIs/views, optional resource
idempotency and existing-worker invite expiry. Identity closure integrates the
active-community ownership blocker under its account guard.

Owner exists solely as community.owner_user_id, backed by deferred membership FK
and a narrow commit trigger; former owner becomes ADMIN in transfer. Invitations
grant MEMBER only; expiry/suspension is enforced live without cleanup. UNBAN leaves
REMOVED/MEMBER. Archive preserves authorized history and denies activity except
the detailed domain's self-leave exception, including retained owner.

Implementation evidence: [Phase 2](phases/phase-2.md).
Exact semantics: [community subsystem](community.md),
[OpenAPI](api/openapi.yaml), [operations](../ops/community.md).
52 task-owned files committed, no dependency introduction or future module scaffold.
No subagents, external contact, push, merge or deployment.

## Reviewer verdict and findings

Awaiting first main review of `f1cde63`; developer does not accept the phase.
No Phase 2 reviewer findings yet. Main should record stable IDs with source/
scenario/required outcome/test expectation and retain them through review rounds.

Phase 1 R1–R3 are preserved with original findings/responses/main dispositions and
evidence in the accepted archive; only relative links were rebased to the new
directory. No old finding was reopened or independently marked resolved by development.

## Developer refinements

- Archive question resolved by the repository's documented precedence: the
  detailed domain contract explicitly permits owner leave after archive; general
  read-only wording receives this narrow self-leave exception. No user approval
  is claimed and no accepted decision is changed. Join/settings/admission/roles/
  sanctions/transfer remain denied in ARCHIVED.
- New invite cancels older pending request; new request revokes/marks expired
  older invitation under the same exclusive guard, preserving history.
- Owned JDBC snapshots and explicit guarded versions handle composite membership/
  admission without a second JPA model; existing Hibernate mappings stay validated.
- Community ETag conservatively increments across effective user transitions.
  Optional 24h idempotency stores only hashes/logical UUID, rechecks current access.
  Stable slug bounds, 365-day suspension ceiling and bounded offset lists are
  documented in subsystem/schema/OpenAPI. No superseding ADR needed.

## Actual checks — development, 2026-10-10

Java 21 used via `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`; Docker available.
Commands and detailed named coverage are in the phase record.

- Final targeted policy/architecture + CommunityIT/FoundationIT: BUILD SUCCESS,
  11 unit/architecture + 36 real PostgreSQL integration = 47 tests, zero
  failures/errors/skips.
- `./mvnw -B -ntp spotless:apply verify`: BUILD SUCCESS, **29 unit/architecture
  + 70 integration = 99 tests**, zero failures/errors/skips. PostgreSQL 18.6,
  Mailpit/provider tests, formatting, Enforcer and dependency convergence run.
- Empty/V0001/V0008 migration upgrade, all eight prior checksums, retained Phase 1
  account, owner commit-time FK/trigger, view columns and SQL/object parity pass.
- Barriers/latches/separate transactions cover duplicate join/accept, request/
  invite collision, owner transfer vs leave/removal/closure, creation vs closure,
  both shared community-write/revocation orders, mandatory audit rollback and
  DB job rollback/fencing/crash reclaim/two workers/ten-attempt DEAD retention.
- Synthetic 10,000-member plan uses membership_pkey; full-verify point-query
  execution 0.029 ms (one local sample, no production capacity claim).
- OpenAPI 3.1 validator 0.7.2, Compose config, local Markdown links and whitespace
  pass. Gitleaks 8.28.0 pinned image scans exact final staged source, no leaks.
- `docker build --tag social-core:phase2 .` passes. Isolated non-root/read-only
  app + PostgreSQL/Mailpit HTTP smoke passes actual SMTP registration, browser
  CSRF/current Bearer, private cards/IDOR, requests, roles, ban/unban, invite retry,
  active ownership closure blocker, transfer/archive and archived owner leave.
  Smoke containers/network/ephemeral synthetic DB data removed; usual Compose
  stack, local volumes and mounted keys preserved.
- Logs are temporary `/tmp/social-core-phase2-*.log`, not repository artifacts.
  Earlier failed implementation/fixture attempts were corrected; only final
  successful runs above count as completion evidence.
- After code commit only this mailbox changes; appropriate link/diff checks are
  run for the documentation handoff, without a redundant executable suite.

## Limitations and external gates

No known local implementation/test/commit blocker. Main review remains required.
Remote CI and live provider/production SMTP/TLS/key/runtime roles/MFA/backup/
capacity/retention checks are not run here. Phase 1 provider gates remain unchanged;
full erasure/release readiness is Phase 9A. Offset pagination is bounded and not a
cross-request snapshot. Read authorization follows documented weak consistency.
Expiry cleanup can lag safely; DEAD work requires restricted attributable operator
repair. No public launch or production-readiness claim.

## Exact next action

Main reviewer reads contracts, verifies clean Git/code baseline and reviews
`f1cde63f06a10f93ac8fa6391c0f678af9600ab1` with the evidence above, reruns checks
as appropriate, and records findings/verdict in this fixed mailbox. Development
waits for review findings. Do not start Phase 3, push or deploy from this handoff.
