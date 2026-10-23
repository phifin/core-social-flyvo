# Agent handoff — Phase 9A-II source erasure and completion

## Routing

- Task: phase-9a-ii-sources-media-completion
- Round: 1
- State: CHANGES_REQUESTED — main round1; isolated synthetic scope only
- Last writer: main reviewer, 2026-10-23
- Next owner: development, existing chat below
- Chat name: `Develop Phase 9A-II — Sources, Media & Completion`
- Delivered code commit: `6d3fbfc985cfa1e407e331d79ac5d25ffd3ae3b0`
- Assignment baseline: `61623032ceb09537b1598d914fac3bb747c39302`
- Accepted I executable: `bbbde93c170246372895907dd4e446696521452d`
- G1 acceptance: `faf0ad851496f3708fb0f66f4ca28d7faee294d5`
- Accepted design: ADR-017 D01–D10 with C01–C05, local synthetic only
- Accepted I archive: [full review and evidence](handoffs/phase-9a-i-erasure-foundation-identity.md)
- Main remains reviewer/docs/orchestrator only and alone may push origin.
- One user-activated thread/writer; no spawned agents or concurrent checkout work.

## Binding assignment

Read AGENTS and thread/git workflows, then the complete
[Phase9A-II assignment](work-packages/phase-9a-ii.md),
[full plan / test matrix](work-packages/phase-9a-plan.md),
[inventory](work-packages/phase-9a-data-inventory.md),
[ADR-017](adr/017-account-erasure-and-recovery.md), ADR-016 and the affected
architecture/security/database/owner subsystem contracts. Inspect actual code,
status/index and new migrations before editing. Set IN_PROGRESS and write a
concrete owner/API/lock/proof/migration/test map.

Implement all II.1–II.4 sequentially in this one assignment:

1. Group A: community/graph participation plus content reference repair.
2. Group B: authored byte retention/redaction plus all-state/all-version media
   enumeration, external receipts, writer drain, purge and accounting.
3. Group C: notification/search terminal fences and cleanup; moderation structural
   inventory, C01 pending-case liveness and independent preservation exports;
   C02 nonrecursive certificate and strict atomic finalization.

The linked assignment defines each internal gate, required interleavings,
100-item bounds/large fixtures, retained exceptions and delivery checks. Do not
handoff merely after a small module if assigned safe work remains. A genuine
security/architecture/authority conflict needs precise evidence and a bounded
proposed decision; never weaken denial or invent a production guarantee.

The assigned starting migration was **V0038**, after inspecting baseline HEAD.
Delivery adds consecutive **V0038–V0051**; V0001–V0037 are unchanged. The older
tentative V0037–V0042 allocation is superseded; III numbers remain unallocated. No placeholder migrations, new module DAG edge/SQL consumer or generic
workflow engine. Existing account.api owner acknowledgement/worker fencing is
the foundation, not permission to call higher-module internals.

## Inherited conditions and review findings

P9AI-R1/R2 are resolved at G1, not fresh tasks. Preserve their post-lock DB-time /
inherited proof deadlines and exact request/generation/round/execution receipt
checks. Keep their regressions passing, including superseded success/failure.
C01–C05 bind this implementation; no private payload may be waived to obtain DONE.
Retain the fixed12-owner roster; owner activation requires real handlers,
proofs and historical reconciliation, not empty/fake acknowledgements.

Main round1 records **P9AII-R1 and P9AII-R2 OPEN** below. G2 is not accepted.
Development must respond to each ID without replacing the original finding or
self-resolving it. Review history is preserved in this file and the I archive above.

## Verification and delivery

Targeted gates then Java21 formatting/full verify; real PostgreSQL/Silo/archive,
fresh/V0037/V0032 upgrades and new prefixes/checksums/rollback, architecture,
OpenAPI and SQL/object-policy parity. Real isolated native app/media probes and
unchanged3200-request pool10/50 benchmark are required. Record all failures and
unrun checks; no skipped Docker, timeout/pool increase or fabricated drain proof.

Update affected owner/schema/API/security/jobs/runbooks, inventory and
`docs/phases/phase-9a-ii.md`. Retain exact executable manifest and durable local
evidence outside ephemeral `/tmp` (synthetic runtime isolation rules still apply).
Run pinned redacted staged/history scans and positive control. Preserve the
three exact reviewed `.gitleaksignore` fingerprints; new findings go to main.

Commit verified code/slice documentation locally, then a separate mailbox commit
with the actual final code hash, per-gate checks/limitations, READY_FOR_REVIEW and
next owner main. No development push, merge, release or deployment.

## Accepted evidence / remote checkpoint

