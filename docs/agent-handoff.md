# Agent handoff — Phase 9A-I implementation

## Routing

- Task: phase-9a-i-erasure-foundation-identity
- Round: 1
- State: READY_FOR_IMPLEMENTATION — local synthetic environments only
- Last writer: main orchestrator/reviewer, 2026-10-17
- Next owner: development; user opens new chat manually
- New chat: `Develop Phase 9A-I — Erasure Foundation & Identity`
- Accepted executable baseline: `d2a8e3c2b5942f1019ba2a48157da6ce3a5ed662`
- Accepted design: original `ac82f4f`, main decision/refinements `eab4da6`
- Design archive: [Phase9A design review](handoffs/phase-9a-erasure-recovery-design.md).
- Prior executable review: [Phases7–8](handoffs/phase-7-8-notifications-search-moderation-ops.md).
- Main is docs/orchestrator/reviewer only; main alone may push origin.
- No spawned agents, concurrent writers, merge, release or deployment.

## Binding assignment and scope

Read AGENTS, thread/git workflows, README, implementation handoff, architecture,
roadmap9A, security, relevant subsystem docs, the [inventory](work-packages/phase-9a-data-inventory.md),
[ADR-017 including C01–C05](adr/017-account-erasure-and-recovery.md) and
[plan I.1–I.3 / T01–T20](work-packages/phase-9a-plan.md). Inspect actual APIs/jobs/
migrations and git status. Set IN_PROGRESS and write the concrete scoped plan.

Implement **all9A-I** sequentially in this single chat, with internal gates:

1. I.1 account request/prepared intent/step/resource state, fixed12-step roster,
   bounded reconciliation, current audited ADMIN progress/resume, version/audit/
   completion denials. Missing later owner handlers remain WAITING_DEPENDENCY;
   no unknown-kind jobs, fake DONE or completion based on enabled consumers.
2. I.2 independent PREPARED receipt before successful close, durable COMMITTED
   export/chunk/receipt mechanics, nonrecursive certificate model and safe scoped
   technical cancellation. Real durable isolated synthetic archive adapter/tests;
   no production authority claim or silent in-memory/runtime fallback. Network
   effects occur outside business locks; retry/fencing/DEAD stay authoritative.
3. I.3 identity proof/session/credential/mail cancellation and cleanup, profile/
   preferences/avatar release, fresh-proof new UUID, guarded email/provider claim
   drain and database-enforced handle reservation/reuse. Existing module DAG only.

C01 moderation pending-case semantics must be preserved in the protocol; its owner
implementation remains II. C02 certificate excludes its own final receipt/step.
C03 prevents dispatching unimplemented owners. C04 names/versions the synthetic
policy, disables destructive maintenance on existing/default environments and
fails strict close closed without the configured verified archive. C05 requires
resource export proof before losing its last owner enumeration mapping.

No9A-I account may reach DELETED: later owner proofs are not implemented.
Do not implement II/III moderation/source/media purge/projection/restore handlers,
scaffold fake ones, or claim end-to-end erasure. Narrow existing media release
API use by profile is in I scope; full media erasure is not.

## Migration, activation and data boundaries

V0001–V0032 unchanged; actual nextV0033. V0033–V0036 in the plan are tentative,
allocate only necessary real migrations after inspecting HEAD. No empty placeholders.
Account lifecycle/step APIs may not call higher-module internals; platform stays
technical, with no account business imports or foreign SQL consumer.

Use explicit isolated synthetic Testcontainers/local fixtures only. Do not enable
new destructive jobs/reconciliation against the existing developer Compose data,
.local data, real accounts/buckets/backups or a production endpoint. Do not perform
a real restore, select/configure production services, contact providers or request
credentials. U01–U06 remain open. Handle/retention defaults are local engineering
policy, not production consent or legal compliance. No Phase9B.

## Verification and delivery gates

For each internal capability, run relevant real-PG tests and keep failures.
Final I gate: targeted lifecycle/auth/admin/worker/identity/profile/handle/race/
archive/rollback regressions; Java21 formatting and full ./mvnw verify;
fresh/upgrade32/new migration prefix/checksums/constraints; OpenAPI/architecture;
real isolated app/media Compose startup/security probes; pinned redacted staged/
full-history secret scans and synthetic positive control, exact ignores unchanged.
Run unchanged committed heavy feed pool10/50 benchmark (all3200 successes) because
shared worker/cancellation/resource paths change. Do not relax pools/timeouts,
skip Docker tests or report only successful retries. Record executable manifest,
commands, environment, timings/outcomes and outstanding owner steps.

Test at least lost/duplicate PREPARED/COMMITTED receipts, current auth/version/
ownership recheck after export, no network under guards, proof/vector mismatch,
missing owner waiting without retry storms, >100-row paged credential chains,
old callback/reset/registration/mail versus release, concurrent handle claim/
rename/reservation/expiry, mandatory audit/checkpoint/fenced-ack rollback and
ten-attempt DEAD plus audited versioned resume. Synthetic archive persistence/
integrity faults must be real assertions, not mocks always returning success.

Deliver implemented API/schema/security/events/owner/runbook docs and
docs/phases/phase-9a-i.md. Commit verified internal slices without handing off each
small module. Finish I.1→I.2→I.3, then separate real-code-hash mailbox commit with
READY_FOR_REVIEW, results/limitations and C01–C05 response. A concrete incompatible
contract/blocker needs evidence and proposed decision; do not improvise.
Development never pushes or self-accepts. Main reviews before II activation.

## Carried limitations

Prior final independent suite388 PASS; first-run Phase4 fixture commit timeout
remains an unproven-cause operability follow-up, not fixed by this design.
Dense feed p95≈2.02–3.00s remains above500ms. Public launch, actual production
ledger/backup/key/provider/storage/admin/legal policy and full erasure remain gated.
No new executable code or application test run was produced by this main review.

## Review findings

No implementation finding yet. D01–D10 accepted only as qualified by ADR-017
C01–C05; U01–U06 remain external/product gates. Prior findings stay in archives.
