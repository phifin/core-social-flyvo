# Agent handoff — Phases 5–6

## Routing

- Task: phase-5-6-interactions-reports-feeds
- Round: 1
- State: BLOCKED — architecture decision P56-B1, package incomplete
- Last writer: development, 2026-10-11 — verified content slice, decision handoff
- Next owner: main — decide ADR-014/scope, then route development continuation
- Accepted Phase 4 code: `9bdc9996b546d73356f177759806296cea40bc43`.
- Phase 4 acceptance commit: `26d60ce`.
- Review history: [accepted Phase 4](handoffs/phase-4-posts-media.md); R1–R5 resolved.
- Content slice commit: `a75885e09bf752e018ccf51a6de6573dda6856e0`.
- Final verified code/test baseline: `870959d876c0928898bf35d75bf48149fe3b896d`.
- Reports and Phase 6 feeds remain unimplemented; no complete package acceptance.
- Full assignment: [Phases 5–6 package](work-packages/phase-5-6.md).
- Main coordinates/reviews/pushes; the user manually opens the named development chat.

## Assignment and execution order

Execute the entire linked two-phase package in one development assignment. Read
AGENTS, thread/git workflow, implementation handoff, architecture, roadmap Phases
5/6, security, domain/database, feed, events/jobs, API/testing contracts and relevant
ADRs. Inspect existing code/APIs before deciding what needs extending.

1. Set IN_PROGRESS and record the concrete API/table/migration/guard/test plan here.
   Preserve all accepted V0001–V0021 migrations; allocate new versions from actual HEAD.
2. Deliver content comments/replies/tombstones and typed reactions, stable lifecycle
   versions, current parent authorization and batched viewer-filtered counts.
3. Deliver scoped reports/evidence/staff action/resolution through existing owner
   APIs with transactional action/audit/outbox.
4. Run complete Phase 5 targeted/format/full verify gate and record evidence.
   Commit verified slices, then continue to Phase 6 in this same assignment.
   This internal gate does not self-accept Phase 5 or require a user handoff.
5. Implement home/community/personal timeline feeds, bounded candidates/refill/
   hydration, scoped cursors and metrics per docs/feed.md.
6. Run real PG race/denial/parity/query-count tests and representative feed benchmark
   (1M posts, 10k follows, skew, 50 readers); record hardware/latency/scan ratios.
7. Run final formatting/full verify, OpenAPI/Compose, pinned full-history secret scan
   and positive control. Update API/schema/subsystem/ops/README and separate
   phase-5/phase-6 evidence. Report unrun/blocked gates honestly.
8. Commit verified code first, then separate mailbox with actual hashes/checks/
   limitations, READY_FOR_REVIEW and next owner main.

Use coherent internal slice commits and deliver the full package. Stop for a concrete
architecture/security conflict or external blocker with evidence and a proposed
resolution. Main alone resolves findings, accepts phases and pushes. No agents,
concurrent writers, push or Phase 7 implementation.

## Carried-forward constraints

- Preserve coherent post/relations snapshots and history revision cap from P4-R3 in
  every interaction/feed path. Candidates/counts never independently grant access.
- Media cleanup retains quota through expiry plus settling and repairs late
  completion durably. Production upload deadline/storage budget, decoder isolation,
  provider compatibility and other launch gates remain pending.
- Keep only the reviewed exact historical secret-scan fingerprints, default
  detection, full-history scan and redaction. There are two fingerprints for the
  same non-secret hash: the original handoff and its verbatim accepted archive.
- Use owner APIs and sanctioned SQL views; no foreign writes/joins, duplicate policy
  authority, architecture exclusions or authorization cache.
- No notifications/SSE/search/advanced Phase 8 moderation, Redis/broker/fan-out,
  speculative modules or production deployment.

## Implementation report and findings

