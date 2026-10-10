# Phase 2 — Communities, membership and scoped authority

Date: 2026-10-10. Development implementation complete locally and verified;
review status ACCEPTED after round 2 on fix commit `1663507`. Baseline `306465b` on master,
accepted Phase 1 code `7aa6b47`; initial index/worktree clean. Prior findings and
evidence preserved in [Phase 1 archive](../handoffs/phase-1-review-fixes.md), with
relative links rebased for its new directory. No subagents, push or deployment.
Real code commit and next owner are recorded in [mailbox](../agent-handoff.md).

## Delivered scope

PUBLIC/PRIVATE communities, stable slug/settings, default PRIVATE+INVITE_ONLY,
minimal authenticated private discovery, create/read/edit/archive, all OPEN/
REQUEST/INVITE_ONLY admission modes (including PRIVATE+OPEN), retained membership
ACTIVE/LEFT/REMOVED/BANNED lifecycle, temporary suspension with live expiry,
existing-user invitations and seven-day expiry, join request approval/rejection/
cancellation, explicit collision resolution and bounded history lists.

Current CommunityPermissionPolicy enforces MEMBER/MODERATOR/ADMIN/derived OWNER
hierarchy, self/equal/superior/owner denial, role changes and atomic ownership
transfer with former owner ADMIN. Identity closure blocks active-community
ownership while serialized against creation/transfer. Archive denies activity
and preserves authorized history; the explicit detailed-domain self-leave
exception includes retained owner. UNBAN yields REMOVED/MEMBER, never rejoining.

Moderation's member-only actions commit membership, private attributable reason/
before-after action, scoped append-only audit and minimal outbox atomically.
Community APIs publish current facts and transaction-required shared write guards;
transitions hold exclusive community guards after sorted shared account guards.
No source permissions are cached. HTTP retains current session/Bearer, CSRF/Origin,
input/body/rate bounds, strict JSON allowlisting, version preconditions and safe
errors. Optional 24-hour resource-reference idempotency covers create/invite/request;
domain uniqueness and live permission checks remain authoritative.

The existing worker schedules deduplicated invite expiry. DB handler + fenced ack
commit together; rollback, duplicate execution, expired lease/crash reclaim and
bounded retry/DEAD handling are tested. Mail claims remain isolated from DB jobs.
Queue/failure metrics and an operator recovery runbook cover the new kind.

## Schema and published contracts

| Migration | Owned state |
|---|---|
| V0009 community_membership | Communities, memberships, invitations, requests, owner deferred FK/commit trigger, community_api v1 views |
| V0010 moderation_member_actions | Append-only member actions and scoped traversal indexes; no future reports table |
| V0011 platform_resource_idempotency | Actor/route/key/request hashes, logical UUID and expiry; no cached bodies/secrets |

V0001–V0008 unchanged. Empty-schema, actual V0001-only and actual V0008-only
upgrades/checksums pass on PostgreSQL 18.6. Existing Phase 1 account survives
upgrade. Commit-time tests prove invalid owner leave/suspension/FK changes roll
back; creation/transfer succeeds atomically. Runtime SQL ownership/ArchUnit rules
pass without exclusions. Source views expose only documented IDs/status/roles;
view role is persisted role, effective OWNER derives from owner pointer.

Published APIs: CommunityCommands, MembershipCommands, AdmissionCommands,
MembershipQueries; producer event records CommunityTransition/ModerationActionApplied;
platform Idempotency/JobHandler and scoped audit/job extensions to DurableWork.
No new dependencies or external service. Query/transition persistence uses owned
JDBC snapshots and guarded explicit versions, avoiding duplicate JPA representations
for composite membership/admission state; account/profile remain Hibernate validated.
Batched membership facts issue two bounded queries for <=100 community IDs.

Exact route/DTO/ETag definitions are in [OpenAPI](../api/openapi.yaml);
transition/guard semantics in [community subsystem](../community.md), concrete
DDL refinements in [database design](../database-design.md), worker/limits in
[community operations](../../ops/community.md).

## Executed verification

This section records the initial implementation checks. The later R1–R2 repair
round and its independently executed checks are recorded below; the original
99-test evidence must not be confused with the current 107-test suite.

