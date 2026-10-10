# Agent handoff — Phase 2

## Routing

- Task: phase-2-communities
- Round: 2 — development response to first main review
- State: READY_FOR_REVIEW
- Last writer: development
- Next owner: main reviewer — re-review Phase 2 R1–R2 proposed fixes
- User assignment: 2026-10-10; explicitly authorized Phase 2 implementation.
- Baseline HEAD: `306465b635a2f4a0f6c676aa1b1a2105a4aed140` on `master`.
- Accepted Phase 1 code: `7aa6b47510f0c51cd959b54b0d46827a7266dba0`.
- Original Phase 2 code commit: `f1cde63f06a10f93ac8fa6391c0f678af9600ab1`.
- Current Phase 2 fix commit: `1663507a7c00e2fb472192c84e1a82bf2a17bac6`.
- Repair baseline: `b008fba6314082f80750c67163a8ec4080d5dc3c` (mailbox-only review),
  clean worktree/index on `master`; reviewed production code still `f1cde63`.
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

Round 2 changes only R1–R2 and necessary tests/contracts: atomic idempotency claim,
concurrency-safe bounded prune and source-specific event status, with eight new
permanent PostgreSQL regressions. Fourteen task-owned fix files committed; no
migration/dependency/HTTP shape change or Phase 3. This separate mailbox commit
contains no further executable changes.

## Reviewer verdict and findings

CHANGES_REQUESTED after first main review of
`f1cde63f06a10f93ac8fa6391c0f678af9600ab1`. The original 99-test suite passes,
but two additional reviewer probes reproduce the defects below on real PostgreSQL.
Phase 2 is not accepted; do not start Phase 3. No application fix was made during
review. Findings are scoped to Phase 2; their IDs are separate from the archived
Phase 1 findings.

Phase 1 R1–R3 are preserved with original findings/responses/main dispositions and
evidence in the accepted archive; only relative links were rebased to the new
directory. No old finding was reopened or independently marked resolved by development.

### R1 — P2: expiry cleanup deletes a freshly renewed idempotency record

- Location: [DurableWorkStore.java](../src/main/java/com/flyvo/socialcore/platform/infrastructure/persistence/DurableWorkStore.java),
  `prune`, lines 382–384; renewal is in
  [ResourceIdempotency.java](../src/main/java/com/flyvo/socialcore/platform/infrastructure/persistence/ResourceIdempotency.java),
  `claim`, lines 26–55 at the reviewed commit.
- Failure: an expired key is reused in transaction A, which updates its request
  hash/reference and expiry to a new 24-hour window. Before A commits, cleanup B
  sees the old expired row in its statement snapshot and selects its primary key,
  then waits for A's row lock. After A commits, the DELETE's outer predicate checks
  only that primary key, not current expiry, and deletes the renewed record.
  A successful command therefore loses its promised replay window. A same-body
  community-create retry tries to insert the existing slug and returns conflict
  instead of the original resource; changed-body reuse also loses its conflict
  protection. Other admission retries can create new logical resources after the
  old admission reaches a terminal state.
- Reviewer reproduction: `reviewProbePruneMustPreserveRenewedIdempotencyRecord`.
  Create with a key; advance injected clock past 24 hours; reuse that key with a
  new slug inside a held transaction; run real `work.prune()` in another transaction.
  Observe its actual PostgreSQL lock wait with `pg_blocking_pids`, then release
  renewal to commit. Expected one unexpired record; actual **zero**. One assertion
  failure, zero errors/skips. Latches and bounded lock-wait observation; no sleeps,
  mocked persistence, or production-code modifications.
- Required outcome: cleanup must not delete an in-use or renewed record. Use a
  bounded, concurrency-safe claim/delete protocol (for example locking selected
  expired rows with SKIP LOCKED plus current-expiry protection). Also ensure the
  claim INSERT/SELECT sequence handles cleanup removing an expired conflicting row
  between those statements without an unhandled empty-row failure. Preserve atomic
  mutation/reference storage, current authorization, 24-hour reuse and body mismatch.
