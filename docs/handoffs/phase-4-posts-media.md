# Agent handoff — Phase 4

## Routing

- Task: phase-4-posts-and-secure-images
- Round: 3
- State: ACCEPTED
- Last writer: main, 2026-10-11 — independent review and Phase 4 acceptance
- Next owner: main (activate the requested Phases 5–6 package)
- Assignment: address P4-R3/P4-R4/P4-R5 from `8333028`; no Phase 5–6 implementation.
- Baseline: `552c4ca99692aba277b6fee6bb91b23962d65787`; clean checkout/index before coordination.
- Accepted Phase 3 code: `bfda6e856d42282eaccd2ed70a81f49c416f86b7`.
- Phase 4 decision commit: `ab568554cf50cfa5c2024e84c90a16ed234a967c` (documentation only).
- Phase 4 checkpoint code: `8fe7738e637e23dc72fe2d6c9845bbe1fe6c02e3` (prerequisites only, not full implementation).
- Complete Phase 4 code: `12fce2f8715ff7e17920b3b1bcf6e39b9b3da311`.
- Round 2 stabilization code: `9bdc9996b546d73356f177759806296cea40bc43`.
- Stabilization baseline: `8333028`; checkout/index were clean before this round.
- Initial full-delivery execution baseline: `44bcc99`; that checkout/index were clean.
- Prior verdict/findings/checks: [accepted Phase 3](../handoffs/phase-3-social-graph.md).
- Protocol: [thread workflow](../thread-workflow.md), [Git workflow](../git-workflow.md).

## Scope and implementation instructions