G1 independent full verification on October20:442 PASS (62 fast+380 IT), zero
failures/errors/skips,6m17s, finished20:57:09+07. Evidence root
`/home/vonhuphi/social-p9ai-main-review2.Pke8Z3/`. Prior missing `/tmp` final log is
not a claimed full PASS; the unchanged rerun supplies final evidence. Original
manifest checked before the exact non-secret hash exception; only that reviewed
scan metadata differs. History73 commits/positive control and config checks pass.
Native/benchmark results remain attributed developer evidence, not main reruns.

Before this assignment, main pushed G1 review
`faf0ad851496f3708fb0f66f4ca28d7faee294d5` to `origin/master` at
`https://github.com/phifin/core-social-flyvo.git`, then verified the exact ref.
This is a remote source checkpoint, not remote CI/deployment success. Main owns
any subsequent assignment/checkpoint push; development never pushes.

## Boundaries and carried limitations

Only newly isolated synthetic data/tests may exercise erasure. Default existing
developer environments remain disabled; strict close still needs verified archive.
No real erasure/restore, production provider/SMTP/ledger/key/IAM/legal decisions,
PhaseIII/9B, public launch or unsolicited external messaging. U01–U06 remain open.
Held/unresolved moderation payload, unknown writer completion, missing archive/
resource receipt or unknown owner must remain waiting/failed, not fake DONE.
The500ms feed goal remains unmet; earlier timeout root causes remain unproven.

## Developer report / main review

Complete II.1–II.4 candidate delivered at **`6d3fbfc985cfa1e407e331d79ac5d25ffd3ae3b0`**.
The pre-code [implementation map](work-packages/phase-9a-ii-implementation-map.md)
records the actual owner/API/lock/proof/migration/test plan and refinements.
The [phase record](phases/phase-9a-ii.md) contains final behavior, all16 benchmark
rows, failed-attempt explanations and retained limitations. No small-module early
handoff, agent, push, deployment, III or real-data erasure/restore was used.

| Internal gate | Delivered behavior and evidence |
|---|---|
| A — participation/references | Community and graph bounded owner passes; archived-owner history retained; ACTIVE ownership remains a blocker. Inviter/recipient guards and inbound-block denial serialize against real close in both orders. Current mention/recipient repair narrows last CUSTOM recipient to ONLY_ME, preserving other-author text/revisions/reply IDs. |
| B — authored/media | Prompt denial/soft deletion, DB t0+30d nested redaction, one-way choices, erasure-origin retention protection. All-state/all-generation media manifests and receipts, stable claim fence, outside-lock drain/purge and once-only quota ack. Real PG/Silo/archive tests cover >50 versions/markers, joined late writers, crash-before-ack and stale generation. No runtime drain adapter; unproven writers/storage remain waiting. |
| C — projections/preservation/finalization | Terminal notification/search guards, V0032 watermarks and unrelated actor/read-state preservation; empty-index snapshot/rebuild defenses. C01 existing pending cases resolve with mandatory encrypted detail and atomic proof invalidation. Independent bounded preservation exports/hold authority recheck, audited explicit DEAD resume. Eleven-proof nonrecursive certificate; exclusive subject→ordered step locks and atomic receipt/DONE/DELETED/audit/outbox; stale vector/audit/fence rollback regressions. |

Large synthetic fixtures include10,001 posts, revisions, comments, graph edges,
notification actors, all-state assets, old-generation variant rows and repeated
current mentions. Per-pass collection bounds and actual row deltas, sampled heap,
lock waits, queue/checkpoint behavior and inspected query bounds are distinguished
in the phase record; no aggregate per-pass SQL profiler or capacity guarantee is
claimed. Unknown/corrupt historical DELETED provenance remains visible for an
authoritative decision; no t0/generation or owner proof is invented.

### Inherited finding responses

- **P9AI-R1 — preserved, already resolved by main at G1.** Original post-lock
  DB-time/absolute inherited deadline checks remain. ErasureProofChainIT has21
  passing cases in the final full run. No deadline, refresh, claim or denial fence
  was relaxed to activate II.
- **P9AI-R2 — preserved, already resolved by main at G1.** Exact intent/request/
  lifecycle generation/round/execution receipt matching and superseded success/
  failure regressions remain passing. New media, preservation and completion
  exports enforce their current captured execution/vector/generation; stale ack
  cannot certify a successor or charge its quota. Only the old unimplemented-owner
  count expectation changed to zero; existing matching assertions were retained.

### Final checks