- Regression: deterministic PostgreSQL renewal-versus-prune and
  prune-versus-claim interleavings. Assert the renewed reference survives, same-body
  retries return that UUID without extra resource/event/job, changed body is 409,
  and genuinely expired unused records are still pruned in bounded batches.
- Disposition: OPEN. Development should append a PROPOSED_FIXED response with
  code hash, regression names/results and limitations; main reviewer closes it.

### R2 — P2: archiving publishes ACTIVE in the CommunityUpdated event

- Location: [CommunityAccess.java](../src/main/java/com/flyvo/socialcore/community/application/CommunityAccess.java),
  `changed`, lines 97–99; called by
  [CommunityLifecycle.java](../src/main/java/com/flyvo/socialcore/community/application/CommunityLifecycle.java),
  `archive`, lines 145–147 at the reviewed commit.
- Failure: the shared event helper takes `status` from the initiating user's
  membership whenever it exists. Archive changes the community to ARCHIVED while
  its owner remains an ACTIVE member, so the committed CommunityUpdated payload
  carries `status: ACTIVE` with the new community version. The published durable
  fact contradicts the source transition. A later version-aware projection/replay
  cannot treat that status as the community's state. No production consumer is
  enabled yet, so this is a producer-contract defect, not a demonstrated current
  HTTP authorization bypass.
- Reviewer reproduction: `reviewProbeArchiveEventMustDescribeCommittedCommunityStatus`.
  Create a community, archive through the actual command, confirm its returned
  status is ARCHIVED, then query the committed CommunityUpdated outbox JSON.
  Expected ARCHIVED; actual **ACTIVE**. One assertion failure, zero errors/skips.
- Required outcome: community lifecycle events derive community state from the
  post-transition source; membership/admission events retain their respective
  source semantics. Make event-specific status meaning explicit rather than
  inferring it from whether the actor happens to have a membership. Update the
  published event documentation if its shape/meaning changes; no future consumer
  implementation is requested.
- Regression: assert persisted payload status/source/version for create, settings,
  archive, membership and admission transitions, including archive retry producing
  no duplicate transition. Verify source mutation and its correct event remain
  atomic on rollback.
- Disposition: OPEN. Development should append a PROPOSED_FIXED response with
  code hash, regression names/results and limitations; main reviewer closes it.

## Developer responses — round 2, 2026-10-10

### R1 — PROPOSED_FIXED (reviewer disposition remains OPEN)

- Fix commit: `1663507a7c00e2fb472192c84e1a82bf2a17bac6`.
- ResourceIdempotency.claim now inserts/locks/renews/returns with one PostgreSQL
  UPSERT/RETURNING, eliminating the cleanup-sensitive INSERT/SELECT sequence.
  At/past expiry it renews hash/expiry and clears the old reference; live replay
  preserves every logical field and original expiry. Mutation/reference remain
  atomic under this key's row lock; current authority and body mismatch remain
  enforced. Live replay performs a logically unchanged UPDATE to obtain the row
  atomically, a deliberate PostgreSQL implementation choice, not cached authority.
- DurableWorkStore.prune locks at most 500 expired candidates with FOR UPDATE
  SKIP LOCKED in deterministic expiry/key order; DELETE also checks current expiry.
  It skips in-use/renewing records and still removes genuinely expired unused keys.
- Reproduced before production edits: `pruneSkipsRenewingIdempotencyAndPreservesReplayWindow`
  fails with renewed record count 0; `idempotencyClaimSurvivesCleanupAtStatementBoundary`
  raises EmptyResultDataAccessException. Both are application failures on real
  PostgreSQL, not mocked persistence or infrastructure errors. Both pass after fix.
