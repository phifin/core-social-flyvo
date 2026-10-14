# Phase 5 — conversations, reactions and report workflow

Status: **ACCEPTED by main, 2026-10-14**, package code `32c1b1e`.
Independent final verify: 260 PASS (49 fast +211 integration), including all
22 interaction and 22 report tests. Package benchmark: 3,200/3,200 PASS.
P56-B1/R1/R2 resolved; historical delivery reports below retain their original
pending-review state. Production readiness is separately gated.
Comments/reactions and reports/evidence/action correlation are implemented. Main accepted
[ADR-014](../adr/014-report-user-target-proof.md) on 2026-10-11 to unblock USER proof.
Accepted code and complete review: [archive](../handoffs/phase-5-6-interactions-reports-feeds.md).
Current task routing: [mailbox](../agent-handoff.md).

## Round 3 reliability regression — 2026-10-14

P56-R2: report/interaction APIs, guards, ADR-014 dependency and evidence access
are unchanged. Final package targeted gate passes **105 tests** (25 fast +80 PG,
including all 22 ReportsIT and 22 InteractionsIT); full Java21 verify passes
**260 tests** (49 fast +211 integration), zero failures/errors/skips. Logs in
`.local/evidence/p56-r3/package-targeted.log` and `verify.log`. This retains the
privacy, both-order race, concurrent duplicate/resolution and atomic audit/outbox
regressions below; it is not a feed-only verification. Formatting, architecture/
SQL boundary, OpenAPI, app/media Compose, changed-doc links and whitespace pass.
Pinned baseline history scan: 43 commits/no leaks, unchanged two fingerprints;
external synthetic positive control passes. Final staged/history scans and actual
code commit are in the [mailbox](../agent-handoff.md).

The measured read-resource fix, failed baseline and **two consecutive final-source
3,200/3,200 PASS** benchmark outcomes are in [Phase 6 evidence](phase-6.md).
Round3 is READY_FOR_REVIEW, not accepted; only main disposes of P56-R2.

## Historical round 2 stabilization regression — 2026-10-13

The current P56-R1 work passes the full package targeted command:
23 fast tests plus 79 PostgreSQL integration tests (22 InteractionsIT,
22 ReportsIT, 18 FeedsIT, 17 FoundationIT), **102 PASS**, zero failures/errors/skips.
Log: `.local/evidence/p56-r2/package-targeted.log`. The command uses Java 21,
`test` with
`-Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest,ReportTest,CommentTest,PostPolicyTest`,
then `failsafe:integration-test failsafe:verify` with
`-Dit.test=ReportsIT,InteractionsIT,FeedsIT,FoundationIT`.
This retains the report privacy/evidence/atomicity and interaction concurrency
matrix below, alongside source-selective HOME and V0024–V0026 upgrade tests.
No Phase 5 implementation/dependency/evidence-access contract is widened.
Final Java 21 `./mvnw -B -ntp verify`: **257 PASS** (47 fast +210 integration),
zero failures/errors/skips; `.local/evidence/p56-r2/verify.log`. This includes all
33 Phase4IT, 12 real pinned StorageContractIT, packaged decoder/Compose secret
isolation and the full prior identity/community/graph/report/interaction matrix.
Formatting was applied before the final benchmark; full verify's formatting,
Enforcer/convergence, architecture/SQL allowlist and OpenAPI gates pass.
The final feed benchmark completes 3,200/3,200 requests on pools 10/50; separate
[Phase 6 evidence](phase-6.md) records the remaining dense p95 goal miss and all
failed iterations. App/media Compose, local documentation links and whitespace
pass. Pinned redacted history through the 40-commit review baseline and external
synthetic positive control pass with unchanged two exact fingerprints.
Final staged/code/handoff scans and real stabilization hash are in the mailbox.
The combined package is returned for round 2 review; neither phase is accepted.

## Report delivery and resumed verification

Verified report slice `19849c96307d687958ede54df0b4fbd9cbf132a7`:
V0023 implements four owner-proven targets, immutable bounded encrypted evidence
(including detail), unresolved uniqueness, scoped reporter/staff/ADMIN metadata and
audited evidence, current-authority review/resolve/dismiss, correlated typed actions
and atomic owner source/case/action/audit/outbox. Only ADR-014's narrow profile API
edge is added; the architecture suite explicitly limits profile owner calls to visible.
No foreign SQL, broad staff lookup, source authorization cache or Phase 8 operation.
See [moderation](../moderation.md) for exact bounds, lock order, endpoints and limits.

Guarded ProfileQueries.visible returns the exact summary used for USER evidence;
sorted account → graph → profile locks serialize privacy/block/account changes in
both orders. A real older managed-entity read followed by privacy change proves
the locked owner read refreshes Hibernate state, denying instead of retaining an
old profile grant. Owner APIs stay in their modules and join the report transaction.

