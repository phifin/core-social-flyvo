# Account erasure — local synthetic packages I–II

Local synthetic I implementation accepted at `bbbde93`, G1 review `faf0ad8`.
The [II candidate](phases/phase-9a-ii.md) implements the assigned source/media/completion
package and awaits main review. Read [ADR-017 C01–C05](adr/017-account-erasure-and-recovery.md),
[inventory](work-packages/phase-9a-data-inventory.md), [package plan](work-packages/phase-9a-plan.md)
and [operator guide](../ops/erasure.md). This is no restore implementation,
production policy or permission to process developer/real data. Unknown physical
writers and legitimate retained moderation payload keep a subject pending.

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

Package I cannot reach DELETED. II replaces its negative guard through new account
migrations with strict current proof/receipt/certificate checks. Finalization takes
the exclusive subject guard and fixed roster rows in order; compares the captured
eleven-prerequisite vector; then atomically commits its verified receipt, completion
DONE, terminal account status/epoch/version, audit and AccountDeletionCompleted v1.
The certificate excludes its own step version/receipt and final event. A stale
vector never certifies its successor; a new immutable attempt is required. No
owner callbacks or archive/network calls occur in that final transaction.

## Fixed roster, progress and execution

The twelve `(module,step)` identities in AccountErasure.Step are persisted, not
inferred from discovered consumers. II registers all twelve steps with real owner
handlers; unknown protocol/owner still fails closed. Package-I-only deployments
retain their explicit OWNER_NOT_IMPLEMENTED wait. Implemented kinds include
ERASURE_EXPORT, ERASURE_RECONCILE, ERASURE_SCAN, ERASURE_TECHNICAL,
IDENTITY_ERASURE, PROFILE_ERASURE, COMMUNITY_ERASURE, GRAPH_ERASURE, CONTENT_ERASURE,
MODERATION_ERASURE, MEDIA_ERASURE, NOTIFICATION_ERASURE, SEARCH_ERASURE and
ERASURE_COMPLETE. Independent moderation exports use MODERATION_PRESERVE and the
bounded MODERATION_PRESERVATION baseline scanner. Owner handlers alone use account.api
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
erasure_issue, not an invented t0. Historical DELETED without trustworthy request/
generation provenance remains a visible issue for authoritative review; II never
guesses a prior pending generation, relabels it ACTIVE or silently certifies it.

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

Every non-PREPARED journal must match the immutable request ID/subject/lifecycle
generation, verification round and t0 of its own intent. A delayed PREPARED for
rejected close A may retain its own verified receipt, but cannot invent COMMITTED(A)
merely because a later request B exists for the same user. Historical backfill
is allowed only for that exact intent/request. No archive record is rewritten or
deleted to hide an unmatched intent or old invalid COMMITTED artifact.

The external worker supplies its immutable producer execution key in the lease.
Export preparation/ack/failure compares it with the export's current execution;
COMMITTED ack also compares the captured recovery step/version/round after reacquiring
the guard. Superseded executions cannot write receipts or charge the successor's
failure count. An invalid old export records only its own failure history, not
another request's recovery proof/status. Normal retries, DEAD and audited resume
keep the same immutable journal and existing worker fence.

ErasureCertificate binds request/subject/generation, protocol/policy/roster,
verification round, resource-manifest digest and exactly eleven current prerequisite
proof/version/round entries. It has no own final-step receipt/version or DELETED
event field (C02). ERASURE_COMPLETE exports that canonical certificate outside
locks and matches exact intent/request/generation/round/step execution on ack.
Superseded success/failure has no successor effects. Missing JSON bindings, unknown
resource owner/kind, incomplete roster or missing receipt also fail DB checks.

## Identity and technical drain

Credential removal starts promptly after recovery-intent proof. Token/registration
pages first export opaque MAIL_PROOF IDs; rows remain until verified receipt,
then pending target-mailboxes are captured in identity-owned transient rows and
proof rows deleted. Ordinary pruning preserves marked bound proof/registration
mappings. Bounded stages remove bound OAuth/PKCE, oldest-first refresh generations
(including self-FK chains over100), then sessions. No raw email/provider identity
enters account checkpoints or archive.

Login email/provider uniqueness is retained until the full anonymous chain bound
passes: LOGIN10m → onboarding10m → mailbox30m =50m from t0. Issuance, capability
validation and erasure eligibility use the same PostgreSQL `clock_timestamp()`
authority, not JVM-clock offsets or transaction-start `now()`. Onboarding is read
FOR UPDATE before sampling time; callback and mailbox validation also sample after
their row/account guards. Dispatch-window/account waits and final registration
writes are followed by another expiry check; failure rolls back consumption,
successor intent/mail and all registration effects in that transaction.

