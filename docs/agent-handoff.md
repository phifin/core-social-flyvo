# Agent handoff — Phase 9A design gate

## Routing

- Task: phase-9a-erasure-recovery-design
- Round: 1
- State: ACCEPTED — local synthetic design with ADR-017 C01–C05
- Last writer: main orchestrator/reviewer, 2026-10-17
- Next owner: main — archive design acceptance and activate 9A-I only
- New chat: `Plan Phase 9A — Erasure, Recovery & Release Gates`
- Accepted executable baseline: `d2a8e3c2b5942f1019ba2a48157da6ce3a5ed662`
- Phases7–8 main acceptance: `96700ecd81472534e535c65ee62bcbabdfdf9749`
- Phase9A design commit: `ac82f4fb90d956266dcf842109fb5cb9d79c2757`
- No new executable/code commit; accepted executable baseline remains unchanged.
- Full prior review/findings/evidence: [accepted Phases7–8](handoffs/phase-7-8-notifications-search-moderation-ops.md).
- P78-R1 resolved by main; P78-D1 design and implementation accepted.
- Main remains orchestrator/reviewer only and exclusively performs origin pushes.
  No agents, concurrent writers, merge, release or deployment.

## Assigned scope

Read [the complete design package](work-packages/phase-9a-design.md), AGENTS and
[thread workflow](thread-workflow.md) before work. One coherent delivery of:
A) full owner/data/retention inventory, B) proposed erasure/recovery ADR and exact
lifecycle/API/checkpoint/race design, C) multi-capability implementation packages
and local-versus-production release evidence matrix.

This activates **design only**. No Java/SQL/config/test implementation, actual
erasure, backup restore, external coordination or Phase9B. Do not infer that
Phases7–8 acceptance approves irreversible data-policy choices or public launch.
Keep architecture/ADR changes Proposed until main review. Next migration after
V0032 is currently V0033; verify actual HEAD later and preserve applied migrations.

## Carried evidence and limitations

Main independently verified final388 tests (57 fast+331 integration), zero
failures/errors/skips,5m11s, finished2026-10-17 11:31:10+07. Initial full run had
one unchanged Phase4 fixture JDBC-commit socket timeout; isolated58-test and full
388-test rechecks passed without code/config/timeouts changes. Preserve this
operability follow-up; root cause is not proven. Exact logs/checks in the archive.

303-file executable manifest matches; config/history/positive-control checks pass.
Main inspected developer real app/media31→32 startup/security probes and heavy
pool10/50 benchmark3200/3200 successes, not independent reruns of those heavy gates.
Dense feed p95 remains2.02–3.00s, not500ms, with no capacity/SLA claim.
Ambiguous pre-fix notification losses are not automatically repaired.
Provider/storage/decoder/key lifecycle, backups/restore erasure, retained audit/
held moderation data, admin gateway and product/legal/public-launch gates remain.

## Delivery instructions

Set IN_PROGRESS; inspect actual owner code/tables/triggers rather than treating
the blueprint as implemented. Deliver all three workstreams before handoff.
Propose one recommended architecture/policy option with alternatives and exact
decision IDs; distinguish main engineering decisions from required user inputs.
No credentials/private data in docs; no weakening immutability/holds or DAG.

Check changed links/diff/scope and pinned redacted scans. No application-suite
rerun is needed for unchanged executable code. Commit verified design documents,
then separate mailbox commit with real hash, decision requests, limitations and
READY_FOR_REVIEW to main. Do not self-accept, implement the proposal, or push.

## Main remote checkpoint — 2026-10-17

Main performed normal fast-forward push of reviewed Phases7–8 plus the Phase9A
design-only assignment to `origin` (`https://github.com/phifin/core-social-flyvo.git`),
branch `master`, from `14c983b6502d394bc4e8fc892d6395d0cbbaf076` to
`6f71d393cc585ddf8daebcfd99fc91072c07b735`; ls-remote confirmed that exact hash.
Outgoing14 commits inspected; final pinned redacted history scan62 commits clean,
staged docs/link/whitespace checks clean, archive matches accepted mailbox except
relative links. No force-push/history rewrite, remote CI claim, release or deployment.
This audit note is subsequent documentation, not a self-referential push hash.

## Current design review findings

Main accepts design with binding refinements C01–C05 in ADR-017, recorded below.
No unresolved design finding blocks local9A-I. Prior phase findings remain preserved.

## Design delivery — A+B+C, 2026-10-17

Completed the assigned design package in this thread; no intermediate inventory-only
handoff. All content in design commit `ac82f4fb90d956266dcf842109fb5cb9d79c2757`:

- **A** [owner/data inventory](work-packages/phase-9a-data-inventory.md): all56 actual
  tables at V0001–V0032, storage versions/markers, projections, encrypted payloads,
  history/holds/audit, durability and backup/operational copies. Each category has
  current behavior, proposed action/clock/horizon/order/proof and decision IDs.
  E1–E12 link inspected migration paths/code symbols. Feed owns no persistence.
- **B** [ADR-017](adr/017-account-erasure-and-recovery.md), **Proposed** and indexed:
  exact account-owned fixed roster/API/lifecycle/ack/checkpoint/fencing/retry/DEAD/
  recovery semantics; DAG unchanged; conservative independent erasure/preservation
  intents and restore exposure barrier. Alternatives and conflicts are explicit.
  Preserve ADR-016, immutable metadata and others' text; held/unresolved private
  payload does not become “complete” merely because access was revoked.
- **C** [implementation/release plan](work-packages/phase-9a-plan.md): three large
  sequential packages9A-I/II/III with internal gates, tentative migrations starting
  V0033, all-owner scope, T01–T20 security/concurrency/replay/restore matrix, explicit
  synthetic source/target layout, compatible rollback/forward-fix gates, local vs
  public evidence and carried operability risks. None is activated by this delivery.

### Decisions requested from main — all OPEN / Proposed

| ID | Recommended choice requiring review |
|---|---|
| P9A-D01 | Fixed account progress roster; owners execute/ack through account.api; technical platform only, no reverse DAG edge |
| P9A-D02 | Strict DELETED after every private-payload/object/manifest proof; WAITING_CASE/HOLD/RETENTION/EXTERNAL remains pending |
| P9A-D03 | Delete subject authorship, preserve other users' content and immutable pseudonymous refs; last CUSTOM recipient→ONLY_ME; archived-owner FK retained; terminal reaction shape |
| P9A-D04 | Bounded100-item passes plus explicit fixed root/aggregate work, existing ten-attempt worker, job-first technical cancellation and proven writer quiescence |
| P9A-D05 | Preserve close204/request-only meaning; current audited ADMIN progress/resume, no revoked-session or new user status credential |
| P9A-D06 | Fresh-proof new UUID on re-registration; release email/provider only after stale-proof drain; unique handle reservation30d then release |
| P9A-D07 | Permanent terminal-account projection fences, guarded stale-snapshot replay/rebuild and historical pending/deleted reconciliation |
| P9A-D08 | Independent conservative intent before close/hold commit, minimal resource/preservation ledger and verified current restore cut/exposure barrier |
| P9A-D09 | Preserve ADR-016 holds/365d/immutability and live/backup key lifetime; finite audit disposal remains a separate policy/design gate |
| P9A-D10 | Three sequential implementation packages; main review gates and external/product launch evidence remain distinct |

Full alternatives/tradeoffs, exact API/preconditions, lock order, final certificate
condition and unresolved policy conflicts are in ADR-017. User/environment inputs
**P9A-U01–U06** remain explicitly required at their dependent gates: retention/minor/
jurisdiction/audit/third-party-reference policy; providers/SMTP; storage/IAM/absolute
writer bounds; independent backup/ledger/key authority and recovery objectives;
admin gateway/MFA/operators; target hardware/capacity/abuse/security/license.
Concrete recommendations are supplied; this is READY_FOR_REVIEW, not BLOCKED on
missing production credentials. No credentials requested or external contact made.

### Checks actually run in this design round

- PASS all-table coverage:56 migration-created tables present in A,32 migrations,
  latestV0032. No Java/SQL/config/test/ops/CI/OpenAPI executable diff against
  accepted `d2a8e3c`; existing migration bytes and exact `.gitleaksignore` unchanged.
- PASS5 design-document scoped stage,88 local Markdown links, table shapes and
  staged whitespace/diff checks. Index initially empty; mailbox excluded from
  the design commit. Mailbox links/whitespace/staged patch checked separately
  before its own commit; final results are reported with that commit in chat.
- PASS CI-pinned Gitleaks8.28.0 digest
  `sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854`:
  redacted design staged patch clean; full history63 commits before design and
  **64 through ac82f4f** clean. Existing external synthetic positive control detects
  the expected generic-api-key/exit1 and the script passes; no new ignores/rule
  exclusions or history rewrite. Final post-mailbox history is checked in chat.
- Evidence logs (temporary, untracked): `/tmp/social-p9a-design-checks.YV4cNs/`
  `docs-checks.json`, `design-staged-scan.log`, `history-before-design.log`,
  `history-through-design.log`, `positive-control.log`; later mailbox/final scans
  use the same directory. Logs are not a durable remote CI artifact.
