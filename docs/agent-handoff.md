# Agent handoff — Phases 5–6

## Routing

- Task: phase-5-6-interactions-reports-feeds
- Round: 1
- State: READY_FOR_REVIEW — full Phase 5–6 package, not accepted
- Last writer: development, 2026-10-11 — completed after decision `7e4d083`
- Next owner: main — independent package review, including the measured latency gap
- Accepted Phase 4 code: `9bdc9996b546d73356f177759806296cea40bc43`.
- Phase 4 acceptance commit: `26d60ce`.
- Review history: [accepted Phase 4](handoffs/phase-4-posts-media.md); R1–R5 resolved.
- Content slice commit: `a75885e09bf752e018ccf51a6de6573dda6856e0`.
- Prior interaction regression: `870959d876c0928898bf35d75bf48149fe3b896d`.
- Verified report code: `19849c96307d687958ede54df0b4fbd9cbf132a7`.
- Final feed/package code: `26dec3845f13f2f1536170b37586ea4287f20aa1`.
- Comments/reactions/reports/feeds are implemented and verified; no phase acceptance.
- Benchmark complete; p95 <500ms goal unmet on recorded hardware (details below).
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

### Full package delivery and actual checks — 2026-10-11

Completed the entire assignment sequentially, not a per-module handoff. Preserved
the delivered interaction slices, implemented four-target reports at `19849c9`,
passed the complete internal Phase 5 gate before feed implementation, then delivered
home/community/personal timeline and final regressions at `26dec38`. Only accepted
ADR-014's narrow moderation→ProfileQueries.visible edge is added, enforced by the
architecture test; no new library/service, foreign SQL consumer, exemption,
authorization cache or Phase 7 implementation. V0022/V0023 are new; previously
applied migrations remain unchanged. Feed reuses measured existing indexes, no V0024.

Reports use exact current guarded owner proofs, bounded encrypted private evidence,
unresolved uniqueness, current scoped staff/ADMIN authority, attributable evidence
audit and atomic source/case/action/audit/outbox. USER cases remain global-only and
do not enable account suspension or private profile lookup bypass. The final narrow
guard regression distinguishes missing target 404 from inactive reporter 401.
Feed uses scoped owner API candidates/current coherent hydration, immutable
chronology, DB cutoff, encrypted 30-minute viewer/scope/mode/size-bound cursors,
examined-key continuation and 100/1,000/200ms refill bounds. Candidate-ID LATERAL
owner proof preserves index traversal without a pre-visibility LIMIT. Counts/names
are batch-authorized decoration, never permission. No cached/private fallback.

- Internal Phase 5: **70 targeted /231 full PASS**, zero failure/error/skip before
  feeds, logs `/tmp/social-p56-phase5-targeted.log`,
  `/tmp/social-p56-phase5-verify.log` at report code above.
- Final package targeted: **93 PASS** (23 fast +70 real PG: 22 interactions,
  22 reports, 12 feeds, 14 foundation), no failure/error/skip;
  `/tmp/social-p56-package-targeted.log`. Actual commands/test matrix in separate
  [Phase 5](phases/phase-5.md)/[Phase 6](phases/phase-6.md) evidence.
- Final Java 21 `./mvnw -B -ntp verify`: **248 PASS** (47 fast +201 integration),
  zero failure/error/skip; `/tmp/social-p56-package-verify.log`. All 33 Phase4IT,
  12 real pinned Silo StorageContractIT, packaged decoder, Compose secret isolation,
  prior identity/community/graph and migration upgrades included. Formatting,
  Enforcer/convergence, architecture/SQL allowlist and OpenAPI PASS, no exclusions.
- App/media Compose config, changed-doc local links and staged/unstaged whitespace
  PASS. No production/provider smoke, remote CI, deployment or push claim.