Implement Phase 4 exactly as specified in [roadmap](../roadmap.md#phase-4--publishable-posts-with-secure-image-attachments):
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
PGSTY Silo selected in accepted [ADR-013](../adr/013-local-object-storage-distribution.md)
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

## Current implementation report — full Phase 4, 2026-10-11

### Round 2 stabilization plan (executed)

Baseline `8333028`, clean checkout/index. Preserve all main findings/dispositions.
P4-R3: replace multi-statement relation/root loading with a bounded single-statement
snapshot; exercise recipient removal/body edits, audience-kind switches and
attachment/mention edits with real PostgreSQL transactions and statement barriers.
P4-R4: deny issuance/binding immediately on deletion but keep quota until upload
expiry plus a bounded in-flight settling window; durably recheck without consuming
the failure budget before that boundary. Test real reusable Silo PUTs, rejection,
crash/retry and exactly-once quota release, including quarantine-only cleanup.
P4-R5: record only the exact historical false-positive fingerprint with rationale;
run the CI-pinned full-history scan and an external synthetic positive control.
Update stale status and affected lifecycle/query/testing/operations documentation,
then targeted tests, formatting and full verify. Commit verified code/docs first,
mailbox separately with its real hash and READY_FOR_REVIEW/next owner main.
No self-resolution/acceptance, push, history rewrite, broad exclusions or agents.

### Round 2 current delivery

Verified stabilization code/documentation/tests:
`9bdc9996b546d73356f177759806296cea40bc43`.
This extends the complete Phase 4 delivery below; it does not self-resolve R3/R4/R5
or accept Phase 4. R1/R2 were already resolved by main. Eight additional Phase4IT
tests cover coherent aggregate/history races, upload cleanup/settling/reconciliation
and bounded replay/FAILED recovery; seven were run red against historical code.
Exact per-ID responses and fresh checks/limitations follow. README and
implementation-handoff now reflect implemented Phase 4 awaiting main re-review.
No Phase 5–6 implementation, push, agent or external coordination.

### Initial full-delivery report (historical)

Code/documentation/tests committed as
`12fce2f8715ff7e17920b3b1bcf6e39b9b3da311` (`feat(content): deliver phase 4 posts and secure image lifecycle`).
This supersedes the prerequisite-only checkpoint, not main's review findings or
Phase 3 acceptance. All six implementation-plan slices are delivered for review.

- Personal/community publication, CUSTOM and inherited scope, mentions/hashtags,
  plain text/unfetched links, current ContentAccessPolicy and published-view SQL
  candidates/batched hydrate/bounded encrypted-cursor query.
- Versioned body/attachment and explicit audience edits, logical create idempotency,
  immutable revisions/current history authorization, owner blind soft deletion,
  bounded retention/redaction and scoped attributable remove/restore. Mandatory
  moderation action/audit/outbox commits atomically; no author-body rewrite.
- Real upload/finalize/pinned process/status/bind, quota/rate, credential-minimized
  isolated static JPEG/PNG/WebP decoder, generation-pinned PNG variants, current
  source-authorized <=60-second capabilities, durable cleanup/reconciliation and
  versioned avatar replacement/release/profile-policy access.
- Platform external prepare/effect/ack and expired final-lease recovery; stale/
  partial/pre-READY crash outputs never become downloadable. Cleanup confirms all
  versions/markers before accounting release. Restricted audited replay preserves
  the existing job and monotonically increasing generation.
- V0015–V0021 add owned schemas, avatar/typed moderation FKs and deferred/immutable
  guards. Applied V0001–V0014, the module DAG and SQL-view allowlist are unchanged.
  New dependency: pure Java TwelveMonkeys imageio-webp 3.15.3, needed for static WebP;
  existing SDK v2 remains the storage seam. Implemented OpenAPI and subsystem/schema/
  domain/operations/README/phase record are updated, including material refinements.

Detailed evidence/limits: [Phase 4](../phases/phase-4.md), [content](../content.md),
[media](../media.md), [operations](../../ops/media.md), [OpenAPI](../api/openapi.yaml).
No Phase 5, future feed/search/notification/video/document scaffolding, acceptance,
push, external deployment, other thread or spawned agent.

## Reviewer findings and developer responses

### Main review — round 3, 2026-10-11: ACCEPTED

Accepted executable commit: `9bdc9996b546d73356f177759806296cea40bc43`, including
full-delivery ancestor `12fce2f8715ff7e17920b3b1bcf6e39b9b3da311`. Reviewed delivery
HEAD `b7ecf1c`; initial checkout/index clean. Main inspected the entire stabilization
executable/config/test diff, affected documentation and previous review context.
No new blocking findings. No application/test/migration changes by main.

- **P4-R3 RESOLVED:** one SQL statement snapshot hydrates post and ordered relations;
  current owner policy still authorizes it. History is capped at the authorized
  aggregate revision. Real PG barrier tests pass for removed CUSTOM recipient then
  private body edit (direct/batch), audience-kind switches, attachment/mention edits
  and staff-departure/history race.
- **P4-R4 RESOLVED for local Phase 4:** cleanup waits through persisted upload expiry
  plus settling, with fenced durable requeue and no attempt burn. Immediate deletion
  denies business use; confirmed purge releases quota once. Real Silo regressions
  pass for reusable/expired PUTs, early rejection, READY deletion, purge-before-ack
  lease loss/retry, late-write tombstone repair, bound quarantine cleanup and bounded
  sweep/FAILED recovery window. Late SDK writes model exceptional completion, not
  successful new uploads with expired URLs or proof of a total server deadline.
- **P4-R5 RESOLVED:** exact full-history pinned scan passes with only the reviewed
  historical fingerprint exception; external synthetic generic-api-key detection
  still passes. Default rules, redaction and original scan command remain enabled.
- **P4-R1/R2 remain RESOLVED:** Compose isolation and real signature/storage checks
  pass in the full suite. These dispositions supersede historical OPEN wording below;
  original findings and developer responses are preserved.

Independent checks on the accepted code:

- Java 21 `./mvnw -B -ntp verify`: **182 passed**, 39 unit/architecture/contract +
  143 integration; zero failures/errors/skips. Includes 33 Phase4IT, 12 storage,
  Compose isolation, packaged decoder and prior real PG/Mailpit suites. Log
  `/tmp/social-core-p4-round3-main.log`, BUILD SUCCESS in 1m41s. The original process
  continued across the user interruption and was observed to finish; no duplicate run.
- Exact CI-pinned Gitleaks on delivery HEAD: 28 commits, zero findings. External
  synthetic positive control PASS (expected scanner exit 1, wrapper exit 0).
- Compose app/media config and reviewed whitespace PASS. Developer negative red
  regressions were not independently rerun. Actual Compose startup/replay/app image
  build remain earlier developer evidence, not fresh checks here.

This accepts local behavior and contracts; production launch remains gated by
provider compatibility, total ingress upload duration/physical storage budgets,
decoder isolation, backup/restore and prior identity gates. Silo has no tested
absolute total PUT deadline; settling plus periodic repair provides eventual cleanup
of exceptional late completions. Worker/sweep throughput needs deployment measurement.

The user requested continuation and larger assignments. Main may now archive this
record, activate the planned Phases 5–6 package and push under main-only authorization.

### Full-delivery review — round 2, 2026-10-11

CHANGES_REQUESTED on code `12fce2f8715ff7e17920b3b1bcf6e39b9b3da311`;
review HEAD `abfd255` adds handoff only. Initial checkout/index clean. No application,
test or migration changes made by main. Phase 4 is not accepted.

Independent full Java 21 `./mvnw -B -ntp verify`: **174 pass** (39 unit/architecture/
contract, 135 integration), zero failures/errors/skips. Log
`/tmp/social-core-p4-main-review.log`. Includes real PostgreSQL, Silo, 25 Phase4IT,
12 StorageContractIT, ComposeSecretsIT and packaged decoder. This does not reproduce
the newly identified races below; regression tests remain required from development.
Actual developer Compose startup/replay and production storage compatibility were
not independently rerun. Main inspected content read/write/hydration/retention,
media lifecycle/storage/decoder, profile avatar, moderation, external worker fencing,
migrations and old-finding regressions; green tests are not acceptance.

P4-R1 and P4-R2 are **RESOLVED** by main: explicit mount allowlists and synthetic
container access checks pass; query-only unsigned-trailer requests omit Authorization,
retain scoped query credentials, assert the explicit unsupported-auth rejection and
no output, with successful signed controls and nonempty Snowball extraction checks.
Damaged presign now preserves the signed headers. Original finding text and developer
responses below remain historical; this disposition supersedes their OPEN statuses.

#### P4-R3 — P1 — Hydrate one coherent post version before authorization

- Status: OPEN. Evidence: code/transaction inspection, not an executed race exploit.
- Anchor: `src/main/java/com/flyvo/socialcore/content/infrastructure/persistence/JdbcPosts.java:45–105`;
  `Posts.read/hydrate/facts` authorizes using the resulting in-memory recipients.
- `load` reads recipients, media, mentions, then the post in separate statements at
  READ COMMITTED. Example: viewer A's read captures CUSTOM recipients {A}; the author
  commits an audience change to {B}, then edits the body to B-only text; the read
  finally loads that new body/version while retaining {A}. Policy authorizes A for
  content A was never entitled to read. This is more than the documented race of
  an already-authorized old response. Switching CUSTOM/non-CUSTOM can also assemble
  an invalid PostAudience and yield a server error; attachment/mention edits can mix.
- Required outcome: bounded batched coherent aggregate loading (e.g. a single
  statement snapshot, or an explicitly safe bounded consistency check/retry), while
  preserving current owner authorization, module boundaries and no N+1. Do not fix
  only SQL candidates: direct read, hydration, history and media paths use this load.
- Regression: real PG transactions/barriers pause after relation read, commit
  recipient removal then private body edit, resume and prove no new body leaks to
  A. Also cover audience-kind switches and attachment/mention edits without 500s.

#### P4-R4 — P2 — Do not finalize purge while upload capabilities still work

- Status: OPEN. Evidence: code/lifecycle inspection, not an executed storage exploit.
- Anchors: `media/application/Images.java:185–194`, `ImageCleanup.java:39–69` and
  `QuarantineCleanup.java:31–34` (paths under src/main/java/com/flyvo/socialcore).
- Owner DELETE changes UPLOAD_PENDING to DELETING immediately. Cleanup waits for
  upload expiry only while state is UPLOAD_PENDING, so it now purges and marks
  DELETED/releases quota before the issued presigned PUT expires. Reusing that still
  valid PUT recreates a quarantine version after successful cleanup; later cleanup
  returns immediately for DELETED. Early rejection/READY deletion has the same
  write-capability window. Lifecycle expiry is defense in depth, not evidence of
  confirmed all-version erasure/accounting. It can leave unaccounted stored bytes.
- Required outcome: keep issuance denied immediately, but defer final purge/accounting
  until the upload window is closed and any bounded in-flight completion/reconciliation
  requirement is covered. Persist a durable retry/recheck rather than acknowledging
  a no-op before expiry and losing future work. Preserve bounded jobs and fences.
- Regression: real Silo issue URL, delete/cleanup, reuse still-valid URL, then pass
  the safe finalization boundary and prove no object versions/markers remain and
  quota is released once only. Include early rejected image and retry/crash cases.

#### P4-R5 — P2 — Restore full-history secret-scan CI without broad exclusions

- Status: OPEN. Experimentally reproduced using the exact CI-pinned Gitleaks image.
- Full-history scan of HEAD exits 1: one `generic-api-key` match at historical
  `docs/agent-handoff.md:228` in `abfd2552a557b8882ea63ea655656774e88e4bda`.
  Inspection confirms the matched value is the Phase 4 code commit hash, not a
  credential. The preceding key-related documentation text triggers the heuristic.
- Required outcome: a reviewed narrowly scoped false-positive fingerprint exception
  (with rationale), or equivalently precise solution. Editing HEAD prose alone does
  not fix historical scanning. No generic rule disablement, docs-directory exclusion,
  history rewrite or weakening redaction. Main does not implement this tooling fix.
- Regression: run the exact full-history CI command and prove zero findings; retain
  detection for a synthetic known-positive fixture outside the tracked repository.

### Round 2 development assignment

Reuse `Develop Phase 4 — Posts & Media`; set IN_PROGRESS and complete R3–R5 together
as one stabilization package across content, media and CI. Add the described
regressions and update each ID with evidence, without self-resolving. Reconcile stale
status prose in README (still says no media routes) and implementation-handoff
(still calls Phase 4 planned), retaining awaiting-review wording, not acceptance.
Run targeted gates, formatting, full verify and the pinned full-history secret scan;
update exact checks/limitations, commit verified code then separate mailbox with
real hash, READY_FOR_REVIEW and main as next owner. No push or Phase 5/6 implementation.

Next larger assignment is already planned in [Phases 5–6 package](../work-packages/phase-5-6.md).
After Phase 4 acceptance main will activate that two-phase package in a named new
chat. It combines content interactions, report moderation and feed in one sequential
assignment with internal gates, rather than handing off individual small features.
User-authorized push belongs exclusively to main under [Git workflow](../git-workflow.md).

### Round 2 developer responses — proposed fixes, not reviewer dispositions

All three responses refer to stabilization code
`9bdc9996b546d73356f177759806296cea40bc43`.
P4-R3/P4-R4/P4-R5 remain OPEN; only main may resolve them after independent review.

- **P4-R3:** `JdbcPosts.load` now hydrates the root plus independently aggregated,
  ordered recipients/media/mentions in one READ COMMITTED SQL statement snapshot,
  without Cartesian expansion, per-post JDBC queries or foreign-base-table access.
  Direct read, batch hydrate and history/media authorization use that same load.
  History SQL additionally caps revisions at the coherent authorized post revision,
  preventing a later edit from entering an old authorized response. Current owner
  APIs/policy and the existing module/view allowlist remain unchanged. Real PG
  statement-completion latches commit recipient removal followed by B-only body
  edit, both audience-kind switches and body/attachment/mention replacement while
  the reader is paused. A staff-history barrier commits departure followed by an
  author edit. Old implementations reproduce a leaked B-only body, mixed data,
  INVALID_POST_AUDIENCE and a post-departure history body; all four new regressions
  pass on the fix, as do SQL/object parity and constant one-versus-100 query count.
  Already-authorized old responses can still race a concurrent revocation; no
  cross-request serializable or immediate byte-revocation guarantee is added.
- **P4-R4:** deletion/rejection still denies issuance/new finalize/binding immediately.
  Full cleanup in every eligible state and READY quarantine cleanup wait for the
  persisted upload expiry plus fifteen-minute settling before purge/accounting.
  `ExternalJobHandler.Deferred` rolls preparation back and durably requeues the
  same job under its lease fence; attempts are restored, generation never resets,
  and actual failures/crashes retain bounded ten-attempt/DEAD semantics. Expiry of
  the lease before ack retains DELETING/quota for a fenced retry. Confirmation of
  all versions/markers releases accounting once. Hourly MEDIA_STORAGE_RECONCILE
  uses twenty-asset keyset pages and stable sweep-hour dedup to recheck terminal
  tombstones and READY quarantine, so unusually late completions are not left
  permanently unaccounted/unpurged. FAILED keeps its full 24-hour operator window
  from transition. Real Silo tests exercise pending DELETE, early rejection and
  READY deletion with reused valid PUT (200), actual short-capability expiry (403),
  durable RETRY/available_at without attempt burn, upload-expiry-only denial of
  accounting release, settling then zero versions/markers, duplicate release,
  purge-before-ack lease loss/crash, retry and a late SDK-write/tombstone sweep.
  The late SDK write explicitly models an in-flight completion, not a successful
  new write using an expired presign. Quarantine-only purge preserves active post
  variants; batch/replay and FAILED-window regression pass. Old cleanup fails all
  three core regressions. **Limit:** Silo has rolling idle reads, not a total PUT
  deadline. Settling plus durable sweep is eventual repair for exceptional late
  completions, not synchronous bounded physical erasure. Production ingress must
  enforce total completion within settling and verify its provider. That existing
  untrusted-traffic/physical-budget deployment gate remains unrun; local Silo is
  loopback-only. No false hard-duration guarantee or silent storage substitution.
- **P4-R5:** exact pinned baseline full-history scan reproduced the single
  non-secret code-hash match. `.gitleaksignore` records only its exact historical
  commit/path/rule/line fingerprint with rationale. No rule disablement, directory/
  path exclusion, redaction weakening, history rewrite or scan-depth change.
  CI's original full-history Docker command is unchanged; an added external
  synthetic generic-api-key positive control must produce scanner exit 1 and the
  expected rule, while its wrapper succeeds and removes the fixtures. Default
  detection remains enabled. Full-history scan on the real new code commit passes:
  **27 commits, zero findings**, exit 0; repo is not shallow. Staged scan and positive
  control pass. Current status prose in README, implementation-handoff and ADR-013
  was reconciled, retaining awaiting-review/not-accepted wording.

### Main remote delivery — 2026-10-11

- Configured `origin` as `https://github.com/phifin/core-social-flyvo.git`; initial
  remote had no branch refs. Local branch remains `master`, with newer unaccepted
  Phase 4 code retained. No branch checkout, reset, merge or history rewrite.
- Successfully pushed accepted Phase 3 ancestor
  `552c4ca99692aba277b6fee6bb91b23962d65787` to remote `refs/heads/master`.
  The exact accepted outgoing history passed the pinned Gitleaks scan: 15 commits,
  zero findings. Phase 3 test evidence is historical accepted evidence; current
  full verify above additionally covers Phase 4, without accepting it.
- HTTPS initially lacked a credential helper; the existing gh credential rejected
  workflow publication for missing `workflow` scope. Existing SSH authentication
  then succeeded using a command-local HTTPS-to-SSH URL mapping. No credential
  scope/identity/global configuration changed; configured origin URL stays HTTPS.
- Phase 4 and this local review/workflow documentation are **not pushed**. Main
  plans the next fast-forward after fixes, acceptance and full-history scan pass.
  Remote CI success is not claimed; a successful Git push is not a CI result.
- Only main may perform future pushes. Development must commit locally and hand
  back for review even with a green suite. Future larger scope does not change this.

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
- Anchors: [Silo mount](../../docker-compose.yml#L67),
  [bootstrap mount](../../docker-compose.yml#L93),
  [application mount](../../docker-compose.yml#L42),
  [new secret generation](../../ops/local/generate-keys.sh#L20).
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
- Anchors: [unsigned-trailer test](../../src/test/java/com/flyvo/socialcore/media/StorageContractIT.java#L274),
  [Authorization header](../../src/test/java/com/flyvo/socialcore/media/StorageContractIT.java#L280),
  [presign tampering test](../../src/test/java/com/flyvo/socialcore/media/StorageContractIT.java#L205).
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

Both fixes are verified in complete code `12fce2f8715ff7e17920b3b1bcf6e39b9b3da311`.
Both findings remain OPEN pending main's independent review; development does not
mark resolved or accept. The checkpoint did not reduce the scope: all Phase 4
capabilities above are now delivered, with regression evidence below.

- P4-R1 developer response (verified fix proposed; status remains OPEN): Compose
  now mounts individual application key/runtime storage files, Silo admin/KMS files,
  and bootstrap admin/API/worker files only. Existing secrets are neither read into
  reports nor rotated/deleted. `ComposeSecretsIT` resolves actual Compose JSON and
  mounts synthetic fixtures on the pinned Silo image under the shared developer UID;
  it checks required paths readable and every unrelated secret path absent. PASS in
  targeted and full real-infrastructure runs. Actual non-root Compose startup and
  bootstrap replay pass; Silo required paths are readable and unrelated paths absent
  without reading secret contents. All eight existing key file inode/size/mtime/mode
  metadata remain unchanged. Silo stopped with data/keys retained. Fix code:
  `12fce2f8715ff7e17920b3b1bcf6e39b9b3da311`.
- P4-R2 developer response (verified fix proposed; status remains OPEN): real unsigned-trailer tests now
  omit Authorization and supply valid scoped API access-key identity in query auth
  with an invalid signature. Pinned Silo rejects that streaming authentication mode
  as HTTP 400 `InvalidRequest` with its explicit unsupported-authorization message
  (tagged `newUnsignedV4ChunkedReader` rejects presigned auth before consumption).
  Header invalid signatures return 403 `SignatureDoesNotMatch`; SDK-signed identical
  payload/header controls succeed. Both ordinary PUT and nonempty valid Snowball tar
  cases assert no versions/extracted entry before the control. The Snowball prefix
  is signed and allowed, avoiding an IAM-only false positive. Damaged presign keeps
  all original required signed headers. PASS on real pinned storage in targeted
  and full verify. Fix code: `12fce2f8715ff7e17920b3b1bcf6e39b9b3da311`.
  No vulnerable server or weakened denial was used.

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

### Round 2 stabilization verification — 2026-10-11

Explicit installed Java 21; shell default/identity unchanged. Final commands:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=OpenApiContractTest,ImageProcessorTest,PostPolicyTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify \
  -Dit.test=Phase4IT,StorageContractIT,ComposeSecretsIT,FoundationIT
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply verify
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:check test -Dtest=OpenApiContractTest
docker run --rm -v "$PWD:/repo:ro" \
  zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854 \
  git /repo --redact --no-banner
bash ops/ci/secret-scan-regression.sh
```

- PASS targeted **76**: 18 unit/architecture/contract +58 integration.
- PASS full verify **182**: 39 unit/architecture/contract +143 integration.
  Zero failures/errors/skips in both; PostgreSQL 18.6 and the exact accepted Silo
  digest, 33 Phase4IT, twelve StorageContractIT, Compose isolation, packaged WebP
  decoder and every prior PostgreSQL/Mailpit suite. No new dependency/migration,
  architecture exclusion, mocked persistence or skipped Docker gate.
- PASS final Spotless/Enforcer/convergence and OpenAPI YAML/reference check after
  documentation updates; resolved app/media Compose, script syntax, changed local
  Markdown link paths and staged whitespace. Runtime deployment config unchanged.
- PASS exact CI full-history/redacted scan: 27 commits including stabilization,
  zero findings; full repository, not shallow. External synthetic positive still
  detects generic-api-key/exit 1; wrapper succeeds. Staged stdin scan also clean,
  never substituted for full history. Handoff is rescanned after its own commit;
  its result is reported in chat, not an invented self-reference in this file.
- Deliberate historical-code negative gate: seven tests, six assertion failures
  and one invalid-audience error. Fixed implementations restored before final
  targeted/full gates. Earlier fixture-job ordering and test generic-inference/
  formatter iterations were fixed; these are not silently treated as green runs.

Logs: `/tmp/social-core-p4-r345-targeted.log`, `...-verify.log`,
`...-doc-format.log`, `...-negative.log`, `...-history-code.log`, `...-staged.log`,
and `/tmp/social-core-p4-r5-positive-final.log`. Fresh operational shell/Compose
checks pass. Actual Compose startup/bootstrap replay and Docker application build
remain historical initial-delivery checks below; not rerun or claimed as current.

No new proven local compatibility/test blocker. Production S3/IAM/KMS/checksum/
version/delete compatibility, absolute ingress/upload completion and strict
physical/overwrite budgets, TLS/runtime separation, stronger decoder isolation,
security/restore/legal-retention/alert gates remain deployment-blocking and unrun.
Settling cannot make rolling-idle Silo a bounded total-duration upload server;
exceptional late versions are durably reconciled, not instantly erased. SDK fixture
write validates that repair path without claiming an expired URL bypass. Main must
review this explicit limitation with the proposed R4 fix; no self-acceptance.

### Initial full-delivery development verification — 2026-10-11 (historical)

Installed JDK 21 explicitly selected; the shell's default Java 8 was not changed.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=OpenApiContractTest,ImageProcessorTest,PostPolicyTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify \
  -Dit.test=Phase4IT,StorageContractIT,ComposeSecretsIT,FoundationIT
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp verify
```

- PASS targeted: **68 tests** (18 unit/architecture/contract +50 integration),
  zero failures/errors/skips. Additional non-default Compose Origin/OpenAPI
  regression **2 pass**; final documentation OpenAPI parser/reference test **1 pass**.
  Evidence `/tmp/social-core-p4-final-targeted.log`,
  `/tmp/social-core-p4-compose-origin.log`, `/tmp/social-core-p4-final-openapi.log`.
- PASS full verify: **174 tests**, 39 unit/architecture/contract and 135 integration,
  zero failures/errors/skips. Real PostgreSQL 18.6, Mailpit, exact accepted Silo;
  25 Phase4IT +12 storage +1 Compose +1 packaged decoder, all prior suites retained.
  Evidence `/tmp/social-core-p4-full-verify.log`. Architecture/persistence tests
  have no new exclusions or foreign-base-table bypass. Full upgrade/fresh tests
  validate twenty-one migrations and preserve fourteen accepted checksums/profile.
- Named real lock waits/races cover leave/block versus publication (both orders),
  quota, ten-image rollback/reuse and attach/cleanup. Real storage fault tests cover
  partial/stale output, crash after both writes before READY rollback, expired tenth
  lease/audited same-job replay, overwrite/pinned processing, invalid media,
  purpose/avatar/privacy/retention, deletion accounting and reconciliation alerts.
- Actual candidate EXPLAIN on 12,000 skewed rows (10% selected author) uses
  post_author/LIMIT, ~1.5 ms on final local run (not an SLA). Hydrate's bounded JDBC
  query count is independent of one versus one hundred posts. SQL/object/current API
  parity uses actual published relationship/community/account views.
- PASS final formatting/Enforcer/dependency convergence, OpenAPI YAML/no duplicate
  keys/local refs/operation IDs, changed-document local link paths, shell syntax,
  app/media resolved Compose and staged whitespace. No applied migration edited.
- PASS actual Compose startup/bootstrap/replay with developer non-root UID/GID,
  required/unrelated secret path checks without content access; eight existing
  secret file metadata unchanged, no rotation/deletion. Silo stopped, data retained.
  Evidence `/tmp/social-core-p4-compose-start.log`, `...-init.log`, `...-replay.log`.
- PASS Docker application build and packaged WebP child smoke as UID 10001:10001
  under read-only root, bounded tmpfs/1 GiB/two CPUs, dropped capabilities and
  no-new-privileges: PACKAGED_DECODER_OK. `/tmp/social-core-p4-image-build.log`.
  This does not claim a whole application deployment or production sandbox.
- PASS staged patch Gitleaks 8.28.0 (pinned existing CI image, stdin/redacted): zero
  leaks, `/tmp/social-core-p4-staged-secrets.log`. No real personal data/secret
  contents are committed or printed. Only owned paths staged; mailbox excluded
  from code commit and committed afterward with the actual hash.

No new proven implementation/infrastructure blocker. Earlier fixture/compiler/
boundary-budget iteration failures were fixed before final gates; no skip-to-green.
Production S3/IAM/KMS/signature/checksum/version/delete smoke, TLS/runtime secret
isolation, strict ingress/volume/reusable-PUT overwrite budgeting, stronger decoder
OS/egress isolation, security review/restore/alert/legal-retention remain explicit
deployment gates, not claimed run. Bucket 10 GiB quota is asynchronously scanned,
not a synchronous physical cap. Both output variants are PNG; EXIF orientation is
stripped. Existing capabilities/bytes cannot be immediately revoked. Removal image
hold is thirty days; late restore cannot revive purged bytes. No public draft-preview,
repair, evidence/report endpoint or future-phase behavior.

The review/checkpoint reports below are historical and do not describe this delivery.

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
untrusted replication metadata is ignored. Details/provenance: [storage operations](../../ops/media.md).

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
unrun, not skipped-to-green. See [Phase 4 checkpoint](../phases/phase-4.md).
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

Concrete proposed resolution: [ADR-013](../adr/013-local-object-storage-distribution.md),
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

**Current:** Phase 4 ACCEPTED at round 3. Main archives this record and activates
the requested Phases 5–6 package. The following older routing is historical.

**Historical:** development addressed round-2 P4-R3–R5 in the existing Phase 4 chat.
Main-only remote delivery and the larger next package are recorded above. The
original incoming review instructions below are historical; current routing is
READY_FOR_REVIEW to main. No Phase 5/6 activation or Phase 4 acceptance yet.

Main reviews stabilization `9bdc9996b546d73356f177759806296cea40bc43` against
`8333028`, including the complete Phase 4 ancestor, not just its prerequisite
checkpoint. Independently verify P4-R3/P4-R4/P4-R5, especially coherent read/history
and explicit settling/late-completion reconciliation limits; eight regressions,
182-test full gate and exact pinned full-history scan evidence. P4-R1/P4-R2 remain
resolved by main, with their regression gates retained. Reassess the full scope
and source/storage/owner boundaries before accepting Phase 4. Only main may resolve
findings or accept; new findings keep stable IDs and route back to this development
chat. Do not implement fixes in main or activate Phase 5–6 before acceptance.

The original main resume assignment is preserved as historical context below.
All requested Phase 4 implementation work is now handed off for review; acceptance
and external production readiness are not implied.

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
