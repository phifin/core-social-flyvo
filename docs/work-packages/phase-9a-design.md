# Phase 9A — erasure, recovery and release-readiness design gate

ACTIVATED by main on2026-10-17 after Phases7–8 acceptance `96700ec`.
This package authorizes
**repository inspection and design documentation only**, not executable changes,
erasure, deployment or Phase9 acceptance. User opens a new chat manually:
`Plan Phase 9A — Erasure, Recovery & Release Gates`.

## Why a design gate now

The accepted executable baseline is `d2a8e3c`, migrations V0001–V0032. Account
closure already checks recent authentication/version/active-community ownership,
revokes sessions and emits AccountDeletionRequested with DELETION_PENDING.
It does not implement cross-owner erasure or the final DELETED transition.
ADR-016 deliberately retains immutable moderation/audit metadata and protected
payloads. Erasure must reconcile those boundaries rather than invent a trigger
exception or claim that inaccessible data has been erased. Historical deletion
requests also predate any new consumer registration.

Deliver the three connected workstreams below together. No handoff after a small
inventory fragment. Main reviews the resulting decisions once, then assigns a
larger implementation package with internal sequential gates. Product/legal or
production-environment choices remain explicit owner decisions, not assumptions.

## Read and inspect first

AGENTS, mailbox, thread/git workflows, implementation handoff, README, architecture,
roadmap9A, security, database/domain models, events/jobs, operations, all relevant
owner subsystem/runbooks and ADRs002–005,008–012,014–016. Inspect real migrations,
FKs/triggers, API callers, job handlers and retention code; cite exact paths and
symbols. Documentation describing a target is not evidence of implementation.
No agents or concurrent writers. Preserve all staged/user edits.

## A. Complete owner/data and policy inventory

Produce `docs/work-packages/phase-9a-data-inventory.md`: cover account, identity,
profile, community, graph, content, media, notification, search, moderation and
platform. Include credentials/provider links/session and encrypted mail/token
payloads; handles/bios/avatar bindings; memberships/invitations/ownership;
authored posts/revisions/comments/reactions/mentions; object versions/delete
markers; indexes/actor contributions/source tombstones; reports/action payloads,
holds/history/audit; outbox/jobs/idempotency and backup copies.

For each persisted category specify actual owner/table/object, personal data,
references, current cleanup, proposed erase/redact/retain action, retention start
and horizon, required ordering, completion proof and policy decision status.
Distinguish personal content from immutable pseudonymous metadata and from others'
content referencing the subject. Describe identifier/email/handle reuse and
re-registration without identity linking. Never blanket-cascade account deletion,
erase another person's content, or silently override unresolved/held evidence.

## B. Concrete lifecycle, API and recovery design

Propose the next available ADR (currently017; verify actual index) for the
owner-coordinated erasure/restore boundary, clearly **Proposed**. Update ADR index,
not accepted architecture. Recommend one option with alternatives/tradeoffs.
Keep account lifecycle/progress in account; preserve the allowed dependency DAG.
Do not let account call higher-module internals or put business policy in platform.
Reuse durable jobs/events and narrow owner APIs; no new generic workflow engine.

Specify step identities and dependency order, bounded checkpoints, version/fencing,
retry/DEAD/operator recovery, lock order, idempotent owner acknowledgement and
the exact condition for DELETED. No completion before required owners and external
object versions are actually done; distinguish held/deferred/failed work from
completion. Define progress visibility without re-enabling revoked sessions or
exposing other users' deletion state. Design reconciliation for existing
DELETION_PENDING accounts and expired/missing historical delivery rows.

Cover close-versus-owner-transfer/write/upload/finalize/rebuild races, events/jobs
queued before deletion, old events replayed afterward, and a worker crashing after
an external delete but before checkpoint acknowledgement. Storage calls stay
outside business locks, with durable intent and bounded retry. Keys must remain
available for all live ciphertext; crypto-shredding is not an implicit substitute.

Specify backup/restore erasure-manifest authority, integrity, access, retention and
reconciliation before restored service is exposed. A manifest stored only inside
the restored old backup cannot prove more recent erasures. Keep minimal identifiers,
no private content. Show an isolated synthetic restore-drill plan with explicit
source/target paths and fail-closed recovery; no real restore/delete is authorized
by this design task. Preserve ADR-016 unless main explicitly accepts a superseding
decision; list any genuine policy conflicts together rather than hiding them.

## C. Implementation packages and release evidence matrix

Produce `docs/work-packages/phase-9a-plan.md` with coherent multi-capability packages,
not one thread per small module. For each give dependencies, exact owner/API scope,
new migration needs (nextV0033; never edit applied files), tests, internal gates,
deliverables and stop conditions. Plan all required owners, not just credentials.

Include real-PG fresh/upgrade/checksums, rollback/fencing/retry/concurrency,
post-deletion source/search/notification/media absence, retained-held evidence,
no resurrection on replay/rebuild/restore and bounded large-account cleanup.
Specify local synthetic restore/storage-version drills and meaningful negative
checks, full Java21 verify/format/OpenAPI/architecture/Compose/scanner gates, and
heavy feed regression where shared owner/worker/resource changes warrant it.
Carry the main round2 Phase4 fixture JDBC-commit timeout and its unchanged
recheck evidence into the operability risk register; do not claim a proven host
root cause or silently relax timeouts to remove a flaky result.

Separate locally implementable evidence from production-dependent gates:
provider approval/SMTP/storage/IAM, backup/PITR and key recovery, admin gateway,
retention/jurisdiction/minor policy, dependency/security review, quotas, sustained
load and recorded RPO/RTO/lag/latency targets. Dense feed p95<500ms remains unmet;
do not raise timeouts/pools or substitute a target for a measurement. No public
launch, legal compliance, production capacity or remote CI success claim.
List exact remaining user/environment inputs and recommended choices without
requesting credentials in chat or contacting external people/services.

## Delivery

Set mailbox IN_PROGRESS; complete A+B+C in this chat, then READY_FOR_REVIEW with
all decision IDs, file/symbol evidence, alternatives and exact proposed next
implementation package. This is a design delivery, not a blocked implementation.
Only a genuine missing input that prevents even a concrete proposal warrants
BLOCKED. No Java/SQL/runtime/config/test implementation or destructive experiment.

Check links, scope/contract consistency, whitespace and pinned redacted staged/
history secret scan (keep exact existing ignores). Do not rerun application tests
for unchanged executable code merely to fill a report. Commit verified design,
then a separate mailbox commit referencing its real hash. Main alone accepts
decisions, activates implementation and pushes; no self-acceptance or Phase9B.
