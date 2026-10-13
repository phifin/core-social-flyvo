# Agent handoff — Phases 5–6

## Routing

- Task: phase-5-6-interactions-reports-feeds
- Round: 2
- State: CHANGES_REQUESTED — P56-R1 resolved; P56-R2 open
- Last writer: main, 2026-10-13 — independent round 2 review and benchmark
- Next owner: development — existing Phases 5–6 chat, reliability assignment below
- Accepted Phase 4 code: `9bdc9996b546d73356f177759806296cea40bc43`.
- Phase 4 acceptance commit: `26d60ce`.
- Review history: [accepted Phase 4](handoffs/phase-4-posts-media.md); R1–R5 resolved.
- Content slice commit: `a75885e09bf752e018ccf51a6de6573dda6856e0`.
- Prior interaction regression: `870959d876c0928898bf35d75bf48149fe3b896d`.
- Verified report code: `19849c96307d687958ede54df0b4fbd9cbf132a7`.
- Round 1 feed/package code: `26dec3845f13f2f1536170b37586ea4287f20aa1`.
- Verified round 2 stabilization code: `21f7a5c9f568666b94c892786d90c16ab202794a`.
- Developer round 2: 102 targeted/257 full pass; 3,200 benchmark requests succeed.
- Main round 2: 257 full pass; independent benchmark FAIL, 3,199/3,200 succeed.
- Dense p95 goal remains unmet; no phase acceptance or production SLA claim.
- Full assignment: [Phases 5–6 package](work-packages/phase-5-6.md).
- Main coordinates/reviews/pushes; the user manually opens the named development chat.

## Assignment and execution order

### Main round 2 verdict — code 21f7a5c, handoff a17d448

**CHANGES_REQUESTED.** Inspected the complete stabilization delta: source-driven
HOME SQL, exact seed bound and optional complete/overflow probe, current canonical
privacy, transaction-local JIT, V0024–V0026, plan/load instrumentation, migration
and feed regressions. No new blocking privacy/cursor defect identified. Source
SHA-256 manifest matches the delivered files; working tree was clean. P56-B1 stays
resolved. P56-R1 is resolved on the independent evidence below, but the combined
package remains unaccepted because the required load gate failed with a real
request error (P56-R2), not merely an unmet latency goal.

Actual independent checks, Java 21:

- `./mvnw -B -ntp verify`: **257 PASS** (47 fast +210 integration), no failures,
  errors or skips; BUILD SUCCESS, 2m10s. Includes 18 FeedsIT, all report/interaction,
  prior-phase regressions and new migration upgrades. Log
  `/tmp/social-p56-r2-main-verify.log`.
- App/media Compose config, whitespace and source manifest pass. V0001–V0023 are
  unchanged. Pinned default redacted Gitleaks: **42 commits, no leaks** through
  `a17d448`; unchanged two historical fingerprints. External synthetic positive
  control passes. Logs `/tmp/social-p56-r2-main-secrets.log` and
  `/tmp/social-p56-r2-main-positive.log`.
- Ran the committed heavy tool without formatting, source/config modifications or
  concurrent Maven tests: `./mvnw -B -ntp test`
  `-Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest`
  `failsafe:integration-test failsafe:verify -Dit.test=FeedBenchmarkTool -Dfeed.benchmark=true`.
  Its 14 fast prerequisites pass; the **heavy test FAILS**, BUILD FAILURE, 4m47s.
  Log `/tmp/social-p56-r2-main-benchmark.log`; Failsafe report
  `target/failsafe-reports/com.flyvo.socialcore.feed.FeedBenchmarkTool.txt`.
  Logs under /tmp/target are temporary; the exact outcome is retained here.

P56-R1 independent result: on the million-row fixture, both pools' no-source,
rare-old, last-page and exhausted-continuation plans visit **0/3/1/0 post nodes**.
All 1,200 sparse/private/overlap scenario requests succeed; no unrelated global
scan or false exhaustion was observed. The 18 real-PG feed tests include complete
versus overflowing window, privacy before LIMIT, source overlap and JIT rollback.
This supports closing the original finding independently of dense reliability.

#### P56-R2 — P2 OPEN: required dense load gate still loses a request

