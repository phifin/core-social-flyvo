# Agent handoff — Phase 4

## Routing

- Task: phase-4-posts-and-secure-images
- Round: 1
- State: READY_FOR_IMPLEMENTATION
- Last writer: main orchestrator, 2026-10-10 — storage decision recorded
- Next owner: development chat `Develop Phase 4 — Posts & Media` — resume assigned task
- Assignment: user requested checking completed review and continuing the next work.
- Baseline: `552c4ca99692aba277b6fee6bb91b23962d65787`; clean checkout/index before coordination.
- Accepted Phase 3 code: `bfda6e856d42282eaccd2ed70a81f49c416f86b7`.
- Phase 4 decision commit: `ab568554cf50cfa5c2024e84c90a16ed234a967c` (documentation only).
- Prior verdict/findings/checks: [accepted Phase 3](handoffs/phase-3-social-graph.md).
- Protocol: [thread workflow](thread-workflow.md), [Git workflow](git-workflow.md).

## Scope and implementation instructions

Implement Phase 4 exactly as specified in [roadmap](roadmap.md#phase-4--publishable-posts-with-secure-image-attachments):
personal/community posts, audiences including CUSTOM, mentions/hashtags, revisions
and explicit audience edits, owner soft deletion and basic community remove/restore;
secure image upload/finalize/process/status/bind and avatar lifecycle. Include
ContentAccessPolicy, SQL candidate/hydration building blocks, guarded writes and a
basic paginated post query. This is not a media-only task.

Read AGENTS.md, README.md, implementation-handoff.md, architecture.md, security.md,
domain-model.md, database-design.md, media.md, events-and-jobs.md, API conventions,
testing strategy, and applicable ADRs (especially 002, 003, 005, 008, 009) before
implementation. Inspect existing module APIs/guards/worker jobs and dependency
introduction matrix. Follow the authoritative contracts rather than this summary
where more detailed requirements apply.

Required infrastructure is the specified S3 adapter, pinned Compose/Testcontainers
PGSTY Silo selected in accepted [ADR-013](adr/013-local-object-storage-distribution.md)
with private/versioned storage, bounded image processor, quotas and durable cleanup.
No network/storage calls under business locks, arbitrary key signing, unpinned
quarantine reads or public original files. Owner content/profile policy authorizes
variant issuance; media does not call those modules. Keep existing module DAG.
Explain new dependencies and verify storage behavior against real local storage.
Production S3 compatibility remains a distinct deployment gate when not exercised.

No Phase 5 comments/reactions/reports workflow, full home feed, search,
notifications, video/documents/CDN or speculative future modules. Do not silently
change product/security/architecture decisions; raise conflicts with a concrete
proposed ADR change.

## Development plan and delivery

1. Verify baseline/index, set IN_PROGRESS, inspect contracts and record a concrete
   scoped implementation plan before edits. Only this development chat writes code.
2. Implement coherent, reviewable slices; preserve existing migrations, add new
   Flyway migrations and use owner APIs/published allowlisted views only.
3. Test all Phase 4 acceptance/edge cases, especially audience SQL/object parity,
   current history/media authorization, blocked users/community departure,
   version/idempotency conflicts, atomic ten-image binding and concurrent reuse,
   overwrite-after-finalize, stale generation fencing, partial output failure,
   attach/cleanup races, quotas, avatar replacement and metadata stripping.
4. Run targeted real PostgreSQL/storage checks, formatting and full ./mvnw verify;
   report actual operational checks and remaining external gates honestly.
5. Update implemented OpenAPI, schema/domain/subsystem/operations docs and
   docs/phases/phase-4.md. Commit verified task-owned work using git-workflow.md.
6. Set READY_FOR_REVIEW with real code commit, checks, limitations and next owner
   main in this mailbox; commit handoff separately. Do not self-accept or push.

### Concrete development plan (round 1)

Execution baseline is `af6082b` (assignment-only documentation after the recorded
accepted baseline); checkout/index were clean. Implement in these internal slices:

1. Extend existing published account/community/profile APIs only where needed for
   batched current content facts, staff guards and attribution. Add a platform
   external-job prepare/effect/ack boundary: claim/ack transactions are short,
   storage effects run without business locks, and lease/generation fencing guards
   every READY/deletion commit. Reuse current jobs, outbox, audit and idempotency.
2. Add new globally ordered Flyway migrations after V0014 for media assets,
   variants, quota and binding; post/recipient/revision/attachment/mention/hashtag;
   profile avatar FK; and post-target moderation actions. Keep existing migrations
   unchanged. Preserve the module DAG and existing SQL-view consumer allowlist.
3. Implement personal/community publication, explicit CUSTOM recipients, validated
   text/link/mention/hashtag data, immutable revisions, versioned body/attachment
   edits and separate audience commands, owner deletion, and attributable scoped
   remove/restore. Compose ContentAccessPolicy from current owner APIs; batch SQL
   candidates/hydration consume only the published views. Expose a bounded post
   query, not a Phase 6 home-feed implementation.
4. Implement real S3 presign/HEAD/versioned GET/variant PUT/delete adapter and
   private versioned local MinIO with encryption/CORS/lifecycle controls. Reserve
   quota under per-owner guards, finalize outside locks then CAS, bound image
   decoding/re-encoding, generation-specific outputs, retryable cleanup and
   reconciliation. Bind all ten images atomically; retain released revision media
   for the retention window. Avatar replacement binds/releases transactionally;
   owner content/profile policy alone authorizes 60-second variant capabilities.
5. Verify domain and HTTP denial/invalid-state paths, generated SQL/object audience
   parity, PostgreSQL empty/upgrade migrations and meaningful query plans. Use
   separate committed transactions and barriers for membership/block vs writes,
   ten-image rollback/reuse, quota and attach/cleanup races. Real MinIO tests cover
   presign/checksum/versioning, post-finalize overwrite, stale generations, partial
   output failure, malformed/oversized/static WebP images, metadata stripping,
   avatar replacement and all-version cleanup. Verify required worker recovery.
6. Run targeted PostgreSQL/storage tests, formatting and full `./mvnw verify`;
   validate OpenAPI/Compose and update subsystem/schema/domain/operations docs,
   README and Phase 4 evidence. Commit verified owned implementation first, then
   a separate mailbox commit with its actual code hash, READY_FOR_REVIEW and main
   as next owner. No self-acceptance, Phase 5, push or agents.

## Reviewer findings and developer responses

No Phase 4 implementation/review yet. No finding IDs or required responses.
Phase 3 was independently ACCEPTED without open findings; preserved in archive.

### Main prerequisite decision — P4-B1 RESOLVED, 2026-10-10

The user explicitly asked main to review the blocker and decide its handling.
Select the proposed PGSTY Silo artifact for local Compose/Testcontainers. ADR-013
is now ACCEPTED FOR IMPLEMENTATION in the decision commit above. No further
distribution-choice approval is pending. This resolves the decision prerequisite,
not Phase 4 acceptance or its unimplemented storage/security tests.

Independent review confirmed the official MinIO advisories describe the relevant
final-CE signature/encryption defects; Silo's release/security ledger describes
fix provenance. Main successfully pulled the exact proposed digest and inspected
linux/amd64 version/source/license metadata matching the tagged artifact identity.
No storage service was started and no exploit or storage contract was tested.
Local evidence: `/tmp/social-core-phase4-storage-review-pull.log`.

Development must use that same digest in Compose and real Testcontainers tests,
keep AWS SDK v2 as the boundary and implement all existing Phase 4 slices. Use a
single local node, loopback developer ports, private/versioned/encrypted buckets,
separate admin/runtime credentials and retained artifact/license notices. Storage
signature/header/checksum, pinned-version and cleanup/fencing tests remain required.
If a required test exposes a real incompatibility, report the precise failure;
do not weaken a denial path or substitute untested storage to proceed.

## Checks and limitations

### Historical development report — prerequisite blocker P4-B1 (before main decision)

The report below is preserved from `4c5a155`; its approval-pending statements
describe that earlier state. The main decision above supersedes them.

Mailbox was set IN_PROGRESS and the scoped plan above was recorded before edits.
No Phase 4 implementation code, migration, dependency or storage configuration has
been added. Posts/privacy/revisions/moderation/images/avatar remain the full task;
none is claimed delivered. There is no Phase 4 code commit and READY_FOR_REVIEW
would be inaccurate. Accepted executable baseline remains
`bfda6e856d42282eaccd2ed70a81f49c416f86b7` (with subsequent documentation commits).

**P4-B1 — required MinIO distribution unavailable/unpatched:** real Docker pulls
of upstream minio/minio's April 2025 and final October 2025 CE tags failed on
Docker Hub (pull-access denied) and Quay (HTTP 401); the archived official binary
URL returned HTTP 410. Upstream security advisories identify unsigned-trailer and
Snowball authentication bypass and encryption metadata injection in all final CE
releases. These security conclusions are from official advisories/source
inspection, not a reproduced exploit in this repository. No vulnerable service
was started. Rebuilding the available archived upstream source alone does not
resolve the documented defects.

Concrete proposed resolution: [ADR-013](adr/013-local-object-storage-distribution.md),
committed as `551dbb71135e63186588cec9d86f797dba0d9b1f` (documentation only), specifies
a maintained MinIO-derived PGSTY Silo release/source/digest for local Compose and
Testcontainers, exact required security/storage checks and supported upstream
AIStor/source-plus-proxy alternatives. Candidate registry manifest lookup succeeds;
its image has not been pulled/run and its storage contract is not yet tested.
The proposal is **not accepted**. No adopted contract has been changed.

Approval is needed because AGENTS.md's “Before changing code” requires a concrete
ADR for product/security/architecture conflicts, and implementation-handoff.md
requires resolving a documented conflict before coding that part. Changing the
local distribution/maintenance provenance or maintaining a mandatory security
proxy is treated as such a conflict. This is not a repeated request to authorize
ordinary implementation or local commits. An async choice was requested from the
user; absent a decision, dependent infrastructure work remains blocked.

Actual checks in this development round, on the unchanged executable baseline:

- The initial `./mvnw verify` failed Enforcer because the shell defaults to Java 8.
  Existing JDK 21 was found at `/usr/lib/jvm/java-21-openjdk-amd64`; no installation
  or system Java setting was changed.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw verify` succeeded:
  **124 tests**, 29 unit/architecture and 95 integration; zero failures/errors/skips.
  PostgreSQL 18.6 and Mailpit ran through existing Testcontainers. This validates
  the Phase 3 baseline, not Phase 4 behavior.
- The same explicit Java 21 environment with `./mvnw -B -ntp spotless:check`
  succeeded. `docker compose config --quiet` and `git diff --check` succeeded.
- Documentation proposal paths/status and staged whitespace were inspected before
  its local commit. Original checkout/index were clean; only task-owned docs were
  staged. No unrelated changes, code commit, push, deployment or agent spawning.

Phase 4 PostgreSQL/storage/concurrency/HTTP acceptance tests have not been written
or run. No implemented OpenAPI/schema/phase-completion update is appropriate yet.
Production S3 compatibility and earlier provider/production gates remain external.

### Main decision checks

- Verified clean checkout and documentation-only proposal commits `551dbb7` and
  `4c5a155`; accepted executable baseline remains Phase 3's code commit above.
- Independently reviewed primary upstream advisories, Silo release/security ledger,
  successful pinned image pull and its version/source/digest metadata.
- Decision documentation local-link checks and whitespace checks: PASS. Updated
  ADR index/ADR-008 supersession and media/testing/roadmap/operations references.
- Application tests were not rerun for this documentation-only decision. The
  development report's 124 baseline tests remain historical; no Phase 4 test claim.
- No source/migration/dependency/Compose edits, code fix, push or deployment here.

## Next action and role boundary

User returns to the existing development chat `Develop Phase 4 — Posts & Media`
and sends the resume prompt below. No new development chat is needed.

> Read AGENTS.md and docs/agent-handoff.md. Main resolved P4-B1 and accepted ADR-013
> at ab568554cf50cfa5c2024e84c90a16ed234a967c. Resume Phase 4: set IN_PROGRESS and
> implement the full recorded plan using the exact pinned PGSTY Silo artifact for
> Compose/Testcontainers. First prove the required real storage/security contracts,
> then complete posts/privacy/revisions/basic moderation/images/avatar. Keep all
> required acceptance and concurrency checks. Do not ask again for the decided
> distribution choice; report any new concrete test incompatibility precisely.
> Run targeted gates and full ./mvnw verify, update docs, commit verified code and
> handoff separately, set READY_FOR_REVIEW with actual code hash and next owner main.
> Do not self-accept, push, start Phase 5 or spawn agents.

Main remains orchestrator/reviewer. Return here when development hands off for
Phase 4 code review; one chat writes at a time.