- Pinned isolated benchmark: **1 heavy test PASS +14 fast prerequisites**, 1M posts,
  10k follows/4k blocks/1k mutes per reader, skewed communities, **50 readers and
  1,000 measured requests**, each returning 20; actual SQL **10/request**, app scan
  ratio 1.000. First/steady burst p50 386.772/393.154ms, p95
  **1723.410/821.781ms**, p99 2564.168/1216.902ms; 74.766/101.817 req/s.
  **Initial p95 <500ms target NOT MET in either burst**; not an unrun benchmark or
  successful capacity/SLA claim. Completed earlier run also missed (1156.072ms).
  Full fixtures/hardware/commands/probes/plans/failed attempts in Phase 6 evidence;
  `/tmp/social-p56-feed-benchmark.log`. Test-only pool 50, production default 10
  unchanged. SQL/statement timeout remains 5s; no constraint relaxation.
- Measured home candidate EXPLAIN: Limit → Nested Loop → post_chronology, 295
  physical rows/100 eligible keys, no full eligible-set sort; 12.347ms execution,
  5,053 hits/0 reads. Hydration bounds 100 IDs, 7.551ms, 2,545 hits/0 reads.
  Actual JDBC count/plan and current SQL/object policy parity regressions pass.
- CI-pinned Gitleaks 8.28.0 redacted report/feed staged scans PASS; full history
  through final code **38 commits/no leaks**, only the two accepted exact historical
  fingerprints, no rule/exclusion change; `/tmp/social-p56-package-history-code.log`,
  `/tmp/social-p56-feed-staged.log`, `/tmp/social-p56-reports-staged.log`.
  External generic-api-key positive control PASS (detector exit 1, wrapper 0,
  redacted/outside git/cleaned), `/tmp/social-p56-package-secret-positive.log`.
  This separate handoff is scanned after commit; its own hash is not embedded here.

Material limitations: **latency goal remains unmet** on the shared i7-1165G7/8-CPU,
~7.5GiB host; test pool/load are not production capacity. Before a public SLA,
profile statement planning/JVM/PG waits, tune measured owner SQL and revalidate on
target hardware and actual production pool. Do not add unassigned cache/fan-out to
mask it. The work package explicitly requires recording goal outcome; main must
review this gap. The 200ms query-work budget stops refill, not in-flight SQL; cutoff
pagination is not a frozen snapshot, and already authorized old responses cannot
be revoked. Initial reaction serialization and accepted Phase 4 storage/provider/
physical upload bound/decoder/key/legal-retention/public-launch gates remain.
No required verification is unrun; no acceptance or Phase 7 authorization is implied.

### Resumed execution plan recorded at IN_PROGRESS — ADR-014 accepted

Internal Phase 5 gate PASS at report commit
`19849c96307d687958ede54df0b4fbd9cbf132a7`: 70 targeted /231 full verify tests,
zero failures/errors/skips. Phase 5 evidence updated; no acceptance. Continue
Phase 6 now. Scoped feed candidates/hydration stay in the approved content SQL
adapter; lightweight HOME/COMMUNITY/TIMELINE scopes recheck both current audience
and current source eligibility in the coherent body statement. Feeds never import
SQL/repositories or materialize the graph. Database cutoff, encrypted 30-minute
viewer/scope/size-bound cursor, examined-key continuation, 100/1000/200ms bounds,
batched attribution/engagement and low-cardinality metrics are the concrete plan.
Only measured query evidence may justify V0024; no feed table/new infrastructure.

Clean HEAD `7e4d083`; preserve delivered content slices and all migrations through
V0022. Implement V0023 reports with OPEN/IN_REVIEW/RESOLVED/DISMISSED versions,
partial unresolved reporter/typed-target uniqueness, bounded encrypted owner-returned
evidence and report/action FK correlation. Reporter endpoints expose case metadata,
never evidence; separate community MODERATOR+ and platform ADMIN queues/evidence/
review/resolution endpoints recheck current scope and audit evidence reads. USER
cases have no caller community scope. Global case handling has no account sanction.

Add only the ADR-014 moderation→profile API edge. Extend existing owner proof APIs
as necessary for transaction-required target guards; moderation never imports graph
or owner internals/SQL. USER proof uses the exact authorized ProfileQueries.visible
summary inside the command, including on logical idempotency replay. Evidence is
encrypted with existing Secrets, contains no storage URL, and never enters outbox.