- Application tests/format/Compose startup/heavy benchmark and restore/storage
  drills **not rerun/executed** for unchanged executable code. Historical388-pass
  evidence remains attributed to main's accepted recheck, not this task. All
  T01–T20 and proposed production drills are future implementation requirements.

### Limitations, findings response and exact next action

No new code is delivered or accepted; ADR-017/retention/reuse/recovery policy is
not self-accepted. No actual erasure/restore, Java/SQL/config/test change, agent
spawning, push, external messaging, deployment, Phase9B or public launch occurred.
Immutable audit's finite disposal path, held/unresolved evidence, actual storage
writer bounds/backup/key/provider/admin/product gates remain open. R01 preserves
the unchanged Phase4 fixture commit timeout + isolated58/full388 passing rechecks
and unproven cause; R02 preserves dense feed p95≈2.02–3.00s, unmet500ms target.
No capacity/legal compliance/remote CI or lost-notification repair claim.

No new reviewer finding ID exists to respond to in round1. P78-R1 and P78-D1 main
dispositions stay preserved; this thread does not reopen, resolve or re-accept them.
Main next reviews A+B+C and records each D01–D10 disposition in this mailbox. If
approved, main explicitly activates **9A-I only** for the user-opened named chat
`Develop Phase 9A-I — Erasure Foundation & Identity`, using C's G0/I.1–I.3 scope.
Otherwise main records stable review finding IDs; design thread responds to them.
II/III, real data operations, launch and Phase9B require their separate gates.

## Main design review — ACCEPTED with refinements, 2026-10-17

Reviewed design `ac82f4f`, handoff `fbf2f5b`; clean initial index/tree; executable
baseline stays `d2a8e3c`. Checked A/B/C against closure/account guards, actual
moderation resolution/action payload writes, worker external prepare/effect/ack,
profile and schema boundaries. This is document/static review, not runtime proof.

| Decision | Main disposition |
|---|---|
| D01 | ACCEPT local: fixed owner roster/account progress; existing DAG, no reverse imports |
| D02 | ACCEPT local: strict pending vs DELETED, with nonrecursive final certificate C02 |
| D03 | ACCEPT synthetic policy only: scoped authorship/reference/tombstone rules; no production approval |
| D04 | ACCEPT local: bounded passes/fencing/cancellation; unimplemented owners wait without unknown-kind dispatch (C03) |
| D05 | ACCEPT local: guarded close request, audited current ADMIN progress/resume; no replacement status credential |
| D06 | ACCEPT synthetic policy only: fresh-proof/new UUID, drain claims,30d handle reservation; U01 still required |
| D07 | ACCEPT local: terminal fences/reconciliation, distinguish pending case work from final DELETED per C01 |
| D08 | ACCEPT protocol only: independent intents/cuts; synthetic adapter boundary C04 and resource-export barrier C05; U04 unresolved |
| D09 | ACCEPT: preserve ADR-016/keys/metadata; existing pending cases may resolve with guarded proof invalidation (C01) |
| D10 | ACCEPT sequential packaging; activate I only after this review, II/III require main gates |

C01 fixes an actual design ambiguity: ReportWorkflow.resolve always appends an
encrypted action_detail, even when dismissing. Banning all pending-subject private
payload writes would prevent cases leaving WAITING_CASE. Existing-root resolution
is allowed with current authority and atomic guarded inventory/proof invalidation;
final DELETED still rejects new private data. C02 removes self-reference from the
12-step completion certificate. C03 prevents dispatching absent II handlers in I.
C04 isolates synthetic authority and default activation; C05 preserves independent
resource inventory before the last identifying mapping is removed. These conditions
are now part of the accepted ADR, not deferred discretionary fixes by development.

No production retention/identity reuse/legal/provider/ledger decision was made;
U01–U06 remain open at their dependent gates. No actual erasure/restore, executable
change, application test run, deployment or external coordination in this review.
Next owner is a new implementation chat named in C, assigned I.1→I.2→I.3 in one
package, not a return to inventory-only work. Main remains docs/reviewer only.

Main checks: all56 migration-created tables appear in inventory,32 migrations;
91 local links in the five reviewed/edited docs resolve; whitespace clean. No
executable/config/test/migration or scanner-ignore change from accepted baseline.
Pinned redacted history65 commits throughfbf2f5b clean; synthetic positive control
PASS. Logs `/tmp/social-p9a-main-history.log`, `/tmp/social-p9a-main-positive.log`.
Application/Compose/restore/benchmark tests not rerun for documentation-only work.
