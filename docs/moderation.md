# Reports and scoped case actions — Phase 5

Reports support POST, COMMENT, COMMUNITY and USER. UUID knowledge is not proof.
Submission invokes a transaction-required owner proof before idempotency/report
locks: content uses coherent current parent/comment policy and source guards;
community captures its ordinary authorized discovery card; USER uses only the
authorized ProfileQueries.visible summary under [ADR-014](adr/014-report-user-target-proof.md).
No general profile lookup, private bypass or foreign SQL is introduced.

POST/COMMENT inherit the immutable containing community, if any. COMMUNITY uses
its own community scope. USER and personal content are global; callers cannot
submit community/author/reporter/status/evidence fields. Current target access is
checked again on every idempotency replay. One unresolved OPEN/IN_REVIEW case per
reporter/typed target is enforced by a partial unique index. Concurrent duplicates
return the original unresolved case without replacing evidence/category/detail or
repeating effects. A key/body conflict is 409; a resolved case permits a new report.

Evidence is a captured timestamp, optional detail (4,000 Unicode code points) and
the exact owner-authorized summary, truncated to 2,000 code points. It contains no
media URL, original object, recipient list or unauthorized fresh reload. Existing
Secrets authenticated encryption binds it to the report UUID; ciphertext is capped
at 64 KiB, key ID is retained, keys stay external to PostgreSQL. Detail is inside
ciphertext, not a plaintext report column. Generic outbox/audit contain IDs/status
only. Reporter metadata pages never expose evidence or other reporters' cases.

Community queue/evidence/actions require current effective MODERATOR+ in the exact
scope. Evidence persists after source deletion, but staff revocation immediately
denies subsequent access. Every successful evidence read has attributable scoped
audit in the same transaction. Platform ADMIN uses separate explicit global-case
endpoints; community staff alone cannot read USER/personal global cases. ADMIN does
not bypass ordinary resource policy or see community cases through global routes.

OPEN → IN_REVIEW → RESOLVED/DISMISSED, or OPEN → RESOLVED/DISMISSED. Review and
resolution require the case If-Match. Remove/restore additionally require post or
comment sourceVersion, reuse existing owner APIs, and cannot edit body or restore
author deletion. Sanctions additionally require current membership sourceVersion
and target the immutable reported content author; current self/owner/equal/higher
role denials apply. Sanctions cannot target a caller-chosen user. Global actions are
review/resolve/dismiss only; account suspension and assignment/filtering/retention
operations remain Phase 8. Terminal cases cannot change again.

The owner source transition, case version/status, append-only typed action with
report FK, mandatory audit and minimal v1 outbox commit or roll back together.
Conflicting resolutions recheck the locked case after owner mutation; a losing
version check rolls back all preceding effects. No automatic command retry.

## Lock order and limits

Submission acquires sorted shared actor/source account guards → shared community
(read, including archive where ordinary policy permits) → sorted owner graph pairs
→ shared post/comment or profile row → idempotency/report. Profile proof obtains
its profile row exclusively initially (no upgrade), serializing privacy edits; block
and account revocation serialize through the earlier guards. Evidence uses only
actor account → shared community → case read. Global authority is current under
the actor account guard.

Actions pre-read immutable report/source IDs, acquire the complete sorted account
set → final community lock mode (exclusive initially for sanctions, shared otherwise)
→ owner source post/comment → case exclusive. Called owner commands only reacquire
already-held earlier guards; no new earlier lock set, reverse callback, network,
lock upgrade or unbounded retry. Report rows always follow source locks.

Reporter/queue pages are newest-first `(created_at,id)`, size 20/default, 100/max,
offset 0–10,000, no totals/snapshot, nextCursor null (explicit offset pagination).
Report JSON cap is 64 KiB; action bodies retain the existing 16 KiB cap. Existing
per-user/IP write budgets apply. [OpenAPI](api/openapi.yaml) is the exact contract.
Case redaction/key-rotation/legal evidence retention remain pre-launch gates; no
new indefinite source-media hold or production readiness is claimed.

## Phase8 development checkpoint — incomplete D

The account owner now supplies guarded/versioned suspend/reinstate and durable
session-epoch invalidation; no new moderation HTTP operation is claimed yet.
Remaining assignment/filter/history/hold/retention work is BLOCKED on main's
[proposed ADR-016](adr/016-moderation-private-payload-retention.md) decision (P78-D1).
Private action fields and report evidence still have their accepted immutable
storage/trigger protections. The proposed payload split/engineering hold policy
is not implemented or self-approved. See [Phase8 evidence](phases/phase-8.md) and
the [mailbox](agent-handoff.md) for actual hashes/checks and the exact next action.