For report submit: complete sorted shared account set → community read guard when
scoped → owner graph guards → source post/comment/profile → idempotency/report row.
For case actions: pre-read immutable owner lock set, sorted shared accounts → final
community mode (exclusive for sanctions) → owner post/comment → report row; resolve
only after owner transitions, with expected case/source versions so conflicting
resolutions roll back the entire action. No upgrades, automatic command replay,
network effects or reverse lock callbacks. Audit/outbox/action/case are atomic.

Tests include four owner-proof target matrices, USER privacy/block/account orderings
and replay, reporter/wrong-scope/staff/ADMIN IDOR, evidence after source deletion,
concurrent unresolved submission, hierarchy/self/owner denial, author deletion versus
restore, both staff revocation orders and injected audit rollback. Add fresh/upgrade
V0022 checksums. Run targeted, formatting and full Phase 5 verify before feed code.
Continue existing feed plan and representative 1M/10k/skew/50-reader benchmark,
then final package gates and separate real-hash READY_FOR_REVIEW handoff. No agents,
push, self-acceptance or Phase 7.

### Historical interaction-only delivery at blocker handoff

The following report/checks were recorded at `870959d`, before main's ADR decision;
its partial/unimplemented wording is preserved as history, not current routing.

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

### P56-B1 — decision ACCEPTED by main; implementation pending

Main decision (2026-10-11): accept [ADR-014](adr/014-report-user-target-proof.md).
Retain POST/COMMENT/COMMUNITY/USER report scope; allow only moderation-to-profile
owner API access for USER submission proof. No UUID-only proof, attribution bypass,
foreign SQL or policy relay. The original blocker/developer response below is
preserved as history; its pending-approval wording no longer controls routing.

Resume the same development chat, set IN_PROGRESS and implement reports with the
narrow ModuleRules map update and denied-access/concurrency regressions. Use the
authorized summary as evidence, test privacy/block/account changes and idempotent
replay, and keep USER cases out of community staff queues unless independently
authorized as platform ADMIN. Do not add Phase 8 account suspension. Finish the
full Phase 5 gate before Phase 6, then benchmark and final package delivery.

Main checked the actual ProfileQueries/Profiles implementation, dependency map,
ADR-002 and architecture/security/domain contracts at HEAD `030f18a`. This is a
decision review, not independent acceptance of content code `870959d`. Developer's
207-test evidence below remains developer-reported; no full suite rerun for this
documentation-only decision. Main will review the complete package and both slices.
Documentation diff/links/consistency are checked before committing. No push now:
the newer executable slices have not completed main review.

#### Developer completion response to P56-B1 — pending main review

Implemented the accepted ADR without widening its scope. Moderation calls only
guarded ProfileQueries.visible and authorized Summary accessors; the architecture
regression explicitly enforces that restriction. Sorted account → graph → exclusive
profile guards capture the same current summary used for evidence, including on
idempotent replay; a locked refresh prevents an earlier managed entity from retaining
an old privacy grant. ReportsIT tests PUBLIC/AUTHENTICATED/FRIENDS/PRIVATE, bilateral
blocks/inactive targets, privacy/block/account changes in both real lock-wait orders,
IDOR/global versus community isolation, encryption/no-public-evidence and audited
staff scope revocation. Report code `19849c9` and final regression `26dec38` pass
the gates above. Request main independently verify this implementation response;
development does not mark P56-B1 resolved or accept either phase.

#### Original blocker evidence and developer response

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

### Historical checks — interaction-only baseline 870959d

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
consistency is current source near read time, not a snapshot; report/feed work was
blocked at developer handoff, now unblocked by main's P56-B1 decision above.
Accepted Phase 4 provider/storage/physical upload bound/decoder/
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

Main reviews report `19849c96307d687958ede54df0b4fbd9cbf132a7` and final package
`26dec3845f13f2f1536170b37586ea4287f20aa1`, both separate phase records and the
P56-B1 developer response. Independently check current evidence/privacy/lock order,
source SQL/object parity/cursors/races and recorded benchmark/latency limitation.
Only main decides findings/acceptance and any authorized push. No automatic Phase 7
start. If changes are requested, route named findings to this same development chat;
do not implement review fixes in main. Actual maximum V0023, earlier migrations
unchanged. This is the full assigned package handoff, not a small-feature stop.