Anchors: `src/test/java/com/flyvo/socialcore/feed/FeedBenchmarkTool.java:320–336`
(request/failure capture) and `:397` (all-requests-success assertion);
`src/main/resources/application.yml:12–16` (default pool/timeouts).
This is an experimentally observed acceptance-gate failure. A precise production
root-cause location is not yet established; do not label it a proven SQL timeout.

Main's unchanged-code run produced **3,199/3,200 successes**. Pool 10's steady dense
burst returned only 499/500 requests. The final assertion reports exactly
`pool=10 scenario=post_burst_steady CannotCreateTransactionException`.
Pool 10 first burst and both pool 50 dense bursts returned 500/500; every sparse
scenario returned 100/100 per pool. The tool correctly fails rather than hiding
the lost request. Do not remove or weaken that assertion.

| Dense burst | Success/attempt | p95 /p99 ms (successful requests only) | Mean acquire /candidate execute ms |
|---|---|---|---|
| pool 10 first | 500/500 | 3115.115 /4397.559 | 761.406 /159.119 |
| pool 10 steady | 499/500 | 2597.478 /3828.671 | 598.313 /145.258 |
| pool 50 first | 500/500 | 2290.245 /3484.887 | 145.756 /808.015 |
| pool 50 steady | 500/500 | 1653.215 /1809.649 | 0.491 /775.652 |

The measured pool wait plus earlier developer acquisition failures make pool
contention a plausible hypothesis, not proof of this exception's cause. The tool
records only the wrapper class, losing cause/SQLState and failure-time pool state.
The shared host may influence timing; neither that possibility nor a previous
successful run makes this required failed check a pass. Dense p95 <500ms is still
a separate unmet goal; it is not newly promoted to an absolute acceptance SLA.

Required outcome and one sequential development assignment:

1. Improve test-only failure diagnostics: safe cause classes/SQLState, failed
   request timing, pool active/idle/pending/acquisition statistics and available
   CPU/memory/DB-wait evidence. Do not print tokens, connection credentials,
   private SQL bindings or raw user content. Retain failed attempts in measurements;
   explicitly distinguish success-only percentiles and error rate.
2. Establish the cause, then fix measured source-query/transaction resource usage
   or the relevant implementation defect within the current architecture. Preserve
   the R1 source-work fix, coherent policy, all source branches and cursor contracts.
   Do not silently raise timeouts/pool defaults, reduce readers/requests, serialize
   the benchmark, swallow/retry failures until green, change hardware assumptions
   without reporting them, add caches/fan-out, or relax the all-success gate.
   If evidence instead proves an external resource blocker or needs a materially
   different capacity/architecture contract, report that evidence and a concrete
   decision proposal rather than self-waiving the gate.
3. Deliver regression coverage for the diagnosed cause; rerun targeted/full verify,
   Compose/OpenAPI/secret checks and the complete committed benchmark on the final
   source. Demonstrate repeatability with two consecutive final-source benchmark
   runs at pool 10/50, retaining all outcomes, not cherry-picking successes. Record
   both sparse physical work and dense errors/latency/acquisition. Main reviews
   the actual outcomes before acceptance; no public capacity claim.

Reuse `Develop Phases 5–6 — Interactions, Moderation & Feeds`; set IN_PROGRESS,
increment the delivery round to 3, preserve findings and record the concrete plan.
Commit verified fixes first, then a separate mailbox referencing real hashes and
responding to P56-R2. Do not self-resolve, push or start Phase 7/8. Main remains
reviewer/orchestrator; no executable changes or push were made in this review.

### Historical round 1 review

### Main round 1 verdict — code 26dec38, handoff e7f9065

**CHANGES_REQUESTED.** Reviewed the interaction/report/feed implementation and
security, transaction, SQL, migration, HTTP and test changes from the accepted
Phase 4 baseline. P56-B1 implementation is resolved: the guarded profile owner
proof uses the current authorized summary, preserves the narrow ADR-014 dependency
and has real privacy/block/account ordering and replay regressions. No additional
blocking privacy/atomicity finding was identified in this round. This does not
accept Phase 5 or Phase 6; keep the combined package pending stabilization review.