- Additional permanent regressions: `claimWaitsForCleanupDeletionThenCreatesOneReplayableResource`,
  `pruneDeletesOnlyExpiredUnusedKeysInBatchesOfFiveHundred`, and
  `renewedAdmissionKeysReplayTerminalReferencesWithoutExtraEventsOrJobs`.
  Latches/real statement instrumentation/pg_blocking_pids establish renewal-first,
  cleanup-at-boundary and cleanup-first interleavings; no sleeps/fake SQL results.
  Assert retained UUID, same-body replay, changed-body 409, exact expiry boundary,
  unchanged live expiry, no extra resources/events/jobs and terminal admission replay.
- Results: all five R1 regressions pass in targeted and full suites below.
  No schema rewrite/new infrastructure; contended cleanup can safely lag.

### R2 — PROPOSED_FIXED (reviewer disposition remains OPEN)

- Fix commit: `1663507a7c00e2fb472192c84e1a82bf2a17bac6`.
- CommunityAccess now has explicit community/membership/admission producer helpers.
  Lifecycle events read committed post-transition community status (ARCHIVED on
  archive); membership events read subject membership status, admission events
  retain invitation/request status. Envelope uses current community version.
  Actor membership no longer decides the meaning of the event's status.
- Producer record JavaDoc and [event contract](events-and-jobs.md) explicitly define
  each status/sourceId/subject and envelope/membership version. V1 payload shape
  unchanged; no future consumer implemented.
- `lifecycleEventsDescribeCommittedCommunityStateAndArchiveRetryIsSilent`
  reproduced ACTIVE instead of ARCHIVED before production edits, then passes.
  It asserts persisted create/settings/archive status/source/version and archive
  retry emits no duplicate event. Additional regressions:
  `membershipAndAdmissionEventsKeepTheirOwnSourceStatusesAndVersions` and
  `rollbackKeepsSourceAndCorrectLifecycleMembershipAdmissionEventsAtomic`.
  They check join/role/leave/suspend/ban/unban/owner transfer, invite/create/accept,
  request/create/approve, membership source metadata and rollback of source/event/
  idempotency/job together. Existing mandatory audit failure regression still passes.
- Results: all three R2 regressions pass in targeted and full suites below.
  Historical outbox payloads are not rewritten; no consumer is enabled and future
  projections must backfill/reconcile authoritative source before rollout.

## Developer refinements

Development resumed R1–R2 on 2026-10-10 at `b008fba` on `master`, clean
worktree/index. That review commit changed only the mailbox; the reviewed executable
baseline was `f1cde63`. Original findings/dispositions above remain untouched.
Regression reproduction precedes fixes; scope is idempotency cleanup/claim and
source-correct community events only, with no Phase 3 or schema redesign.

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

## Actual checks — initial development, 2026-10-10

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

## Actual checks — R1–R2 repair round, 2026-10-10

- Before fixes: completed three-test PostgreSQL reproduction, 2 assertion failures
  (renewed record deleted / archive payload ACTIVE), 1 application empty-row error,
  zero skips. An interrupted earlier attempt produced no results and was rerun.
  After fixes those same three pass; no production fix preceded reproduction.
- Targeted `./mvnw -B -ntp spotless:apply test
  '-Dtest=CommunityPolicyTest,ArchitectureTest,PersistenceBoundaryTest'
  failsafe:integration-test failsafe:verify '-Dit.test=CommunityIT,FoundationIT'`:
  BUILD SUCCESS, **11 unit/architecture + 44 integration = 55 tests**, zero
  failures/errors/skips. CommunityIT now 34 (eight added regressions), FoundationIT 10.
- `./mvnw -B -ntp verify`: BUILD SUCCESS, **29 unit/architecture + 78 integration
  = 107 tests**, zero failures/errors/skips. Java 21, PostgreSQL 18.6/Mailpit/provider
  tests, formatting, Enforcer/dependency convergence and architecture checks ran.
  Migration/owner/view contracts and prior Phase 1 security regressions pass unchanged.
- Local Markdown links/working and staged whitespace checks pass; exact staged
  source snapshot scanned by pinned Gitleaks 8.28.0, no leaks. Ignored keys/build
  artifacts excluded. Only evidence text was added after that scan, no new code.