All Maven commands use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (shell default
is not the build's required JDK). Docker/Testcontainers run, no skipped ITs.

```bash
./mvnw -B -ntp spotless:apply test \
  '-Dtest=CommunityPolicyTest,ArchitectureTest,PersistenceBoundaryTest' \
  failsafe:integration-test failsafe:verify '-Dit.test=CommunityIT,FoundationIT'
./mvnw -B -ntp spotless:apply verify
```

| Check | Actual result |
|---|---|
| Final targeted suite | BUILD SUCCESS: 11 unit/architecture + 36 PostgreSQL integration = 47; no failures/errors/skips |
| Full spotless:apply verify | BUILD SUCCESS: 29 unit/architecture + 70 integration = **99 tests**; no failures/errors/skips; formatting/Enforcer/dependency convergence pass |
| Migration/ownership/view contracts | Empty/V0001/V0008 upgrade, eight prior checksums, deferred owner FK/trigger, exact view columns and SQL/object read parity pass |
| Query plan | Synthetic 10,000-member community: membership_pkey point lookup; full-verify actual query execution 0.029 ms, bounded member page 100 |
| OpenAPI 3.1 validator 0.7.2 | Passed |
| docker compose config --quiet | Passed |
| Docker image build | social-core:phase2 built with pinned Java 21, non-root runtime; packaging skipTests does not replace host verify |
| Isolated container HTTP smoke | Passed: real SMTP registration/current browser CSRF/Bearer, private discovery/IDOR, request approval, roles, ban/unban, invite accept/retry, ownership closure blocker, transfer/archive and archived owner self-leave |
| Markdown links / whitespace | 139 local links checked, none broken; git diff --check passed |
| Pinned Gitleaks 8.28.0 | Exact staged source snapshot scanned successfully, no leaks; ignored keys/build artifacts excluded |

Local logs: `/tmp/social-core-phase2-targeted.log`, `-verify.log`, `-docker-build.log`,
`-http-smoke.log`. Smoke used its own Compose project/random localhost ports and
ephemeral PostgreSQL container data, with existing local keys mounted read-only;
the isolated app/PostgreSQL/Mailpit containers and network were removed afterward.
The developer's usual Compose stack, volumes and keys were preserved.
These are temporary artifacts, not committed evidence files. Initial compilation/
fixture/implementation failures were corrected before the successful final runs;
the final table does not claim those earlier attempts passed.

Named regressions cover duplicate join/accept, request/invitation collision,
mode changes and staff review, live expiry/replacement, banned acceptance and
unban, role/sanction hierarchy, IDOR/invalid versions/states, audit rollback,
owner transfer vs successor leave/removal, creation/transfer vs account closure,
both shared-write guard/revocation orderings, job transactional rollback/lease
loss/reclaim/two workers/retry-to-DEAD and real HTTP endpoints. Pure domain tests
cover ban reactivation denial and owner self-leave after archive. Existing Phase 1
R1–R3, refresh/provider/CSRF/current status, PostgreSQL/Mailpit and durable work tests
all pass without changing their security expectations.

## Main review R1–R2 repair round — 2026-10-10

Resumed at clean `b008fba` on master; this mailbox-only review follows `70179b1`
and reviewed implementation `f1cde63`. Original reviewer findings/dispositions
remain in [mailbox](../agent-handoff.md); development proposes fixes, not acceptance.
The real fix hash and next owner are recorded there after the code commit.

R1: claim is now a single INSERT/ON CONFLICT UPDATE/RETURNING. It locks the key,
renews only at/past expiry and preserves all logical fields on live replay,
including the original 24-hour expiry. It cannot lose a conflicting expired row
between INSERT and SELECT because that gap no longer exists. Prune locks at most
500 expired candidates in deterministic order with SKIP LOCKED, then deletes only
still-expired rows. Claimed/renewing records are skipped, not waited on or deleted.
Each live replay performs a logically unchanged row update to return/lock current
state atomically; this is a deliberate bounded PostgreSQL implementation choice.
Mutation/reference storage, current policy checks and changed-body 409 remain intact.

R2: explicit community/membership/admission event helpers replace membership-based
status inference. CommunityCreated/Updated reads post-transition community status;
membership/admission events retain their own source semantics. Published v1 field
shape is unchanged; [event documentation](../events-and-jobs.md) and producer
record JavaDoc now specify status, sourceId, subject and version meaning. No
migration, dependency, HTTP/OpenAPI change, new consumer or Phase 3 work.

Eight permanent CommunityIT regressions were added:

- `pruneSkipsRenewingIdempotencyAndPreservesReplayWindow`
- `idempotencyClaimSurvivesCleanupAtStatementBoundary`
- `claimWaitsForCleanupDeletionThenCreatesOneReplayableResource`
- `pruneDeletesOnlyExpiredUnusedKeysInBatchesOfFiveHundred`
- `renewedAdmissionKeysReplayTerminalReferencesWithoutExtraEventsOrJobs`
- `lifecycleEventsDescribeCommittedCommunityStateAndArchiveRetryIsSilent`
- `membershipAndAdmissionEventsKeepTheirOwnSourceStatusesAndVersions`
- `rollbackKeepsSourceAndCorrectLifecycleMembershipAdmissionEventsAtomic`

Before production edits, the first, second and sixth regressions ran against the
reviewed code on real PostgreSQL 18.6: **3 tests, 2 assertion failures, 1 error,
0 skips**. Renewed key count was 0 instead of 1; archive payload was ACTIVE instead
of ARCHIVED; the claim interleaving raised EmptyResultDataAccessException. That
error is the reproduced application defect, not infrastructure failure. The first
attempt was interrupted before test results and was rerun; only the completed
reproduction counts. After fixes those same three tests passed, then all eight
passed in the targeted and full suites below. Latches, real SQL statement-boundary
instrumentation and pg_blocking_pids arrange interleavings; no sleep or fabricated
SQL/lock result. Tests inspect committed references/payloads and repeat commands to
prove no extra resource/event/job and unchanged live expiry; rollback checks include
idempotency and scheduled job removal. Existing authority/body-denial tests remain.

Commands use Java 21 and real Testcontainers infrastructure:

```bash
./mvnw -B -ntp spotless:apply test-compile \
  failsafe:integration-test failsafe:verify \
  '-Dit.test=CommunityIT#pruneSkipsRenewingIdempotencyAndPreservesReplayWindow+idempotencyClaimSurvivesCleanupAtStatementBoundary+lifecycleEventsDescribeCommittedCommunityStateAndArchiveRetryIsSilent'
./mvnw -B -ntp spotless:apply test \
  '-Dtest=CommunityPolicyTest,ArchitectureTest,PersistenceBoundaryTest' \
  failsafe:integration-test failsafe:verify '-Dit.test=CommunityIT,FoundationIT'
./mvnw -B -ntp verify
```

- Final targeted: BUILD SUCCESS, 11 unit/architecture + 44 integration = **55**,
  zero failures/errors/skips (CommunityIT 34, FoundationIT 10).
- Full verify: BUILD SUCCESS, 29 unit/architecture + 78 integration = **107**,
  zero failures/errors/skips, formatting/Enforcer/dependency convergence pass;
  PostgreSQL migration/owner/view/concurrency and Mailpit/provider tests run.
- Logs: `/tmp/social-core-phase2-r1-r2-red.log`, `-green-initial.log`, `-targeted.log`,
  `-verify.log`; local temporary evidence, not repository artifacts.
- Local Markdown links and staged/working whitespace checks pass. Source and
  contract changes are task-scoped; migrations V0001–V0011 unchanged.
- Pinned Gitleaks 8.28.0 scanned the staged index-exported source snapshot: no
  leaks. Log: `/tmp/social-core-phase2-r1-r2-gitleaks.log`; ignored keys/build
  artifacts excluded. Only this evidence sentence is added afterward.

Docker image/isolated HTTP smoke, OpenAPI validator and remote CI are not rerun in
this repair round; no container/HTTP/spec shape change. Initial implementation
operational evidence remains historical, not claimed as rerun. Existing outbox
history is not rewritten; no consumer is enabled and future projections must
backfill/reconcile current source before rollout. At repair handoff, main re-review
was pending; the verdict below supersedes that status. Previously documented
provider/production readiness gates remain. No local fix/test blocker.

## Main review acceptance — 2026-10-10

Main reviewer accepts Phase 2 at `1663507a7c00e2fb472192c84e1a82bf2a17bac6`;
R1–R2 are RESOLVED with no new blocking finding in the changed code. Clean review
HEAD `60d881d` was mailbox-only after that fix. Independently reviewed atomic claim/
bounded locking prune, source-specific event producers, eight permanent regressions
and the updated contracts. Independently ran Java 21 `./mvnw -B -ntp verify`:
**107 tests (29 unit/architecture + 78 integration), zero failures/errors/skips**.
Full evidence and retained findings/responses are in the [mailbox](../agent-handoff.md).
No executable changes were made during re-review. Container smoke, remote CI and
external production/provider checks were not rerun. Local acceptance does not
remove those gates or assign Phase 3 automatically.

## Refinements and remaining gates

No accepted architecture/product/security decision changed; no superseding ADR.
The archive self-leave detail follows domain-model and implementation-handoff's
subsystem precedence over the general read-only shorthand; documented explicitly.
Collision choice, slug bounds, conservative community ETag, optional idempotency,
JDBC snapshots and fixed 365-day suspension ceiling are routine refinements.

The 10,000-member EXPLAIN is one local point-query measurement, not production
latency/load capacity evidence. Small lists use bounded offset, no totals/cursor
or cross-request snapshot. PostgreSQL is current authority; in-flight responses
remain subject to documented weak read consistency. Invitation cleanup can lag
without granting expired access. DEAD jobs require restricted attributable operator
repair. Full erasure/admission retention is a Phase 9A gate, not delivered now.

Live Google/Facebook approval/smoke, production SMTP/TLS/key/roles, shared limiter
before multiple public instances, operator MFA, backup restore, capacity/retention
and public-launch readiness remain external gates. Remote CI has not been run.
No Phase 3 graph/content/search/notification/tenant feature or new infrastructure.
Next owner is main/user for the next task assignment; no automatic Phase 3 start.