Main independently ran Java 21 `./mvnw -B -ntp verify`: **248 PASS** (47 fast +201
integration), zero failures/errors/skips, BUILD SUCCESS in 2m41s. Log:
`/tmp/social-p56-main-review-verify.log`. App/media Compose config and whitespace
pass; applied V0001–V0021 are unchanged. Pinned redacted Gitleaks full history:
**39 commits, no leaks**, unchanged two exact fingerprints; external synthetic
positive control passes. Logs `/tmp/social-p56-main-review-secrets.log` and
`/tmp/social-p56-main-review-positive.log`. Main inspected the developer benchmark
tool/results but did not rerun its full 50-reader fixture; the independent sparse
source probe below is separate evidence, not a replacement benchmark.

#### P56-R1 — RESOLVED by main round 2 (original priority P1)

Disposition 2026-10-13: source-selective work and complete/overflow proof checked
at `21f7a5c`; all independent sparse scenarios pass with 0/3/1/0 post-node visits.
Original finding/evidence below is preserved. New dense failure is P56-R2, not a
reopening or a claim that R1's unrelated-history defect still exists.

Anchors: `JdbcPosts.java:151–177` (feedCandidates), `Feeds.java:120–129`,
`FeedBenchmarkTool.java:267–270`. HOME leaves the root scoped predicate empty,
walks the global published chronology, then runs the per-candidate LATERAL owner
proof until it finds 100 eligible rows or reaches the end. A new user with no
sources therefore visits unrelated history; a sparse source or exhausted cursor
has the same risk. The 1,000/200ms application budget cannot stop this first SQL
call, which has not returned any candidates/progress yet. No full-sort is needed
for the bad plan, so the existing no-sort regression does not catch it.

**Independent reproduction:** fresh isolated pinned PostgreSQL 18.6, all V0001–23
DDL, two synthetic active accounts, 1,000,000 PUBLISHED/PUBLIC personal posts by
one account; viewer has no posts/follows/friends/memberships/recipients/mutes/blocks.
Called the actual compiled `JdbcPosts.feedCandidates` with HOME/limit 100/current
cutoff; no copied policy/query, application edits or user database were involved.
With the production-equivalent server `statement_timeout=5000` and no JDBC timeout,
two serial calls returned zero rows in **4811.932 /4929.024ms**. **All four concurrent
calls failed with PostgreSQL statement timeout**, at 5025–5027ms. This already
occurs with only four requests, not the 50-reader goal. An earlier JDBC 5s run
timed out both serial/four concurrent calls; timing variation is reported honestly.
At 200k posts, the same empty query took 735/861ms. This is a reproducible request
failure/scaling defect, not merely the developer's p95 goal miss.

Probe source `/tmp/P56SparseProbe.java`; logs
`/tmp/social-p56-main-sparse-probe.log`,
`/tmp/social-p56-main-sparse-million.log`,
`/tmp/social-p56-main-sparse-server-timeout.log`. The probe's temporary container
and synthetic database are disposable; port/container need recreating to rerun.
Development must add a permanent isolated regression/tooling scenario, not depend
on these temporary artifacts or benchmark only repeated dense first pages.

Required outcome: source-selective HOME planning that does not linearly traverse
unrelated global history for absent/rare/exhausted sources. Preserve every source
branch, dedup-before-pagination, owner privacy before eligible LIMIT, coherent
hydrate and last-examined continuation. A source-driven indexed union/EXISTS plan
is an option to measure, not a prescribed untested rewrite. No empty-result catch
on timeout, pre-visibility LIMIT with false exhaustion, timeout increases, graph
materialization in Java, authorization cache, Redis or fan-out workaround.

Regression/measurement must include no sources, one rare source with old posts,
exhausted continuation, mostly blocked/muted/private candidates and overlapping
sources on the million-row fixture. Record actual EXPLAIN/physical rows/buffers,
SQL counts, successful result/continuation and concurrent latency; retain current
privacy/race/cursor/parity checks. Cover the ordinary default pool 10 as well as
the explicitly labeled benchmark pool 50. Avoid flaky wall-clock-only unit gates;
use meaningful query-plan/work assertions plus explicit heavy performance evidence.

#### Latency decision and next development package

The dense benchmark p95 1723.410/821.781ms remains an honest unmet initial goal;
it is not proof of a production SLA and does not justify removing a test or raising
timeouts. The assigned package allowed reporting the goal outcome, so that number
alone is not a new architecture blocker. P56-R1 is independently blocking. Profile
planning/execution/pool waits while fixing it, rerun both dense bursts and the new
sparse/deep-page cases, and retain all misses in evidence. Main will evaluate any
residual latency gap separately after correctness/work-bounding is fixed.