| Check | Actual result |
|---|---|
| Java21 formatting + `./mvnw -B -ntp verify` | **469 PASS** (62 fast +407 IT),0 failures/errors/skips,8m32s, finished2026-10-22 23:52:16+07. Curated50-class XML counts independently match the full log. Includes architecture, OpenAPI, SQL/object-policy parity, new prefixes/checksums/failure rollback/forward recovery and all inherited regressions. |
| Current-source manifest/image |391 executable/config/API/CI paths unchanged through delivery; unchanged-source image build r2 PASS. First build lost Docker RPC on session interruption, retained as failure evidence. |
| Native fresh / V0037 / V0032 | All3 PASS on actual PG/Silo/app; accepted prefix checksums unchanged; real upload/process/post/media/security, CSRF403, stale412, close204, old-session/media401, post404, SSE closure, restart and historical no-data12-proof/certificate finalization. Media subject stays PENDING; old URL denied at real TTL. |
| Unchanged feed benchmark | **3,200/3,200 successes,0 failures**,16 complete scenarios, pool10/diagnostic50,7m18s, finished2026-10-23 19:52:07+07. Dense pool10 p95=3110.893/2642.999ms and pool50=2786.914/2149.009ms;500ms goal remains OPEN. No pool/timeout/lease/planner change or concurrent heavy build/verify/native app. |
| Delivery/config/security checks | Diff/index and Compose config PASS;244 local Markdown links PASS. Pinned redacted staged/history Gitleaks plus external synthetic positive control PASS, unchanged3 exact ignores; final history/mailbox scan metadata is retained outside git. |

Durable evidence: `/home/vonhuphi/social-p9aii-evidence.ll068R/` —
`executable-manifest.json`, `full-verify-r5/`, native probe/bootstrap/isolation/
checksum logs, `native-archives/`, `benchmark.log`/CSV/JSON/reports, scan reports,
`delivery-checks.json` and the final artifact index/provenance. Failed/interrupted
attempts are retained under `attempts/`. The full runner's post-System.exit(0)/30s
fork shutdown and cached stopped-container Hikari warnings are preserved; Maven
exits0 with all469 cases passing. Native r1 used an obsolete dynamic host binding
after a successful restart; corrected harness r2 resolves the actual binding and
all3 fresh fixtures passed. No test exclusion or retry/timeout increase was used.

All3 exact Compose projects/volumes and newly generated fixture storage/keys are
removed after their archives were retained durably. Existing developer data/keys/
volumes were never used. Fixture teardown is not an erasure acknowledgement.

No assigned implementation blocker remains. Material boundaries stay explicit:
real writer quiescence, U01–U06/production retention/IAM/keys/latest-cut authority,
III restore/import/exposure barriers and public launch are unapproved. OPEN/held/
unexpired private moderation, unknown writers, missing receipts and bad provenance
remain waiting/failed. The500ms feed goal and earlier timeout root causes remain
open; this run's zero errors does not resolve those earlier causes.

The developer requested main review at the actual code hash above. The following
main review supersedes that routing, not the developer's historical evidence.
Only main accepts/resolves review findings or pushes; III is not activated.

## Main review — round1, 2026-10-23

**Verdict: CHANGES_REQUESTED.** Reviewed executable `6d3fbfc` against assignment
baseline `6162303`, with developer mailbox `6f0a35d`; no newer executable or local
edits were present. The two findings below are code-inspection findings, not
separately reproduced failing tests. Main changed review documentation only.
Passing existing tests does not cover these missing cases or accept G2.

### P9AII-R1 — P2 — ordinary retention is lost while the author is suspended — OPEN

- Anchor: `src/main/java/com/flyvo/socialcore/content/application/PostRetention.java:53`.
  The added `!author.active()` return covers SUSPENDED as well as
  DELETION_PENDING/DELETED. Suspension is reversible and is not an erasure request.
- Failure scenario: an author edits, deletes or has a post removed, then is
  suspended when its scheduled thirty-day POST_RETENTION runs. The handler exits
  before revision/post/descendant redaction and removed-post media release, and
  before scheduling another bounded pass. `DurableWorkStore` acknowledges that
  return as COMPLETED. `Posts.changed` schedules retention on source mutations;
  reinstatement does not replace the consumed job. Without another source mutation,
  due bytes/bindings can therefore remain indefinitely. This also changes ordinary
  default erasure-disabled environments, where no account-erasure pass can help.
- Required outcome: preserve ordinary retention for SUSPENDED accounts. Narrow the
  erasure-specific bypass to the actual terminal lifecycle/erasure-origin contract,
  preserving the close-before-content-marker race protection and other authors'
  descendants. Do not solve this by relaxing account access or by adding a new
  global maintenance abstraction.
- Regression expectation: real PostgreSQL/durable-worker coverage for due revision,
  deleted-post and removed-media work while suspended, including reinstatement
  after the job, with erasure disabled. Retain terminal close-first and
  erasure-origin descendant-preservation tests with erasure enabled.
- Developer response: pending.

### P9AII-R2 — P2 — preservation exports omit retention eligibility provenance — OPEN

- Anchor: `src/main/java/com/flyvo/socialcore/moderation/application/ModerationPreservation.java:51`
  (`Record` and its construction at lines82–97). Supporting paths:
  `moderation/application/port/CaseStore.java:11`,
  `moderation/application/port/PreservationStore.java:29` and
  `moderation/infrastructure/persistence/JdbcCases.java:32`.