Started from clean `03f8a98`, set IN_PROGRESS and recorded the concrete plan below
before code. V0022 implements content comments/reactions, normalized two-level
roots, non-identifying tombstones, versioned own edits/deletion, stable typed reaction
lifecycle, scoped cursors and source-authorized batched counts. Current parent policy,
full guard order and coherent source reads are preserved. Durable 30-day comment
and bounded deleted-parent descendant redaction use the existing worker/outbox/audit.
No new dependency/view/module, architecture exclusion or prior migration edit.
OpenAPI/schema/domain/security/events/ops/README and separate
[Phase 5](phases/phase-5.md)/[Phase 6](phases/phase-6.md) evidence reflect the partial state.

Content slice is independently verified and reviewable, **not a complete Phase 5
or Phase 5–6 READY_FOR_REVIEW delivery**. Reports/evidence/action correlation and
their tests are unimplemented/unrun. Complete Phase 5 internal gate has not passed;
feed work and the representative benchmark cannot proceed through that gate.
No self-acceptance, push, spawned agent or Phase 7 implementation occurred.

### P56-B1 — pending architecture decision, not a resolved reviewer finding

Evidence: domain-model.md's Report contract includes USER and requires access at
submission; public user visibility is owned by `ProfileQueries.visible` (interface
line 16, implementation `Profiles.visible` line 55). `AccountQueries.find/activeIds`
provide lifecycle facts, not viewer-aware profile access. The frozen architecture
DAG lines 65–67 permits moderation→content/community/account only; enforced
`ModuleRules.java:26` likewise excludes profile. Existing source/private/block
policies and the full architecture suite remain unchanged/passing.

Implementing USER proof by calling profile directly violates that DAG; granting by
active UUID, duplicating profile/graph policy, or relaying profile access through
content would violate the current source/owner contracts. AGENTS's instruction to
raise product/security/architecture conflicts requires a concrete decision instead
of a silent dependency or supported-target scope change.

Proposed resolution: [ADR-014](adr/014-report-user-target-proof.md), status Proposed,
adds only moderation→profile.api for existing `ProfileQueries.visible` at USER report
submission. No profile writes/staff directory/base SQL/global ordinary bypass.
Alternative: main explicitly defers USER reports and records a narrowed Phase 5
target scope. An asynchronous decision request was presented; no answer/approval
was received, and elapsed time has not been treated as approval.

Developer response: complete independent interaction slice and all its required
verification; do not implement the prohibited dependency or claim the report/feed
gate complete. Main must decide P56-B1, then route this same package back to the
named development thread. Accepted Phase 4 R1–R5 remain in the unchanged archive;
development does not mark P56-B1 or prior findings resolved.

### Actual checks — final baseline 870959d

- Java 21 targeted: **46 pass** (11 unit/architecture/OpenAPI +35 real PG ITs:
  22 InteractionsIT, 13 FoundationIT), zero failures/errors/skips. Command in Phase 5
  evidence; log `/tmp/social-p56-coherence-targeted.log`.
- Java 21 `./mvnw -B -ntp verify`: **207 pass** (41 unit/architecture/OpenAPI +166
  integration), zero failures/errors/skips. Includes all accepted Phase 4 33 tests,
  pinned Silo/storage/packaged-decoder, previous graph/community/identity and migration
  regressions. Log `/tmp/social-p56-coherence-verify.log`. Formatting,
  Enforcer/convergence and architecture/SQL allowlist pass.
- Named real PostgreSQL lock-wait races cover interaction/revocation both orders,
  duplicate reactions and comment-reaction/deletion. Statement barriers cover an
  old in-flight response and revocation before comment SQL, with no newly private
  body exposure. HTTP CSRF/actor/privilege/precondition/session, root FK/depth,
  current counts/actors/100-parent no-N+1, retention/replay and V0021 checksum-preserving
  upgrade pass. See the test matrix and Phase 5 evidence for exact cases.
- App/media `docker compose ... config --quiet`, OpenAPI parser/local references,
  changed documentation local links and whitespace pass. No Docker image/provider
  deployment smoke or remote CI claim for this task.
