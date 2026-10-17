# ADR-016 — Retained moderation metadata and redactable private payloads

Status: **Accepted for implementation by main, 2026-10-16**, resolving the
architecture decision P78-D1. Proposed by development on the same date.
Implementation was accepted with Phase8 by main on2026-10-17 at `d2a8e3c`;
verification is recorded in [Phase8](../phases/phase-8.md).
No Phase9/legal erasure authorization follows from this ADR.

## Context and evidenced conflict

The activated [D package](../work-packages/phase-7-8.md) requires bounded retention
jobs with the documented365-day engineering default, explicit time-bounded holds,
and redacted attributable tombstones, without weakening append-only audit.

The accepted implementation has private reason/before_state/after_state directly
on moderation.action. V0010's moderation_action_immutable trigger rejects **every
UPDATE and DELETE**, with no age/role/retention exception. V0023 similarly includes
evidence_encrypted/evidence_key_id in the immutable report identity guard and
requires nonempty ciphertext. platform.audit_entry has its own unconditional
mutation rejection. These are protections, not bugs to disable. Existing
ReportsIT.rawReportIdentityEvidenceAndTypedActionConstraintsRejectMutation and
Phase4IT's private-action history regression explicitly preserve them.

Consequently an ordinary durable job cannot redact these existing private fields
through the present schema. Deleting a report is also not a safe substitute:
action/report FKs restrict deletion, unresolved/held cases must survive, and IDs
and attributable history must remain. The blueprint describes a controlled
owner-role process for security-audit retention but does not define an executable
moderation exception or a private-payload split. The original plan's generic
"controlled DB retention mechanism" does not choose that security boundary.

Selecting a runtime trigger exemption, or silently changing which parts of an
accepted append-only action may mutate, would decide a security/persistence
contract in development. [Implementation handoff](../implementation-handoff.md)
requires an accepted/superseding ADR for that change. No such change is made here.

## Accepted decision — private-payload split with bounded engineering holds

Keep immutable action/case identity and attributable metadata separately from
retained private payloads, inside the existing moderation module/case system.
No new module/dependency, SQL consumer, broker or alternate job framework.

1. Use a **new** globally ordered Flyway migration after V0029. Preserve all old
   migration bytes/checksums. Copy report ciphertext/key IDs byte-for-byte into
   a one-to-one owned evidence payload. Copy action reason/before/after losslessly
   into a one-to-one owned private detail payload; verify row count and values
   before removing those columns from the immutable parent. Parent UUIDs, actor,
   immutable scope/typed target, timestamps, report FK and action remain intact.
2. Keep moderation_action_immutable and platform.audit_entry mutation rejection
   unconditional and unchanged. Refine the report identity guard only to move
   evidence immutability to the new payload guard, never permitting target/scope
   reassignment. Ordinary payload mutation/deletion stays prohibited; the only
   allowed transitions are bounded verified legacy encryption and eligible
   irreversible redaction. Enforce terminal/age/no-current-hold eligibility in
   the owner persistence boundary and database transition guard, not a caller
   flag, mutable session variable, disabled trigger or general-purpose bypass.
3. New action private detail uses the existing authenticated Secrets encryption,
   purpose-bound to action UUID; private plaintext does not enter audit/outbox.
   SQL migration cannot access external keys: retain copied legacy values only
   in the restricted owned detail table until a bounded durable crypto backfill
   persists verified ciphertext and removes the legacy value atomically. Reuse
   the existing worker, receipts/fencing/retry, ≤100 rows per transaction and
   fixed scalar checkpoints. Scoped attributable history reads must authorize
   before any payload access, including the transitional legacy representation.
4. A case becomes retention-eligible **365 days after resolved_at**, only in
   RESOLVED/DISMISSED, with no unexpired hold. Unresolved cases are excluded.
   Case-linked action detail shares that case's eligibility/hold; standalone
   pre-case member/content actions use created_at+365 days with their own explicit
   scoped hold. Keep redaction timestamps and immutable metadata, not fake blank
   evidence or deleted history. Reads distinguish redacted payload from missing
   case and never fall back to source/projection/backup content.
5. Holds are an **engineering preservation control, not a legal-hold product**:
   current eligible staff in the immutable case/action scope (global: current
   platform ADMIN), versioned audited create/release, bounded reason code, explicit
   expiresAt in (now,now+365 days]. Renewal is another current explicit audited
   decision, never automatic. A hold cannot restore already redacted payload.
   Main accepts this maximum/scope/renewal rule as an engineering default, not
   a legal policy. Any later change requires an explicit decision.
