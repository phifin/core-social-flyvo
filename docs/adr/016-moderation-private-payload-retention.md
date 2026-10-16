# ADR-016 — Retained moderation metadata and redactable private payloads

Status: Proposed by development, 2026-10-16. **Not accepted or implemented.**
Main decision required for package blocker P78-D1 before the D retention migration.
This proposal does not accept either phase or authorize Phase9/legal erasure.

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

## Proposed decision — recommended for main approval

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
   This maximum/scope/renewal rule is a requested main decision, not an inferred
   legal policy. If a different hold policy is required, main must specify it.
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

## Alternatives for main

- Approve the proposed split and explicit engineering hold rules, then resume D
  in this development chat. This is the recommended narrow resolution.
- Require a separately privileged owner-role retention process instead. Main must
  specify the role/entrypoint/credentials and ordinary-worker boundary, plus how
  private immutable action fields become eligible without an application bypass.
  Do not implement the split or invent that authority until the choice is made.

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

V0029 only implements account/session epochs and guarded versioned lifecycle owner
commands. It changes no moderation/audit trigger, payload, hold or retention rule.
No assignment/history/retention or new platform moderation HTTP endpoint is claimed.
The actual verified code hashes and checks belong in the fixed mailbox; this ADR
remains Proposed until main records its decision.
