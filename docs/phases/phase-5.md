# Phase 5 — content interaction slice, report workflow pending

Status: partial implementation, **not complete or accepted**. Comments/reactions
are implemented; reports/evidence/action correlation are unimplemented. The package
must complete reports before its full Phase 5 gate. Main accepted
[ADR-014](../adr/014-report-user-target-proof.md) on 2026-10-11 to unblock that work.
Current routing and actual code hash: [mailbox](../agent-handoff.md).

## Delivered content slice

V0022 adds owned comments/reactions with typed FKs, two-level same-post roots,
immutable identity, status/deletion precedence and stable actor/target uniqueness
including inactive reactions. V0001–V0021 are unchanged. No library/dependency,
new module or published SQL view was introduced. CommentModeration is an internal
transaction-required owner seam; complete moderation action/report orchestration
and HTTP workflow remain pending, not represented as delivered.

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

## Verification evidence — 2026-10-11, Java 21

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

## Missing gate and limitations

Reports, concurrent unresolved-case uniqueness, encrypted evidence, staff queue/
resolution/action audit rollback and report-specific privacy/races are **unrun**.
No full Phase 5 completion/gate or acceptance is claimed. Main accepted ADR-014's
narrow owner-policy dependency for USER target proof; development must implement
and verify it. Phase 6 must wait for the full internal Phase 5 gate.

Content interaction delivery does not claim production load capacity. Reaction writes
initially serialize on a post or target comment; future narrowing requires measured
contention. Page consistency is current committed reads near request time, not a
cross-page snapshot. Previously delivered bytes/in-flight old responses cannot be
revoked. Production/provider/media/retention launch gates from Phase 4 remain.
