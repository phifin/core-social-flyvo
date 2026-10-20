# Agent handoff — Phase 9A-II source erasure and completion

## Routing

- Task: phase-9a-ii-sources-media-completion
- Round: 1
- State: READY_FOR_IMPLEMENTATION — isolated synthetic scope only
- Last writer: main orchestrator/reviewer, 2026-10-20
- Next owner: development, in a new user-opened chat
- Chat name: `Develop Phase 9A-II — Sources, Media & Completion`
- Accepted executable: `bbbde93c170246372895907dd4e446696521452d`
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

Next migration **V0038**, allocate actual consecutive owner migrations after
inspecting HEAD. Preserve V0001–V0037; older tentative V0037–V0042 allocation is
superseded. No placeholder migrations, new module DAG edge/SQL consumer or generic
workflow engine. Existing account.api owner acknowledgement/worker fencing is
the foundation, not permission to call higher-module internals.

## Inherited conditions and review findings

P9AI-R1/R2 are resolved at G1, not fresh tasks. Preserve their post-lock DB-time /
inherited proof deadlines and exact request/generation/round/execution receipt
checks. Keep their regressions passing, including superseded success/failure.
C01–C05 bind this implementation; no private payload may be waived to obtain DONE.
Retain the fixed12-owner roster; owner activation requires real handlers,
proofs and historical reconciliation, not empty/fake acknowledgements.

No new II implementation findings yet. Development must not self-accept G2 or
resolve future main findings. Review history is preserved in the archive above.

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

Not started. Record the implementation plan, actual slice/final hashes, evidence,
deviations and concrete blockers here while preserving the assignment and any
later reviewer IDs. Main reviews the whole package at G2 before activating III.
