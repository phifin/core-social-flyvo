# Local synthetic erasure operations — 9A-I

Use only the assigned isolated synthetic fixture, never existing developer Compose,
`.local` data, real users/buckets/backups or a production endpoint. No restore is
implemented/authorized here. [Protocol](../docs/account-erasure.md),
[ADR-017](../docs/adr/017-account-erasure-and-recovery.md) and
[evidence](../docs/phases/phase-9a-i.md) define scope and limitations.

## Explicit activation

Normal/default startup and checked-in Compose keep new destructive maintenance
off. To run the local protocol in a separately validated sandbox, an operator must
explicitly supply all of:

| Property / environment | Required value |
|---|---|
| `app.erasure.enabled` / `ERASURE_SYNTHETIC_ENABLED` | `true`, only in the isolated synthetic fixture |
| `app.erasure.policy` / `ERASURE_POLICY` | `isolated-synthetic-v1` |
| `app.erasure.lineage` / `ERASURE_LINEAGE` | Explicit stable synthetic source UUID; retain with its authoritative archive; mismatch fails closed |
| `app.erasure.archive-directory` / `ERASURE_ARCHIVE_DIRECTORY` | Existing restricted directory beneath a unique `/tmp/social-p9a-*` sandbox, outside PG/object backup copies |
| database URL | loopback test PG or isolated Compose `postgres`, explicit port, `p9a_*` database |
| worker | Explicitly enabled only on that synthetic data; tests otherwise drive the real fenced executor manually |

Do not add these flags to the developer's existing `.env`/volumes. Do not use a
temporary in-memory archive on error. Configuring the normal `social_core` database
with erasure enabled fails startup; a missing policy/archive cannot make close204.
All standard auth/storage secret mounts remain external. The local archive proves
protocol mechanics, not independently trusted freshness/production durability.

## Observe, repair, resume

Use a current ACTIVE ADMIN through the existing authenticated/CSRF-protected API:
GET `/api/v1/admin/accounts/{uuid}/erasure`, inspect ETag and safe fixed-step reasons.
No status credential is issued to the revoked user. Support must use an approved
operator process, not revive an old session.

OWNER_NOT_IMPLEMENTED means wait for main to activate/review II, not retry it.
ANONYMOUS_PROOF_DRAIN and LEGACY_MAIL_EXPIRY are bounded validity waits, not erasure
completion deadlines. SMTP_DRAIN_UNPROVEN requires genuine external-invocation
completion evidence; a lease timeout, cancellation fence or arbitrary SQL clear
does not supply it. Historical integrity/DELETED blockers need source investigation
and the later package gate. Never fabricate a t0, proof, receipt or owner DONE.

For FAILED/DEAD, fix the actual cause first (archive path/access/integrity, database,
handler or audit). Preserve original failed logs/jobs/record digests. POST
`.../erasure/resumes` with current strong If-Match and `{"step":"IDENTITY"}` (or
the relevant fixed selector) creates a new audited bounded execution only for DEAD.
The owner selector also resumes its failed resource export; RECOVERY_INTENT handles
failed request exports. The immutable record/chunk/checkpoint and cumulative failure
history survive. Stale412/live409 require rereading, not forced updates. Reconciliation
does not reset the ten-attempt budget, resurrect mail, clear a hold or skip an owner.

Do not repair missing archive bytes by copying a matching old DB backup into the
authority directory and claiming freshness. Missing/wrong records are integrity
failures. PREPARED without committed closure is conservative recovery quarantine,
not consent to delete ACTIVE data. ABORTED adjudication/current-cut recovery and
release attestations await III; no restore or production provider is selected.

## Upgrade and rollback

Stop writers for schema upgrade. V0001–V0032 stay unchanged; V0033–V0037 add account
protocol, technical effects/cancellation, identity traversal and profile claims/
tombstones and bounded handle-creation lock behavior. Fresh,32-upgrade, every new prefix/checksums and failed-migration atomic
rollback are tested. No mixed V0032/Phase9 writer or old-binary rollback compatibility
is claimed. After an intent/effect is activated, keep service closed and forward-fix
unless an exact compatible Phase9 previous artifact has separately been tested;
none precedes this first package. A binary rollback cannot recover erased bytes,
and restoring a backup is not a rollback shortcut.

U01–U06, real storage writer bounds, immutable/latest-cut authority, backup/key/
provider/admin/legal/capacity controls, full source erasure and no-resurrection
restore remain public-launch blockers. Local PASS or a local commit is not launch.