Reuse `Develop Phases 5–6 — Interactions, Moderation & Feeds`, set IN_PROGRESS and
deliver one coherent stabilization assignment: (1) source-query optimization and
necessary measured indexes, (2) permanent regressions plus expanded benchmark and
profiling, (3) full package verification and updated evidence. New migrations start
after actual max V0023; never edit applied files. No repeated small handoffs; stop
only for a concrete new contract/external blocker. Commit verified slices, then a
separate real-hash mailbox, respond to P56-R1 and return READY_FOR_REVIEW. Main alone
resolves/accepts/pushes. No implementation fixes in main, no Phase 7, no push now.

### Original package assignment

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

### P56-R1 developer completion response — round 2, pending main review

Actual code: `21f7a5c9f568666b94c892786d90c16ab202794a`, based on main review
`2cc3e61`. Delivered the entire stabilization package and preserved main's finding
text/disposition. Only main resolves P56-R1 or accepts either phase. P56-B1's main
resolution and accepted ADR-014 remain unchanged; no dependency/evidence-access
widening, push, agents or Phase 7 work.

HOME enumerates current author/community/direct-CUSTOM/own sources inside the
approved content adapter, with V0024–V0026 measured covering owner indexes.
Shared canonical privacy applies before eligible Top K; UNION deduplicates before
global pagination. Eligible seed keys can bound author seeks exactly; without a
seed the query retains per-author Top K. Direct recipient IDs are batched only
inside PostgreSQL, guarded against an empty source. No graph IDs leave SQL.
Current coherent body/source hydration, viewer-bound encrypted cursor, last
examined key, source completeness, privacy/guard order and 100/1,000/200ms refill
contracts remain. No foreign base-table read/write, applied migration edit,
permission cache, new dependency/view, timeout increase or denial-path bypass.

For >1,000 effective author sources with an eligible seed, an optional chronology
interval probe visits at most 1,001 keys. Only a complete <=1,000-key interval may
supply author candidates; otherwise all probe results are discarded and the query
uses parameterized per-author seeks. Raw truncation never establishes exhaustion
or advances a cursor. No-source/rare/no-seed paths skip the probe. The permanent
regression checks complete/overflow paths, inactive author and exact 1,001 work;
it caught and prevented a planner-chosen global fallback. The final million-row
dense case overflows and uses source seeks, so the fast path is not claimed as the
cause of its measured timings. LOCAL JIT off is an evidence-based HOME-read tuning,
not a timeout change; real PG tests verify restoration after success/rollback.

Final code checks (logs in ignored mode-700 `.local/evidence/p56-r2/`):

- **102 targeted PASS**, 23 fast +79 PG (22 interactions, 22 reports, 18 feeds,
  17 foundation), zero failures/errors/skips; `package-targeted.log`.
- Java 21 `./mvnw -B -ntp verify`: **257 PASS**, 47 fast +210 integration,
  zero failures/errors/skips; `verify.log`. Includes all 33 Phase4IT, 12 real pinned
  StorageContractIT, packaged decoder/Compose secret isolation, previous modules,
  report evidence/atomicity/revocation races, feed privacy/cursor/coherent hydration,
  SQL/object parity and fresh/upgrade checksum/data preservation. Formatting,
  Enforcer/convergence, architecture/SQL allowlist and OpenAPI pass, no exclusions.
- **1 heavy benchmark +14 prerequisites PASS**, `benchmark.log`: 1M initial
  posts/10k follows/skew/blocks/private/overlap; pool 10 then explicitly labeled
  50; **3,200/3,200 requests succeed** with asserted result/dedup/continuation.
  No-source/rare/last/exhausted physical post-node visits **0/3/1/0**, chronology
  probe work zero, independent of unrelated history. Pool-10 candidate execution
  **0.916/1.182/1.344/1.209ms**; actual plans/buffers recorded for both pools.
  Sparse p95 pool10/50: no-source 66.929/33.264ms, rare 237.012/178.717ms,
  last 360.020/372.195ms, exhausted 180.134/202.147ms.