- CI-pinned Gitleaks 8.28.0 default redacted full-history scan: PASS, **34 commits**
  through final code/test commit, with only the two accepted exact historical
  fingerprints. Code/regression staged patch scans PASS. External synthetic
  generic-api-key positive control PASS (expected detection/exit 1, wrapper exit 0).
  Logs `/tmp/social-p56-history-final-code.log`, `/tmp/social-p56-staged.log`,
  `/tmp/social-p56-regression-staged.log`, `/tmp/social-p56-secret-positive.log`.
  This separate handoff is scanned after commit; its own hash is not embedded here.
- **Unrun/incomplete:** report submission/queue/evidence/resolution, unresolved-case
  concurrency/IDOR/action-audit rollback matrix; complete Phase 5 gate; every Phase 6
  API/refill/cursor/privacy/race/parity/metric check; 1M-post/10k-follow/skew/50-reader
  EXPLAIN/query-count/latency/scan-ratio benchmark. No substitute capacity/gate claim.

Material limitations: initial reaction serialization is per post or comment; page
consistency is current source near read time, not a snapshot; report/feed scope is
blocked by P56-B1. Accepted Phase 4 provider/storage/physical upload bound/decoder/
legal retention/public launch gates remain, without newly claiming readiness.

### Concrete implementation plan

Existing seams: `ContentCommands`/`ContentQueries`, `PostModeration`, `PostRepository`
and coherent `JdbcPosts.load`; `AccountQueries.guard/activeIds`,
`MembershipQueries.guardWrite/guardScope/facts`, `RelationshipQueries.guardInteractions/facts`,
`ProfileQueries.attributionSummaries`, `ModerationActions`, existing member/post
moderation, `DurableWork`, `Idempotency`, `Secrets`. Extend these rather than duplicate
post authorization or introduce modules for comments/reactions.

1. V0022 content comments/reactions: root composite FK, root-only/immutable-parent
   trigger, status/deletion precedence, stable unique actor/typed-target reactions
   including inactive rows; root/reply ordering and actor/count indexes. Add content
   interaction command/query API, source-authorized summaries/batch engagement,
   normalized replies, non-identifying tombstones, versioned edit/delete and
   current-parent guards. Minimal v1 comment/reaction lifecycle outbox, no consumers.
2. V0023 moderation reports: partial unresolved reporter/target uniqueness,
   bounded encrypted evidence, explicit status/version, report/action correlation,
   typed comment/report action extensions and scope/reporter queue indexes. Add
   reporter submit/list and scoped staff queue/evidence/resolution; reuse owning
   content/community commands for actions, mandatory audit/outbox in one transaction.
   Exact supported report targets/access will be checked against existing owner APIs
   before finalizing public DTOs; no broad staff or ordinary-content bypass.
3. Phase 5 gate: targeted real PostgreSQL/HTTP/race tests, formatting, full verify;
   update Phase 5 evidence and commit verified coherent slices before feed work.
4. Extend `ContentQueries` with lightweight scoped scan and database-time cutoff,
   current authorized hydration and batched engagement. New feed API/controller,
   concrete chronological ranker, authenticated encrypted 30-minute scoped cursors,
   100-candidate batches/1,000 examined/200 ms query budget, 20 default/50 maximum,
   last examined continuation and low-cardinality metrics. V0024 only if measured
   query plans justify indexes; no feed tables or new dependencies.
5. Isolated benchmark tooling: 1M synthetic posts/10k follows/community/block skew,
   actual source SQL EXPLAIN, query count/scan ratio and 50-reader latency on recorded
   hardware. Final privacy/parity/race tests, formatting/full verify, OpenAPI/Compose,
   exact pinned redacted full-history secret scan + external positive control.

