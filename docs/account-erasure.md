# Account erasure — implemented package 9A-I

Local synthetic implementation, pending main review. Read [ADR-017 C01–C05](adr/017-account-erasure-and-recovery.md),
[inventory](work-packages/phase-9a-data-inventory.md), [package plan](work-packages/phase-9a-plan.md)
and [operator guide](../ops/erasure.md). This is **not complete erasure**, a restore
implementation, production policy or permission to process developer/real data.

## Activation and lifecycle

Default/local/normal Compose leave `app.erasure.enabled=false`. A valid close now
fails closed503 without explicitly configured isolated synthetic policy/archive;
it never falls back to the old receipt-free204. Ownership/recent-auth/version
denials still apply first. Enabling requires `isolated-synthetic-v1`, loopback or
isolated Compose `postgres` JDBC URL with database `p9a_*`, and an explicitly
configured real directory beneath `/tmp/social-p9a-*`. Explicit `app.erasure.lineage`
/ `ERASURE_LINEAGE` is a stable synthetic source UUID persisted in every intent,
journal and certificate; another configured lineage cannot execute/acknowledge it.
No memory/test adapter silently becomes a runtime archive. U01–U06 remain open for production.

`Authentication.close` performs two short transactions around an independent
append/verification: validate current session/five-minute freshness/account
If-Match/zero ACTIVE community ownership, persist/coalesce a PREPARED export;
append outside every business transaction; then repeat all checks, atomically
commit DELETION_PENDING, request/roster/COMMITTED export, session revocation,
identity-pruning marker, audit and unchanged AccountDeletionRequested v1. A lost
receipt/204 is protected by the immutable PREPARED record. An unmatched intent
does not authorize deleting an ACTIVE account; future recovery must quarantine
it. No public progress token, reactivation, forced close or ABORTED API exists.

No package-I account can reach DELETED. Application completion checks refuse any
missing/current-version/round proof and stale certificate; a database trigger
also rejects a new DELETED transition. Later package-II approval must replace
that negative guard with the complete tested finalization protocol, not disable
it operationally.

## Fixed roster, progress and execution

The twelve `(module,step)` identities in AccountErasure.Step are persisted, not
inferred from discovered consumers. Eight later steps (community, graph, content,
moderation, media, notification, search, completion-manifest) stay
WAITING_DEPENDENCY / OWNER_NOT_IMPLEMENTED, without jobs. Implemented kinds are
ERASURE_EXPORT, ERASURE_RECONCILE, ERASURE_SCAN, ERASURE_TECHNICAL,
IDENTITY_ERASURE and PROFILE_ERASURE. Owner handlers alone use account.api
begin/advance/resource acknowledgements; focused architecture tests restrict
those callers. Account imports no higher owner and platform imports no business
module or foreign SQL. Existing delivery receipts do not substitute for step proof.

Account guard precedes step/owner locks; reconciliation/admin/finalization choose
exclusive subject guards initially. Worker job fence comes first. DB owner
effects, checkpoint/version, audit, continuation and worker ack commit together.
One page contains at most100 collection items plus fixed protocol/root rows;
checkpoints are scalar stage/UUID, application-bounded128 characters and DB-bounded
4096. Missing prerequisites do not spend a fresh failure budget. Technical prepare
defers on the same fenced job while inventory is incomplete. Timing/owner waits
are not success. Ten failures mean DEAD; reconciliation does not mint a replacement
execution. Resume requires current ADMIN, current overall If-Match, the exact
DEAD execution and mandatory audit; cumulative failures and old jobs remain.

Admin GET/POST routes are documented in [OpenAPI](api/openapi.yaml). Reads are
audited/no-store and contain only request/account/protocol/policy/version and fixed
step status/reason/check time/update/failure metadata. No raw checkpoints, former
handles, email, object keys, ciphertext or resource lists escape. Ordinary users
cannot poll even their own UUID. Missing precondition428, stale412, live/not-DEAD/
unimplemented409, malformed/extra fields400; unsafe calls keep CSRF/Origin/body16KiB.

Minute reconciliation keysets pending/deleted IDs in100-ID pages even if old
events/deliveries disappeared. Historical pending state with authentic t0/version
gets a conservative late PREPARED/COMMITTED baseline and complete roster. This is
explicitly not retrospective backup safety. Invalid generation/time is a visible
erasure_issue, not an invented t0. Historical DELETED without this protocol waits
for packageII residual verification, never becomes ACTIVE or silently healthy.

## Independent archive and manifests

SyntheticRecoveryArchive writes real restricted local files, fsyncs data before
atomic no-overwrite hard-link publication, fsyncs the directory, and rereads SHA-256
integrity. Same record ID/digest retries are idempotent; another digest, missing or
corrupt file is a failure. Restart verification uses the same persistent files.
The directory is separate from synthetic PG/object backups, but is **not** a
production independent failure domain, immutable provider, authenticated latest
cut/anti-rollback catalog or restored-service release attestation. Those are III/U04.