Resumed targeted command:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=ReportTest,CommentTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify -Dit.test=ReportsIT,InteractionsIT,FoundationIT
```

**70 pass**: 13 unit/architecture/OpenAPI +57 real PG tests (21 ReportsIT,
22 InteractionsIT, 14 FoundationIT), zero failures/errors/skips. Log
`/tmp/social-p56-phase5-targeted.log`. New V0022 upgrade preserves all 22 applied
checksums; every prior migration upgrade remains green.

ReportsIT covers four targets, USER PUBLIC/AUTHENTICATED/FRIENDS/PRIVATE, friendship,
bilateral blocks/inactive target, privacy/block/account changes in both actual lock
wait orders and idempotent replay; no plaintext/public/event evidence, bounds/IDOR,
wrong-community/global USER isolation, deleted-source evidence and current staff
revocation. Concurrent duplicates commit one case/event; concurrent resolutions
commit one source/action. Review/terminal versions, self/owner/hierarchy denial,
member sanction, restore versus author deletion, both staff-sanction/action orders,
evidence/removal orders and audit failures before/after action append prove rollback.
HTTP checks CSRF, current session, unknown caller scope and required preconditions;
raw SQL rejects evidence/identity mutation and append-only action deletion.

Final Java 21 `./mvnw -B -ntp verify`: **231 pass** (43 unit/architecture/OpenAPI
+188 integration), zero failures/errors/skips. Log `/tmp/social-p56-phase5-verify.log`.
Includes all 33 Phase4IT, real pinned Silo/storage/packaged decoder and all previous
identity/community/graph regressions. Formatting/Enforcer/convergence/architecture/
SQL boundary/OpenAPI PASS. Compose app/media config and diff whitespace PASS.
Pinned redacted full-history scan at resume: 36 commits/no leaks, only the two
accepted exact historical fingerprints (`/tmp/social-p56-resume-history.log`).
Report staged patch scan PASS (`/tmp/social-p56-reports-staged.log`); resumed package
history scan through that code commit PASS, 37 commits/no leaks
(`/tmp/social-p56-package-history-before.log`). Only the two exact accepted
fingerprints remain; final delivery scans and actual hashes are in the mailbox.
The successful prior 230-test run is historical, not substituted for this final gate.
That gate allowed Phase 6 in the same package; no phase acceptance.

Final package regressions additionally map a missing USER target to resource 404,
not authentication 401, and deny an inactive reporter with 401. This preserves
sorted owner account guards; no denial path is relaxed. Package targeted verification
is **93 pass** (23 fast +70 real PG: 22 interactions, 22 reports, 12 feeds,
14 foundation), zero failures/errors/skips; `/tmp/social-p56-package-targeted.log`.
It includes cross-phase report removal before feed hydration and the final feed
candidate no-full-sort plan regression. Final full-package `./mvnw -B -ntp verify`:
**248 PASS** (47 fast +201 integration), zero failures/errors/skips;
`/tmp/social-p56-package-verify.log`. Formatting, architecture/SQL boundary, OpenAPI,
app/media Compose, changed local document links and whitespace PASS. Positive
control PASS; final staged/new-history scans/hashes are in the mailbox. Separate
[Phase 6](phase-6.md) records completed representative benchmark and its unmet
p95 goal; this is neither phase acceptance nor production capacity.

## Delivered content slice

V0022 adds owned comments/reactions with typed FKs, two-level same-post roots,
immutable identity, status/deletion precedence and stable actor/target uniqueness
including inactive reactions. V0001–V0021 are unchanged. No library/dependency,
new module or published SQL view was introduced. CommentModeration is an internal
transaction-required owner seam; the resumed reports slice above now supplies
complete action/report orchestration and scoped HTTP workflow.

Comments support roots/replies, normalization of reply-to-reply, edits/If-Match,
logical create idempotency and deletion. Hidden roots become non-identifying
tombstones only for currently visible children. Root/reply pagination is bounded,
oldest-first with scoped authenticated encrypted 30-minute cursors. Reactions have
five types, replace/remove/reactivate with stable identity and increasing source
versions; same-type/absent operations emit nothing. Viewer-filtered actor/count
queries and 100-parent engagement batching never grant access. Events are minimal
v1 envelopes; no social consumer is registered.

Current parent policy, account/graph/community guards and one-statement comment
text/parent/actor eligibility preserve Phase 4's coherent-read contract. Final lock
modes are acquired initially; no upgrade, automatic command retry or network work
under guards. Own deleted text has a 30-day durable single-comment redaction job.
Parent deletion redacts descendants in 500-row batches with atomic monotonic
tombstone events/audit and durable continuation. Existing worker retry/fencing applies.
Implemented APIs/HTTP/details: [content](../content.md), [OpenAPI](../api/openapi.yaml),
[schema](../database-design.md), [events](../events-and-jobs.md).

## Historical content-slice verification — 2026-10-11, Java 21

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=CommentTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify -Dit.test=InteractionsIT,FoundationIT
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp verify
docker compose --profile app --profile media config --quiet
```