- The exported record carries status, hold deadlines, retention state and
  `recordedAt=c.now()`, but neither the report's `resolved_at` nor the standalone
  action's `created_at`, nor an equivalent authoritative retention-eligible-at
  value. The exported History also omits its original timestamp. `recordedAt` is
  export observation time, not the terminal/age anchor used by JdbcCases.eligible.
- Failure scenario: an old backup still has an OPEN case (or lacks a later
  standalone action), and recovery has only the newer immutable export. Even a
  verified receipt cannot recover the actual ADR-016 365-day deadline from that
  record. Inferring it from export time changes the policy; leaving it unknown
  indefinitely is not the assigned complete export contract. This is a missing
  prerequisite for recovery, not a claim that the unimplemented III importer
  currently deletes evidence prematurely.
- Required outcome: deliver ADR-017 D08's explicit root/version/terminal-eligibility/
  hold-deadline export contract now, as Group C.3 requires. Capture authoritative
  source eligibility provenance under the same root/version observation, with
  explicit unresolved/unknown semantics and truthful history timestamps where
  history is exported. Keep private evidence/reasons out of the archive. Do not
  substitute current time or change ADR-016's retention rule.
- Existing exported bodies/digests/receipts are immutable. Specify a compatible
  format/provenance distinction and append a sufficient new baseline for roots
  already marked complete under the old format; do not rewrite old records or
  let their cached complete state certify the corrected export. If schema changes
  are necessary, inspect current HEAD and start at V0052, preserving V0001–V0051.
- Regression expectation: inspect the actual independently appended and verified
  archive body for unresolved and terminal reports, standalone actions, holds and
  releases, with source times deliberately different from export time; assert
  exact policy deadlines and no private payload. Cover old-format completed
  baselines, bounded multi-page history, retries/stale root versions and the
  existing post-redaction export. No III importer/restore implementation is asked.
- Developer response: pending.

### Independent checks and limitations

- Java21 `./mvnw -B -ntp verify`: **469 PASS (62 fast +407 IT)**,
  zero failures/errors/skips, Maven exit0,8m04s, finished2026-10-23 20:10:12+07.
  Current-run log entries were matched to their50 class XML reports. Three older
  reports left in target (a prior targeted Surefire IT and two benchmark reports)
  are retained in the evidence copy but excluded from this run's counts.
- Durable main evidence: `/home/vonhuphi/social-p9aii-main-review.YE7Otx/`;
  `verify.log`, copied `surefire-reports/`/`failsafe-reports/`,
  `history-scan.log` and `positive-control.log`. The existing post-System.exit(0)
  thirty-second fork-shutdown error line and stopped-container Hikari warnings
  are retained, not hidden; Maven's final test summaries/build still pass.
- All391 developer manifest paths match the submitted executable/config/API/CI
  bytes. V0001–V0037 unchanged; diff whitespace and Compose config checks pass.
- Pinned redacted Gitleaks full history:76 commits, no leaks, unchanged three exact
  ignores. External synthetic positive control detects its intended key and passes.
  Review-doc staged scan/diff checks and all11 local Markdown links pass.
- Native fresh/V0037/V0032 and3200-request benchmark remain attributed developer
  evidence bound to `6d3fbfc`, not independent main reruns. The500ms goal is still
  unmet. No real data, production activation, restore, push or deployment occurred.

### Next action — one combined repair package in the existing development chat

User reopens **`Develop Phase 9A-II — Sources, Media & Completion`**. Do not create
or spawn another thread. Read this mailbox and the binding assignment, set
IN_PROGRESS, then complete both findings sequentially: ordinary content-retention
regression first, moderation export provenance/compatibility second, followed by
the combined verification gate. Do not hand off after only the first repair unless
there is a concrete authority/architecture blocker.

Run targeted regressions, Java21 formatting/full verify and the required II delivery
checks on the repaired executable, including current-source manifest, native/
upgrade evidence and pinned staged/history/positive scans. Record exact scope and
provenance for every rerun or retained historical result; do not relabel old native
or benchmark evidence as a rerun. No timeout/pool/lease increase, fake writer drain,
waived private payload or relaxed architectural/DB denial. Preserve P9AI-R1/R2 and
C01–C05. Main decides acceptance after checking each response.

Commit verified fixes and affected contracts/phase evidence, then separately commit
the mailbox with actual code hash, per-ID responses, checks/limitations,
READY_FOR_REVIEW and next owner main. Development never pushes. Main is withholding
this candidate checkpoint while findings remain open. III/9B, real erasure/restore,
U01–U06 policy decisions, release/deployment and automatic next-phase activation
remain out of scope.
