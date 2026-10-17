# Agent handoff — Phase 9A design gate

## Routing

- Task: phase-9a-erasure-recovery-design
- Round: 1
- State: READY_FOR_IMPLEMENTATION — documentation/design only
- Last writer: main orchestrator, 2026-10-17
- Next owner: development/design — user opens the named new chat manually
- New chat: `Plan Phase 9A — Erasure, Recovery & Release Gates`
- Accepted executable baseline: `d2a8e3c2b5942f1019ba2a48157da6ce3a5ed662`
- Phases7–8 main acceptance: `96700ecd81472534e535c65ee62bcbabdfdf9749`
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

## Review findings

No findings for this new design task yet. Prior phase findings remain preserved
in the accepted archive; only main may resolve future findings/accept decisions.
