# Agent handoff — Phase 4

## Routing

- Task: phase-4-posts-and-secure-images
- Round: 1
- State: CHANGES_REQUESTED
- Last writer: main reviewer, 2026-10-10 — requested checkpoint review
- Next owner: development chat `Develop Phase 4 — Posts & Media`
- Assignment: user requested checking completed review and continuing the next work.
- Baseline: `552c4ca99692aba277b6fee6bb91b23962d65787`; clean checkout/index before coordination.
- Accepted Phase 3 code: `bfda6e856d42282eaccd2ed70a81f49c416f86b7`.
- Phase 4 decision commit: `ab568554cf50cfa5c2024e84c90a16ed234a967c` (documentation only).
- Phase 4 checkpoint code: `8fe7738e637e23dc72fe2d6c9845bbe1fe6c02e3` (prerequisites only, not full implementation).
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

Resume baseline: `0193bd4`, clean checkout/index; main's accepted ADR-013 decision
is `ab568554cf50cfa5c2024e84c90a16ed234a967c`. P4-B1 is resolved. Execute the full
six-slice plan below, starting with real storage/security prerequisites. Use the
exact accepted Silo image/digest in both Compose and Testcontainers (not upstream
MinIO). Verify private/versioned/encrypted buckets, separate runtime credentials,
CORS, checksum/presign denial and complete version/marker cleanup before dependent
implementation. No Phase 4 acceptance is implied by this resumed state.

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
   private versioned local Silo with encryption/CORS/lifecycle controls. Reserve
   quota under per-owner guards, finalize outside locks then CAS, bound image
   decoding/re-encoding, generation-specific outputs, retryable cleanup and
   reconciliation. Bind all ten images atomically; retain released revision media
   for the retention window. Avatar replacement binds/releases transactionally;
   owner content/profile policy alone authorizes 60-second variant capabilities.
5. Verify domain and HTTP denial/invalid-state paths, generated SQL/object audience
   parity, PostgreSQL empty/upgrade migrations and meaningful query plans. Use
   separate committed transactions and barriers for membership/block vs writes,
   ten-image rollback/reuse, quota and attach/cleanup races. Real Silo tests cover
   presign/checksum/versioning, post-finalize overwrite, stale generations, partial
   output failure, malformed/oversized/static WebP images, metadata stripping,
   avatar replacement and all-version cleanup. Verify required worker recovery.
6. Run targeted PostgreSQL/storage tests, formatting and full `./mvnw verify`;
   validate OpenAPI/Compose and update subsystem/schema/domain/operations docs,
   README and Phase 4 evidence. Commit verified owned implementation first, then
   a separate mailbox commit with its actual code hash, READY_FOR_REVIEW and main
   as next owner. No self-acceptance, Phase 5, push or agents.

## Reviewer findings and developer responses

### Checkpoint review verdict — round 1, 2026-10-10

CHANGES_REQUESTED on prerequisite code `8fe7738e637e23dc72fe2d6c9845bbe1fe6c02e3`.
Review HEAD `5bf1b80` adds only handoff documentation; checkout/index were clean.
The user requested this review while development was IN_PROGRESS. This reviews
the delivered checkpoint, not a completed Phase 4. Phase 3 acceptance is unchanged.

Reviewed all checkpoint executable changes: SDK port/adapter, content values/read
policy, Compose/bootstrap/credential policies, dependency additions and tests.
Two actionable findings follow. Remaining Phase 4 capabilities are separately
listed in the development report below and must still be implemented.

#### P4-R1 — P2 — Limit each service's secret mounts