6. Retention takes case/action root then payload locks, rechecks eligibility/hold
   under those locks, and commits redaction+tombstone+minimal audit+next bounded
   job/fenced ack atomically. Assignment/resolution/hold mutations use the same
   root lock; no evidence loss from a stale preselection or concurrent hold.
   Failed/replayed jobs remain retryable/idempotent; no cascade account erasure.
7. External evidence/detail keys remain available while **any** retained live
   ciphertext references them, including held/unresolved records. This proposal
   adds no key-rotation service and does not select a production retention/legal
   policy. Audit metadata retention and backups/restore-erasure enforcement remain
   separate operator/public-launch gates; do not mutate platform audit to pretend
   those gates are delivered.

## Main implementation conditions

- Supersedes only the physical co-location/absolute private-payload immutability
  of V0010/V0023 for the transitions above. It does not weaken immutable action
  metadata, report identity or platform audit. Keep ordinary payload replacement,
  parent reassignment, DELETE/reinsert and post-redaction restoration denied.
  Preserve a one-to-one payload/tombstone for each parent; no absent-row loophole.
- The SQL migration must prove lossless copies before dropping old columns and
  abort atomically on mismatch. Document a controlled migration/write-stop window;
  do not claim old and new application binaries can safely write both schemas.
  New writes must never create legacy plaintext. Backfill must converge to zero
  legacy rows; expose bounded progress/failure metrics and a resumable runbook.
- Verified encryption means the application decrypts the new purpose-bound
  ciphertext and compares the complete legacy value before atomically removing
  plaintext. On crypto/key/verification failure retain the old data and retry
  under the existing bounded worker policy. DB guards enforce identity and the
  one-way state/shape transitions, not cryptographic equality with external keys.
  Do not claim the database independently proves ciphertext semantics or protects
  against a database owner/superuser. No external key-service calls under locks.
- Eligibility uses authoritative database time. A hold is active while
  expiresAt > now; at expiry it ceases to block cleanup. Hold creation, renewal,
  release and cleanup serialize on the same case/standalone-action root before
  payload locks. For case-linked actions, the case is the retention root; do not
  introduce a competing independent action hold. Recheck current staff authority
  under the existing account/community guard order before the root lock.
  Reject hold creation/renewal once applicable payload redaction has begun; never
  imply that a hold restores removed bytes. Test both winning race orders.
- Large cases may require several <=100-row passes. Mark the case's retention
  state under its root lock when redaction starts, then redact bounded batches
  with resumable progress. Do not hold an unbounded action list/transaction or
  mark completion while any eligible private payload remains. Later hold requests
  must clearly fail rather than claim preservation of an already partly redacted
  case. No automatic renewal and no early release caused by assignment changes.
- Update existing raw-SQL regressions to attack the new payload storage as well
  as immutable metadata. A test failing merely because an old column no longer
  exists is not evidence of retained mutation protection. Include eligible versus
  premature redaction, unresolved/held state, already-encrypted ciphertext
  substitution, FK/root reassignment, missing payload, repeat cleanup and
  rollback/fencing failures.

## Alternatives considered

- **Selected:** the split and explicit engineering hold rules; resume remaining D
  in the existing development chat under the conditions above.
- Not selected: a separately privileged owner-role retention process. Main would
  specify the role/entrypoint/credentials and ordinary-worker boundary, plus how
  private immutable action fields become eligible without an application bypass.
  No such privileged role/process is authorized by the selected decision.

Merely postponing retention cannot satisfy D's acceptance gate. Turning off
triggers, editing applied migrations, silently deleting unresolved/held state,
retaining private action text indefinitely while claiming redaction, or weakening
regression assertions are not acceptable alternatives.

## Required verification after acceptance

Fresh/upgrade data and all previous checksums; lossless legacy private values and
ciphertext migration; target/scope/action/audit mutation denials unchanged;
scoped history/assignment/hold reads and current staff revocation; terminal365-day
boundary, unresolved and current/expired hold cases; concurrent hold versus cleanup;
key-reference lifetime; crypto/checkpoint/audit/outbox failure rollback; duplicate,
restart and bounded retention passes. Preserve all accepted reports/content/feed
tests and complete the full package/benchmark/Compose/scanner gates.

## Current executable boundary

At main's acceptance, V0029 only implemented account/session epochs and guarded versioned lifecycle owner
commands. V0030/V0031 now implement the accepted payload/control boundary locally;
the decision and Main implementation conditions above remain unchanged. Actual
code hashes, full checks and review status belong in the fixed mailbox/Phase8
record. This is design acceptance, not self-acceptance of the completed package.