- Exact commands/results/limitations: [repair evidence](phases/phase-2.md).
  Logs `/tmp/social-core-phase2-r1-r2-red.log`, `-green-initial.log`, `-targeted.log`,
  `-verify.log`, `-gitleaks.log` are temporary local artifacts, not committed.
- Docker build/isolated HTTP smoke/OpenAPI validator and remote CI were not rerun
  in this repair round; no image configuration/HTTP/spec shape change. Prior results
  above remain attributed to initial implementation, not claimed as fresh checks.
- After fix commit only this mailbox changes; link/diff checks run for its separate
  documentation commit. No acceptance, external messaging, push, deploy or Phase 3.

## Limitations and external gates

No known local implementation/test/commit blocker. Main review remains required.
Remote CI and live provider/production SMTP/TLS/key/runtime roles/MFA/backup/
capacity/retention checks are not run here. Phase 1 provider gates remain unchanged;
full erasure/release readiness is Phase 9A. Offset pagination is bounded and not a
cross-request snapshot. Read authorization follows documented weak consistency.
Expiry cleanup can lag safely; DEAD work requires restricted attributable operator
repair. No public launch or production-readiness claim.

## Exact next action

Main reviewer re-reviews `1663507a7c00e2fb472192c84e1a82bf2a17bac6` against preserved
R1–R2 and the PROPOSED_FIXED responses, independently checks PostgreSQL regressions/
verify and updates original dispositions/verdict. Development has not closed either
finding or accepted Phase 2. Current state READY_FOR_REVIEW; no Phase 3, push or deploy.

## Main review checks and limitations — 2026-10-10

- Initial checkout/index clean. HEAD `70179b1` is mailbox-only after reviewed code
  `f1cde63`; no newer executable changes. Read repository contracts, Phase 2 scope,
  implementation evidence, production diff and relevant regression tests.
- Reviewed community discovery/access, admission lifecycle and collision handling,
  role/sanction hierarchy, ownership/closure guards, migrations/owner constraints,
  source views, HTTP boundaries, moderation durability, idempotency and worker changes.
- Independently ran Java 21 `./mvnw -B -ntp verify`: BUILD SUCCESS, **29 unit/
  architecture + 70 integration = 99 tests**, zero failures/errors/skips. Formatting,
  Enforcer, PostgreSQL migration/concurrency/API tests and Mailpit/provider tests ran.
  Log: `/tmp/social-core-phase2-review-verify.log` (local temporary artifact).
- Added temporary diagnostic methods to CommunityIT and executed each with
  `./mvnw -B -ntp spotless:apply test-compile failsafe:integration-test failsafe:verify
  '-Dit.test=CommunityIT#<probe-name>'`. R1 and R2 each produced the assertion failure
  documented above, not an infrastructure error. The first R1 command was stopped
  by formatting before test execution; formatting was corrected before the actual
  reproduction. Logs: `/tmp/social-core-phase2-review-probe.log` and
  `/tmp/social-core-phase2-review-event-probe.log`.
- Both diagnostic methods were removed after execution; `git diff --exit-code -- src`
  confirmed source/tests exactly match the reviewed commit. A local reusable patch
  is `/tmp/social-core-phase2-review-probes.patch`; it and logs are temporary aids,
  not required permanent evidence. Reproduction procedures above are self-contained.
  Current target/failsafe reports reflect the later intentionally failing probes;
  they must not be mistaken for the earlier full-suite result.
- Only review mailbox documentation is delivered. Relative file links and
  `git diff --check` checked before commit. Docker image/HTTP smoke, OpenAPI validator,
  Gitleaks, remote CI and live production/provider checks were not rerun by reviewer;
  their prior developer evidence remains attributed above. External gates unchanged.
- No new authorization bypass identified in this scoped review; that is not a
  production security audit or a guarantee that no other defects exist.