- Status: OPEN. Evidence: configuration/code inspection, not an exploit reproduction.
- Anchors: [Silo mount](../docker-compose.yml#L67),
  [bootstrap mount](../docker-compose.yml#L93),
  [application mount](../docker-compose.yml#L42),
  [new secret generation](../ops/local/generate-keys.sh#L20).
- Both new storage services mount the entire `.local/secrets` directory. With
  the documented shared developer UID, they can read the application's JWT signing
  private key, mail encryption key and limiter key. The same directory now contains
  storage admin/KMS secrets, which also become readable by the existing application
  container through its broad mount. Read-only mounts prevent writes, not reads.
  This defeats ADR-013's admin/runtime separation and the operations claim that
  application credentials exclude storage administration.
- Required outcome: mount only explicitly required files (or narrowly separated
  secret directories) per service. Silo needs its admin/KMS secrets; bootstrap needs
  admin/API/worker credentials; the application needs its application keys and only
  the runtime storage credentials actually used. Preserve existing key material,
  UID behavior and startup/bootstrap replay; do not rotate/delete user secrets.
- Regression expectation: verify the resolved mount allowlist and prove unrelated
  secret paths are absent/inaccessible with synthetic fixtures or non-content
  checks. Never print real secret values. Startup and bootstrap replay must pass.

#### P4-R2 — P2 — Exercise the query-credential unsigned-trailer bypass

- Status: OPEN. Evidence: test/code inspection against the advisory cited by ADR-013;
  no claim the pinned Silo build is exploitable.
- Anchors: [unsigned-trailer test](../src/test/java/com/flyvo/socialcore/media/StorageContractIT.java#L274),
  [Authorization header](../src/test/java/com/flyvo/socialcore/media/StorageContractIT.java#L280),
  [presign tampering test](../src/test/java/com/flyvo/socialcore/media/StorageContractIT.java#L205).
- Both branches of the unsigned-trailer test always set `Authorization` and never
  supply query-string credentials. The
  [upstream bypass](https://github.com/minio/minio/security/advisories/GHSA-hv4r-mvr4-25vw)
  specifically omitted that header and supplied `X-Amz-Credential` in the query;
  header presence selected signature verification even in the vulnerable ordinary
  PUT branch. Thus the test can pass while missing the exact prerequisite defect.
  The separate damaged-presign test also drops the original required signed headers,
  so its 4xx can reflect missing headers rather than rejection of the changed signature.
- Required outcome: add the no-Authorization/query-credential unsigned-trailer case
  on the pinned real server with a well-formed payload and valid scoped access-key
  identifier but invalid signature. Retain a fully formed positive control and vary
  only signature/authentication as appropriate. Preserve all other signed headers
  in the damaged-presign case. Keep the Snowball/header cases; use a nonempty valid
  archive and check extracted destination absence where asserting extraction denial.
- Regression expectation: assert authentication rejection and absence of both
  created object versions and unintended extracted objects, without logging signed
  URLs/credentials. Record the actual denial paths covered; no vulnerable image is
  required. These checks are mandatory before claiming ADR-013 security coverage.

### Developer responses

Pending for P4-R1 and P4-R2. Append implementation/evidence and real fix commit for
each ID; do not mark either resolved. Main verifies their disposition on re-review.

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

### Independent checkpoint review checks — 2026-10-10

On unchanged code with installed Java 21 explicitly selected:

```bash
./mvnw -B -ntp spotless:check test \
  '-Dtest=PostPolicyTest,ArchitectureTest,PersistenceBoundaryTest' \
  failsafe:integration-test failsafe:verify '-Dit.test=StorageContractIT'
docker compose --profile media config --quiet
bash -n ops/local/generate-keys.sh
sh -n ops/local/storage/start.sh ops/local/storage/init.sh
```

- PASS: 26 tests (14 unit/architecture + 12 real Silo integration), no failures,
  errors or skips; formatting/Enforcer/dependency convergence passed. Temporary
  evidence: `/tmp/social-core-phase4-checkpoint-review.log`.
- Compose configuration and shell syntax checks pass. Review documentation local
  link paths and whitespace checked before commit.
- No application source/test/configuration changed during review. No secret
  contents read or printed. No push/deployment or other chat/agent started.
- Full 142-test verify and actual Compose startup/replay are historical development
  evidence, not rerun review checks. The scoped tests do not cover the missing
  authentication variant in P4-R2 or secret mount isolation in P4-R1.
- This checkpoint has no post/media/avatar public endpoints or migrations. Full
  Phase 4 privacy/SQL parity/HTTP/concurrency/decoder/lifecycle gates remain unrun.
  There is no new external blocker or pending architecture choice.

### Reported development checkpoint — 2026-10-10, before main review

Preserved from `5bf1b80`; the routing/verdict above supersedes its earlier state
and next-owner description. Its delivered/remaining scope and checks remain evidence.

Verified code checkpoint: `8fe7738e637e23dc72fe2d6c9845bbe1fe6c02e3`.
This is **not completed Phase 4** and is not READY_FOR_REVIEW. State stays
IN_PROGRESS, next owner development. No new unresolved external blocker; no
distribution approval is pending. The user's full completion request remains
unfinished, and there is no permission to treat these prerequisites as the phase.

Delivered: exact ADR-013 Silo Compose/Testcontainers artifact; shared private,
versioned, SSE-S3 bootstrap with exact CORS, lifecycle and admin/API/worker policies;
persistent ignored local keys/data; AWS SDK v2 storage port/adapter with pinned
reads, checksum/size signing, immutable variant retry, <=60-second capabilities,
bounded version/marker purge and active-transaction network guard. Also pure
PostAudience/PostBody invariants and canonical ordinary read/history policy, tested
across personal/community/current-denial combinations. These are not wired to
business use cases, persistence, owner APIs, SQL queries or public HTTP endpoints.

Actual real-storage compatibility difference: Silo's pinned policy library maps
GetObjectVersion implicitly to GetObject, so the first delivery policy allowed
unversioned GET. Outside-prefix denial still worked. Required non-null s3:versionid
context fixes the local policy; denial, pinned GET and real URL expiry now pass.
No denial assertion was disabled. A guessed internal replication-header rejection
was replaced after source review with proof encrypted bytes remain intact when
untrusted replication metadata is ignored. Details/provenance: [storage operations](../ops/media.md).

Checks on final checkpoint source, with installed JDK 21 explicitly selected:

- Fresh baseline full verify: 124 pass, zero failures/errors/skips.
- Targeted StorageContractIT: 12 real Silo tests pass, using Compose bootstrap and
  policy files. Includes signature/checksum/unsigned operation header denials,
  unsigned-trailer/Snowball invalid-signature paths, versioning/overwrite/pinned
  bytes, private/encrypted storage, CORS, credential scope, version-only delivery,
  capability expiry, all-version/marker deletion and SDK adapter retries/guards.
- Targeted PostPolicyTest + architecture/persistence boundaries: 14 tests pass.
  Pure policy loops are not SQL/object parity or concurrency evidence.
- Final `./mvnw -B -ntp spotless:apply verify`: **142 pass**, 35 unit/architecture
  and 107 integration (95 existing PostgreSQL/Mailpit + 12 Silo), no failure/error/skip.
  Local log: `/tmp/social-core-phase4-checkpoint-verify.log`.
- Actual Compose non-root/read-only startup, shared bootstrap and replay pass;
  Compose config, shell/JSON syntax, local Markdown file links and staged whitespace
  pass. The local Silo service is stopped; generated ignored keys/data are retained.
  Tests clean their own isolated containers. No push, deployment or agents.
- Source/test iteration failures were fixed before the final gate: imports/lambda,
  immutable-list null lookup, UTF-16 constructor validation placement and missing
  sed in the selected image. No failing implementation is committed as complete.

No new migration or OpenAPI endpoint exists. Required post persistence/publication,
privacy mutations/revisions/retention, SQL candidates/hydration/parity and cursor API,
basic attributable remove/restore, quota/asset/binding lifecycle, external fenced
worker/decoder/finalize, cleanup/reconciliation and avatar pipeline remain
unimplemented. Their PostgreSQL/HTTP/race/fault/hostile-image acceptance tests are
unrun, not skipped-to-green. See [Phase 4 checkpoint](phases/phase-4.md).
Production S3 compatibility remains an external deployment gate. Phase 5 is untouched.

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

Return to the existing `Develop Phase 4 — Posts & Media` chat. First address P4-R1
and P4-R2 with regression evidence, then continue the assigned remaining full plan:
owned schemas/owner API guards, publication/read/history/audience/moderation,
quota/assets/binding, fenced processing/finalize/cleanup and avatar. Set IN_PROGRESS
and preserve the findings and developer responses. Verified slice commits are
allowed, but a prerequisite checkpoint does not fulfill the full assignment.

Do not request approval for the already-decided storage distribution. Continue
until the assigned capabilities, tests and docs are complete, or report a new
concrete blocker with evidence. Then commit verified code and a separate
READY_FOR_REVIEW mailbox with real code hash and main as next owner. No Phase 5.

The original main resume assignment remains preserved below; it is not a claim
that the requested full implementation has been completed.

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