- Dense first/steady p95: pool10 **2312.281/2225.788ms**, pool50
  **1713.901/1146.091ms**; **<500ms initial goal NOT MET in any burst**.
  Each burst has 500 successful 20-item requests; app scan ratio 1.000;
  actual JDBC **12 SQL/request**, including plain cutoff/JIT statements, not
  driver wire-level transaction commands. Pool10 mean acquisition 599.893/521.472ms;
  pool50 107.535/0.174ms. Planning/execution/buffers, hardware, fixture counts,
  SQL/profiling definitions and all sparse/private/overlap metrics are committed
  in separate [Phase 6 evidence](phases/phase-6.md). [Phase 5 evidence](phases/phase-5.md)
  records current report/interaction regression gates separately.
- App/media Compose config, local documentation links and staged/unstaged whitespace
  pass. Source SHA-256 manifest matches final verified files. Earlier failures and
  the previous successful load are retained, not substituted for final numbers;
  unavailable pre-reset `/tmp` artifacts are explicitly historical.
- CI-pinned Gitleaks8.28.0 default redacted staged code scan PASS (`staged-code.log`),
  **41-commit full history through actual code/no leaks** (`history-code.log`).
  Exactly the same two accepted historical fingerprints; no new exclusions/rules.
  External synthetic generic-api-key control PASS (`secret-positive.log`), expected
  detector exit1/wrapper0, redacted/outside git/cleaned. This separate mailbox is
  patch-scanned and history-scanned after commit; its own hash is not embedded here.

Material limitations: shared four-core/eight-thread i7/~7.5GiB host, synthetic
fixture and a successful run do not establish production capacity or eliminate
load variability; earlier dense acquisition failures remain in evidence. Dense
latency goal is still unmet and requires main's residual-gap review. The work
budget cannot interrupt the first in-flight SQL; related private/source-degree
work remains, while unrelated history cannot cause the former empty/sparse scan.
The optional dense probe adds at most 1,001 node visits and requires complete-window
proof before use. Three standard transactional index builds need the controlled
DDL window in operations docs. Weak cursor consistency, already authorized old
responses, initial reaction serialization and accepted Phase4 provider/storage/
decoder/key/retention/public-launch gates remain. No required local gate is unrun;
no production/provider smoke, remote CI, deployment or SLA is claimed.

### Recorded round 2 stabilization plan — initially IN_PROGRESS

Clean review HEAD `2cc3e61`; executable baseline `26dec38`. Preserve main's
P56-R1 text/disposition and resolved P56-B1. Change only the content-owned HOME
candidate SQL: indexed author/community/recipient source branches, current owner
privacy before each eligible LIMIT, deduplicate before global pagination. Measure
plans before adding any index; existing maximum is V0023, new DDL would be V0024.
No API/schema/cursor/timeout/cache/dependency change is planned. Read-only source
and coherent hydrate remain one statement each; no new business locks or changes
to account → community → graph → post → comment lock order.

Permanent real-PG regressions will assert source-index work (not wall-clock-only)
for no sources, rare old posts, exhausted continuation, blocked/muted/private
sources and overlapping branches, retaining all privacy/race/cursor/parity checks.
Expand the isolated million-row tool with these scenarios, actual EXPLAIN rows/
buffers/SQL counts and concurrent result/progress assertions; measure default pool
10 and explicitly labeled pool 50, dense first/steady bursts, SQL execution and
connection acquisition waits. Then targeted package tests, formatting/full verify,
OpenAPI/Compose, documentation checks and pinned history/patch secret scan plus
external positive control. Commit verified stabilization and separate phase
evidence before the real-hash READY_FOR_REVIEW mailbox. No agents/push/accept/Phase 7.

### Historical round 1 package delivery and actual checks — 2026-10-11

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

### P56-B1 — RESOLVED by main round 1 (decision and implementation)

Disposition: actual guarded owner proof, narrow dependency enforcement and real
ReportsIT regressions checked at `26dec38`; full independent verify passes. The
decision/developer history below is preserved. Resolution does not accept the
package while P56-R1 is open.

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

Development addresses P56-R2 in the existing Phases 5–6 chat using the three-part
reliability assignment above. Preserve resolved P56-B1/P56-R1, start from code
`21f7a5c`, inspect actual max migration V0026 and never edit applied files. Return
verified code, complete final-source evidence and a real-hash READY_FOR_REVIEW
handoff for round 3. No phase acceptance, next-phase activation or push yet.