For an anonymous LOGIN issued at L, its fixed expiry is E=L+10m. The callback must
finish before E, including network and account-guard waits; onboarding expiry
O≤E+10m. Registration must consume before O, even after dispatch waits, and its
mailbox expiry M≤O+30m. Therefore M≤L+50m≤t0+50m for pre-t0 chains. Successor
deadlines never renew from a late wait. Expiry is strict: equal time is invalid.
A callback's committed state consumption is not an unlimited network credential;
failure after exchange keeps it consumed without minting onboarding/session.

The bound assumes trusted source-issued TTLs and one authoritative DB time domain;
it is not certification of arbitrary imported/old-binary proof rows, clock rollback,
or restored data. Do not activate maintenance on existing developer data or reuse
an unverified old lineage. Those activation/recovery/clock-operability gates remain
with main/U04/U06. Unknown anonymous transactions are not attributed by guessed
email; unrelated anonymous ciphertext keeps its normal TTL. Existing per-email
HMAC issuance windows are not reset.

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

## Profile and source owners

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

Community clears effective memberships/admissions/messages, retaining archived
owner FKs and terminal attributable history. ACTIVE ownership is a visible blocker,
never an automatic transfer/archive. Invitation acceptance guards inviter/invitee;
a terminal inviter cannot issue an effective admission through an old invitation.
Graph clears both directions and pending requests under canonical pair locks,
retaining pair IDs/sequence. At most12 pairs/96 possible relation items are handled
per pass. New inbound blocks to a terminal account are denied; ordinary removals
and blocking a suspended account retain their existing behavior.

Content first removes current subject references, narrowing the last CUSTOM
recipient to ONLY_ME in the same post transaction. Other authors' text/revisions/
reply UUIDs remain immutable. Owned posts/comments soft-delete and reaction choice
becomes a one-way null tombstone. At t0+30d nested stages remove revisions,
recipients, mentions, attachments/alt text, tags, body/link and owned comment text
in at most100 collection items per pass. The documented one-post+100-recipient
atomic audience exception remains bounded; fixed root/protocol rows are separate.
An erasure origin/terminal author prevents queued ordinary PostRetention from
redacting another author's descendants. Media IDs are receipted before their last
content mapping is removed. Terminal post/comment/reaction shapes reject restoration.

Media independently enumerates all owned states/generations, verifies asset-ID
chunks, waits for source release, claims a stable generation fence and requires
positive writer drain before purging every version/delete marker. Short prepare/
ack transactions surround external drain/storage calls. Exact asset/request/round/
step/execution fences protect once-only quota release and metadata stripping.
No runtime drain adapter is installed; disabled storage or unknown writer completion
keeps existing assets WAITING_EXTERNAL. Periodic sweeps/lease expiry are not proof.

Notification projection takes complete sorted actor/recipient account guards before
recipient rows and rechecks source eligibility; terminal principals create no new
source/default state. Cleanup preserves V0032 ordering/decided-through watermarks,
deletes contributions in100-item pages before deleting their group and preserves
other eligible actors/read versions. Permanent recipient markers plus account
denial survive30-day receipt and120-day inactive source-state pruning. Search
takes owner guards before rebuild/document locks, re-reads snapshots and creates
permanent blank USER/POST markers even in an empty index. Owner post enumeration
does not depend on an old index row. Feed continues to authorize current sources.

C01 existing pending-subject cases remain reviewable/resolvable with mandatory
encrypted action detail. Complete structural account guards precede moderation/
completion proof invalidation and community/case locks. Ordinary new roots for a
pending subject and all new private payload for DELETED are denied. Structural
report/action/history/target/source/parent scans never decrypt foreign free text.
Unresolved cases, holds and terminal+365d retention produce explicit waits. Legitimate
redaction still follows root→payload and preserves keys while ciphertext remains.

Moderation's independent exporter records current control plus bounded structural
history pages, including one-way redaction state. A hold/renewal/release persists
PRESERVE intent, appends/verifies outside locks and repeats current authority/root/
If-Match checks before committing control/history/audit and COMMITTED intent.
Archive failure leaves the prior hold intact; an unmatched external intent remains
conservative recovery evidence. Current ADMIN can explicitly resume at most100
DEAD immutable exports through PRESERVATION maintenance; cumulative failures and
old executions remain. No restore/import/latest-cut exposure gate is implemented.

UUID tombstones, immutable audit/job/control metadata, expiring restricted backups
and already delivered external copies remain declared retention exceptions, never
anonymity/compliance or production-durability claims. III and U01–U06 remain gated.