Journal payloads allow only request/subject/resource UUIDs, protocol/policy/roster,
generation/round/operation/owner/kind and times. Resource chunks contain1–100 UUIDs,
immutable body/digest and verified receipt. A request maintains a serialized
append-chain manifest digest; receipt success is separately checked. Owners retain
their last enumeration mapping until the chunk receipt is verified. Export
effects run outside locks; receipt/current step/worker fence ack runs transactionally.
DEAD resource export appears in admin progress and can be resumed by its owner
selector without changing the immutable record or hiding its failure count.

ErasureCertificate binds request/subject/generation, protocol/policy/roster,
verification round, resource-manifest digest and exactly eleven current prerequisite
proof/version/round entries. It has no own final-step receipt/version or DELETED
event field (C02). Successful certificate export/finalization is not enabled in I.

## Identity and technical drain

Credential removal starts promptly after recovery-intent proof. Token/registration
pages first export opaque MAIL_PROOF IDs; rows remain until verified receipt,
then pending target-mailboxes are captured in identity-owned transient rows and
proof rows deleted. Ordinary pruning preserves marked bound proof/registration
mappings. Bounded stages remove bound OAuth/PKCE, oldest-first refresh generations
(including self-FK chains over100), then sessions. No raw email/provider identity
enters account checkpoints or archive.

Login email/provider uniqueness is retained until the full anonymous chain bound
passes: LOGIN10m → onboarding10m → mailbox30m =50m from t0, using DB time for
erasure eligibility. OAuth repeats original transaction expiry **after** external
exchange; mailbox completion reevaluates time after acquiring its intent lock.
Thus an unbounded network exchange cannot renew a pre-cut proof. Unknown anonymous
transactions are not attributed by guessed email; unrelated anonymous ciphertext
keeps its normal TTL. Existing per-email HMAC issuance windows are not reset.

ScopedWork correlates new authenticated mail by opaque producer scope; captured
legacy proof IDs select exact dedup handles. Its cancellation is out-of-business-TX,
job-first and deterministic,100 jobs/pass; it fences leases, removes ciphertext,
records cancelled_at/cancellation_code and preserves attempts/last error. Old
uncorrelatable mail is retired only at expiry and all known pre-cut payload expiry
times are checked, not guessed from30m. Each claimed SMTP invocation has a durable
work_effect generation; cancellation/lease expiry is not evidence that the external
call stopped. Only the original invocation returning records ended_at. Unproven
crashed invocations remain WAITING_EXTERNAL / SMTP_DRAIN_UNPROVEN; no force-clear
resume is provided. Already delivered mail/provider copies cannot be recalled.

The technical job plus captured step version and immutable resource chunks are
the durable cancellation intent; prepare/effect/ack can repeat safely. A separate
bounded stage removes the subject's idempotency references. Only real technical
DONE and the50m barrier release email/provider claims. Final identity checks prove
zero scoped secrets/sessions/mailbox mappings; a minimal pruning marker UUID stays.
Re-registration needs fresh mailbox/provider proof and creates a new UUID, with
no old content/relationship/session recovery or email-based linking.

## Profile and later owners

Avatar IDs are externally receipted before the profile drops its last binding
mapping; release uses the existing media API in the owner transaction. It does
not certify object/version absence. Profile/preferences are deleted, current handle
reserved until t0+30d without a former-user pointer, and an immutable UUID/version
absence tombstone remains. ProfileUpdated v1 uses the tombstone version; bounded
owner scan/inspect returns this blank inactive source, not old display-name fallback.

One profile-owned canonical handle_claim key arbitrates LIVE and RESERVED states.
Backfill is lossless; DB triggers/deferred completeness enforce live ownership
for creation/rename, and prohibit erased-UUID reprovisioning. All paths use the
same unique claim row. Creation takes no advisory key: unique insertion/locked
existing-claim checks serialize it against competing creators and expiry. Bounded
rename/reserve/expiry retain sorted advisory keys before their claim rows. V0037
avoids lock-table exhaustion for bulk creation without raising PostgreSQL limits.
Exact expiry
permits a fresh claimant; synthetic scheduled cleanup removes at most100 expired
reservations transactionally with system audit. No permanent handle redirect exists.

C01 moderation case liveness is unchanged: no new moderation ban, trigger exemption,
redaction or fake metadata-only action is introduced. Existing pending-subject
review/resolution must remain possible under ADR-016; its inventory invalidation,
retention/holds and owner completion are packageII. All social-source/media purge/
projection/restore implementations remain gated. UUID tombstones, immutable audit/
job metadata, expiring restricted backups and already delivered external copies
are explicit retention exceptions, never anonymity/compliance claims.
