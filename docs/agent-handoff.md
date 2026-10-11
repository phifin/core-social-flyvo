# Agent handoff — Phases 5–6

## Routing

- Task: phase-5-6-interactions-reports-feeds
- Round: 1
- State: READY_FOR_IMPLEMENTATION
- Last writer: main, 2026-10-11 — requested larger package activated
- Next owner: new chat `Develop Phases 5–6 — Interactions, Moderation & Feeds`
- Accepted Phase 4 code: `9bdc9996b546d73356f177759806296cea40bc43`.
- Phase 4 acceptance commit: `26d60ce`.
- Review history: [accepted Phase 4](handoffs/phase-4-posts-media.md); R1–R5 resolved.
- No Phase 5/6 code commit yet. Verify actual HEAD/status before edits.
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
- Keep the single exact historical secret-scan exception, default detection,
  full-history scan and redaction.
- Use owner APIs and sanctioned SQL views; no foreign writes/joins, duplicate policy
  authority, architecture exclusions or authorization cache.
- No notifications/SSE/search/advanced Phase 8 moderation, Redis/broker/fan-out,
  speculative modules or production deployment.

## Implementation report and findings

Pending development. No Phase 5/6 implementation or test success claimed.
No findings yet for this package. Accepted prior review remains historical context;
report any newly discovered prerequisite defect with exact evidence.

## Baseline checks and remote delivery

Main independently verified Phase 4: Java 21 full verify 182 pass (39 unit +143
integration), zero failures/errors/skips; full-history pinned secret scan and
synthetic control pass; Compose app/media config and whitespace pass. These checks
cover the baseline, not Phase 5/6 acceptance.

Origin is `https://github.com/phifin/core-social-flyvo.git`. Last confirmed remote
master before this handoff is Phase 3 `552c4ca`; main is preparing the accepted
Phase 4/documentation fast-forward and will verify the resulting remote hash.
Local verification and successful push do not imply remote CI success.
Development must hand back locally even when its tests pass.

## Next action

Open the named new development chat and execute this mailbox and the complete
linked package. The existing Phase 4 development chat has finished its assignment.
Main handles final review and remote delivery.