Targeted gate: **46 pass** (11 unit/architecture/OpenAPI +35 integration: 22
InteractionsIT and 13 FoundationIT), zero failures/errors/skips. Log:
`/tmp/social-p56-coherence-targeted.log`. Final slice full verify: **207 pass** (41 unit/
architecture/OpenAPI +166 integration), zero failures/errors/skips, including all
33 Phase4IT and real pinned Silo/storage/packaged-decoder/prior-phase regressions.
Log: `/tmp/social-p56-coherence-verify.log`. Formatting, Enforcer/convergence,
OpenAPI/reference checks, resolved app/media Compose config, changed documentation
links and diff whitespace pass. This verifies the delivered content slice and
existing baseline, not an unimplemented report/feed package.

Initial slice `a75885e` passed 45 targeted /206 full tests before the additional
parent-before-comment-statement regression; those results remain historical.

```bash
docker run --rm -v "$PWD:/repo:ro" \
  zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854 \
  git /repo --redact --no-banner
bash ops/ci/secret-scan-regression.sh
```

CI-pinned full-history scan: PASS, 32 existing commits/no leaks with only the two
reviewed exact historical fingerprints. External positive control: PASS; default
generic-api-key detection still exits 1 for its synthetic fixture, with redaction
and cleanup. Logs: `/tmp/social-p56-history-baseline.log`,
`/tmp/social-p56-secret-positive.log`. Delivery's staged/new-history scan and actual
commit hashes are recorded separately in the mailbox after the code commit.

Real PostgreSQL 18.6 regressions cover:

- Reply normalization, cross-post denial/FK and root-only/immutable-parent trigger;
  hidden root/body/author/version/reply context; visible children; all personal
  audiences, anonymous/outsider/owner/relations, private departure and PUBLIC
  nonmember writes, bilateral block, inactive authors/viewers and mute semantics.
- Version edits, stale/foreign denial, logical replay/body conflict, mandatory audit
  failure rollback of source/outbox, stable reaction row and event versions across
  PUT/type change/DELETE/reactivation, repeated no-op effects, typed count/actor
  agreement under hidden/inactive actors and bounded batching without per-post SQL.
- Equal timestamps/keyset progress, empty hidden batch continuation, tampered/
  changed-viewer/expired cursors. A statement-completion barrier then commits a
  CUSTOM→ONLY_ME change and new private comment body: the old response returns only
  its already-authorized old body; subsequent reads deny.
  A separate barrier after the old parent read but before the actual comment SQL
  proves a newly private body is denied at the comment source statement itself.
- Separate-transaction barriers and PostgreSQL `wait_event_type='Lock'` prove comment
  first versus delete/block/membership/removal, revocation first versus reaction,
  both comment reaction/deletion orders, duplicate concurrent same-type reaction,
  and final persisted rows/event counts. No sleep-based interleaving or mocked locks.
- Scoped comment owner seam, wrong scope/role denial and restore versus author
  deletion; durable retention/replay and deleted-parent descendant tombstones.
  Fresh migrations and V0021 upgrade preserve all 21 old checksums, alongside
  preexisting V0001/V0008/V0011/V0014 upgrade checks.
- HTTP trusted actor, CSRF, unknown privilege JSON, ETag/preconditions, typed
  reaction routes and current revoked-session denial.

Earlier targeted runs failed on fixture assumptions (legacy migration counts,
graph's lowercase action/404 semantics, JSON job comparison, audit spy transaction
and session revoke-reason constraint); these were corrected against existing
contracts. They are not passing baseline evidence or weakened denial paths.

## Historical blocker and material limitations

At baseline `870959d`, reports, concurrent unresolved-case uniqueness, encrypted
evidence, staff queue/resolution/action rollback and report-specific privacy/races
were unrun; no full Phase 5 gate was claimed. Main subsequently accepted ADR-014,
then the resumed report implementation and full internal gate above unblocked
Phase 6 in this same assignment. This history is not the current delivery state;
only main can accept either phase.

Content interaction delivery does not claim production load capacity. Reaction writes
initially serialize on a post or target comment; future narrowing requires measured
contention. Page consistency is current committed reads near request time, not a
cross-page snapshot. Previously delivered bytes/in-flight old responses cannot be
revoked. Production/provider/media/retention launch gates from Phase 4 remain.