| Command | Account guards (UUID sorted, shared) | Community | Graph | Post | Comment |
|---|---|---|---|---|---|
| Create root/reply | actor + post author + addressed/root authors | shared write | sorted shared pairs | shared | addressed/root shared |
| Comment edit/delete | actor + post/comment authors | shared write | sorted shared pairs | shared | exclusive target |
| Post reaction PUT/DELETE | actor + post author | shared write | sorted shared pairs | exclusive initially | none |
| Comment reaction PUT/DELETE | actor + post/comment author | shared write | sorted shared pairs | shared | exclusive target |
| Submit report | complete actor/source set before source locks | source access guard | source pairs where required | shared if content | shared if comment |
| Staff content action | actor + source author | shared staff write | no ordinary block bypass | exclusive post, or shared for comment | exclusive if comment |
| Staff sanction | actor + target | exclusive from first acquisition | none | none | none |
| Resolve/read evidence | actor, plus owner-command set for action | final mode acquired first | owner command only | owner command only | owner command only |

All immutable scope IDs are pre-read then revalidated after guards. No lock upgrades,
reverse callbacks, network under locks or unbounded retries. Report row locks come
after owner source locks so a concurrent submission cannot reverse lock order.
Non-idempotent commands surface a retryable database conflict; no automatic replay.

| Gate | Required test matrix |
|---|---|
| Interactions | depth normalization/cross-post FK/root trigger; blocked/deleted root with visible children and no identity/text; anonymous/owner/outsider/bilateral block/inactive account/all parent audiences; private membership loss and public nonmember writes; edit If-Match/idempotency/no-op; reaction replace/remove/reactivate stable ID/version; concurrent duplicate PUT; current counts equal filtered actors; bounded batch query count |
| Reports | reporter/staff/wrong-community IDOR; duplicate concurrent unresolved reports; private evidence after source deletion; current membership/staff sanctions; self/owner/equal-role denial; restore versus author delete; action/report/audit/outbox rollback on failure |
| Feed | complete source union/personal timeline/mute scope; equal timestamps/dedup; cursor tamper/viewer/scope/expiry; cutoff/new eligibility/edit chronology; prefetched tail/last examined/high rejection/no loops; current block/unfollow/membership/deletion before hydration; SQL-object parity/batched enrichment; representative measured benchmark |
| Delivery | migration fresh/upgrade with V0001–V0021 unchanged; architecture/format/full verify; OpenAPI/Compose; full-history secret scan + positive control; separate phase records; real slice hashes then separate READY_FOR_REVIEW mailbox |

## Baseline checks and remote delivery

Main independently verified Phase 4: Java 21 full verify 182 pass (39 unit +143
integration), zero failures/errors/skips; full-history pinned secret scan and
synthetic control pass; Compose app/media config and whitespace pass. These checks
cover the baseline, not Phase 5/6 acceptance.

Origin is `https://github.com/phifin/core-social-flyvo.git`. Main successfully
fast-forwarded remote master from `552c4ca` to
`6cadc586636e70dc28cb63aba81464c6241cda2e`, verified by ls-remote, including accepted
Phase 4 and the activated package. The exact 31-commit outgoing history scan passed.
CI run 38114401976 was queued at the last check; remote success is not claimed.
This documentation-only delivery record follows that verified push; any subsequent
publication of this record is reported by main without embedding its own hash here.
Development must hand back locally even when its tests pass.

Main's archival commit `4dd092c` copied the previously reviewed non-secret code-hash
text into a new path, producing the same scanner heuristic at archive line 482.
Main added only that commit/path/rule/line fingerprint as publication metadata,
preserving the original archive and default detection. This is not a new code
finding, credential leak, broad exclusion or history rewrite. Full-history scan
and external positive control are rerun before push.

## Next action

Main reviews the concrete P56-B1 evidence/proposed ADR and the verified content
slice. Decide the narrow dependency or explicitly narrow USER report scope, record
the decision and route IN_PROGRESS back to `Develop Phases 5–6 — Interactions,
Moderation & Feeds`. Development then completes reports → full Phase 5 gate → feeds
and benchmark → final package checks and READY_FOR_REVIEW in the same assignment.
Use actual HEAD/migration maximum (currently V0022; next available V0023); preserve
all delivered slices and prior acceptance history. No push or phase acceptance is
authorized for development. This handoff is for a concrete architecture blocker,
not a routine per-feature stopping point.
