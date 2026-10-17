# ADR-017 — Owner erasure checkpoints and recovery without resurrection

Status: **Proposed**, 2026-10-17. Requested decision by main; no acceptance,
implementation, data deletion, restore or deployment is implied. This completes
workstream B of the [Phase 9A design assignment](../work-packages/phase-9a-design.md).
Evidence: [56-table inventory](../work-packages/phase-9a-data-inventory.md).
Execution/test gates: [plan](../work-packages/phase-9a-plan.md).

## Context and recommended option

At executable `d2a8e3c`, `Authentication.close` calls `Accounts.requestDeletion`
and revokes sessions in one guarded transaction, after recent authentication,
account If-Match and no-active-community-owner checks. `UserAccount` has no final
DELETED operation. Search consumes the deletion event; notification does not.
There are no account erasure checkpoints, independent manifests or restore gate.
V0032 and ADR-016 are latest. Source authorization already denies inactive authors;
that is an immediate access boundary, not proof of byte removal.

Recommend a **fixed account-owned erasure roster with owner-run durable steps**,
plus an independent append-only recovery ledger and an exposure barrier on restore.
Use existing local transactions, EventConsumer, JobHandler/ExternalJobHandler,
receipts, 60s fencing leases and bounded retries. Account stores lifecycle/progress;
owners retain policy, SQL and resource-specific proofs. No generic workflow engine,
new business module, reverse dependency, foreign SQL seam, broker or permission cache.

The strict completion choice intentionally keeps DELETION_PENDING while applicable
moderation private payload is held, unresolved or younger than ADR-016 eligibility.
DELETED means the approved scoped private-data erasure is complete with enumerated
minimal metadata/backup exceptions. It is not a promise of anonymity or removal
from another person's downloads, authored words or provider systems.

## Decision register (all awaiting main review)

| ID | Recommended engineering decision | Alternative / cost |
|---|---|---|
| P9A-D01 | Fixed versioned roster, account progress, events to owners and owner→account acknowledgement; no higher-module calls from account | Central orchestrator importing owner APIs breaks DAG; generic saga/workflow engine adds unjustified dependencies; pure event delivery cannot certify all pages |
| P9A-D02 | Strict DELETED only after every required step, including applicable moderation payloads and external versions, proves DONE; retain approved minimal metadata and restricted expiring backup copies | A separate COMPLETE_WITH_EXCEPTIONS lifecycle could finish despite private evidence, but changes product semantics and requires a superseding lifecycle/policy decision; do not silently equate that with DELETED |
| P9A-D03 | Scoped authorship deletion and pseudonymous reference retention; preserve others' text/replies; narrow last-recipient CUSTOM to ONLY_ME; retain archived-owner FK; erased reaction type shape | Wholesale cascades lose others' content/invariants; rewriting immutable UUID history loses attribution. Retaining all subject text indefinitely does not deliver erasure |
| P9A-D04 | ≤100 collection-item/payload passes plus fixed root/protocol rows, durable intent/fence/ack, current worker ten-attempt budget, separately proven external writer quiescence; typed technical platform cancellation | Unbounded transaction/queue or periodic sweep alone cannot prove completion; generic cancellation by UUID substring risks cancelling unrelated work |
| P9A-D05 | Keep DELETE /me/account success204 after durable guarded request; progress/resume visible only to current audited ADMIN. No user progress credential initially | A deletion-status capability would improve self-service but introduces new credential/recovery/abuse requirements; retaining a revoked session is unacceptable |
| P9A-D06 | Fresh-proof re-registration under new UUID; release email/provider claims after stale proofs drain; reserve handle30d then release with database uniqueness | Permanent email/provider/handle reservation retains extra personal data and blocks re-registration; immediate handle reuse increases impersonation risk; no automatic linking in any option |
| P9A-D07 | Permanent terminal-account guard for every projection/write path, source-authoritative replay/rebuild and bounded startup/reconciliation scans of pending **and deleted** subjects | Relying on30d receipts/120d notification source rows alone eventually allows stale state to reappear |
| P9A-D08 | Independent conservative intent ledger before close commit; immutable resource manifests, verified current recovery cut, preservation-control recovery and fail-closed exposure barrier | DB-only manifest rolls back with old backup; asynchronous export alone leaves a loss window; all-or-nothing DB/external atomicity is not available. Conservative false-positive quarantine trades availability for no resurrection |
| P9A-D09 | Preserve ADR-016 unchanged:365d eligibility, unresolved/held preservation, immutable metadata/audit and key lifetime. Declare audit finite-disposal policy unresolved | Accelerated evidence erase or privileged audit deletion needs a separately accepted superseding ADR, not a job exception or shared-key destruction |
| P9A-D10 | Three sequential implementation packages with internal gates; local synthetic evidence separate from environment/product/public-release signoff | One thread per small module creates partial handoffs; one unreviewed package spanning public rollout hides external dependencies |

Main can accept engineering decisions only; the following inputs need the actual
product/environment owner. None prevents this concrete proposal or makes design
BLOCKED. Do not ask for credentials in chat or contact providers/staff.

| ID | Required input before dependent implementation/launch | Recommended starting choice |
|---|---|---|
| P9A-U01 | Jurisdiction, age/minor/consent, data-controller retention/legal bases, immutable audit disposal, third-party authored references, handle reuse and user wording | Approve engineering defaults for local fixtures only; publish a scoped erasure notice and resolve finite audit disposal before public launch. No legal compliance claim |
| P9A-U02 | Enabled providers, verified callback/deauthorization obligations, SMTP/domain and mail copy retention | Keep providers disabled until approvals/contracts and real smoke tests recorded; no automatic new callback API before verified contract |
| P9A-U03 | Production storage/region, all-version/replica deletion, IAM/KMS, bounded total upload/worker write duration, object lock or retention conflicts | Private versioned storage and enforced total ingress deadline within existing15m settling; object locks causing undeletable bytes block final completion |
| P9A-U04 | Independent ledger/backup service and failure domains, immutable storage/access/integrity authority, key recovery, supported backup/PITR windows and oldest copies | Encrypted30d recovery window; PG RPO≤15m/RTO≤4h targets from operations, but **zero loss of accepted erasure/preservation intents**; no exposure if latest ledger cut cannot be proven |
| P9A-U05 | Protected admin gateway/MFA/operators, production roles, incident/replay authority, alert destination/ownership | Current ADMIN plus protected operator access; no public metrics/recovery port or permanent scraper bypass |
| P9A-U06 | Target hardware/budget, sustained load, erasure/worker lag/latency objectives, shared abuse controls, security/dependency/license review | Keep pool10/timeouts; measure before setting SLA. Dense feed500ms remains unmet; shared rate limits before multiple public instances |

## Lifecycle and proposed public/internal APIs

Existing account statuses remain unchanged. A request is irreversible after commit;
there is no cancel/reactivate/reinstate-from-deletion command. Suspension's auth
epoch remains monotonic. Closure continues to deny suspended/non-ACTIVE subjects;
admin-forced closure/provider deletion callbacks need separately assigned contracts.

```text
ACTIVE -- guarded close + session revoke --> DELETION_PENDING
  -- all required proofs + independent completion receipt --> DELETED

Each fixed step: PENDING -> RUNNABLE -> RUNNING -> DONE
                           |   |         |
                           |   +-> RETRY / FAILED (DEAD execution)
                           +-> WAITING_DEPENDENCY / WAITING_RETENTION /
                               WAITING_HOLD / WAITING_CASE / WAITING_EXTERNAL
```

RUNNING is a fenced execution observation, not an indefinitely held business lock.
WAITING has an explicit code/nextCheckAt (CASE may have no known due time), not a
terminal success. FAILED is distinct from permitted waiting and remains visible.
DONE is irreversible within one request/protocol except an explicit verification
incident invalidation that blocks finalization and schedules a new verification
round. A late object after declared quiescence is such an incident. Account status
never returns ACTIVE; a DELETED incident is re-erased/alerted, not relabelled healthy.

Proposed internal account contracts, names descriptive and not implemented:

| Contract | Inputs / result / transaction |
|---|---|
| `AccountErasureQueries.scanSubjects(after,statuses,limit)` | Pending/deleted ID keyset,≤100; account-owned SQL only; owner reconciliation consumer, no HTTP directory |
| `AccountErasureCommands.begin(requestId,owner,step,passVersion,protocol)` | MANDATORY transaction; acquires subject shared lifecycle guard then step row, validates terminal request/roster/prerequisites and returns current scalar checkpoint/proof context. It accepts DELETION_PENDING for maintenance, not user impersonation |
| `advance(context,expectedStepVersion,nextCheckpoint,result,evidence)` | Same transaction, already-held guards/step row; owner effects+audit+step update+next job+worker fenced ack commit together. Evidence is scalar safe IDs/counters/digests/receipt refs, no PII |
| `recordResources(context,kind,IDs)` | ≤100 UUID references, immutable owner-tagged manifest chunk with export intent in transaction; no resource body/prefix selected by caller. Media needs this for asset IDs absent from later DB backup |
| `reconcilePending` / `finalize` | Account-owned bounded JobHandlers; seed roster/resume/finalize using only account SQL and platform technical APIs. Never inspect owner base tables or invoke higher-module callbacks |

Every importing owner is already allowed account.api: identity/profile/community/
graph/content/media/notification/search/moderation. Restrict ack calls by focused
architecture rules to the responsible owner handler/step; public controllers cannot
submit acknowledgements. In-process module integrity, current request/version and
real owner transactions are the trust boundary, not a client-provided `isSystem`.

Platform publishes only a narrow proposed technical `ScopedWork` cancellation/
drain API and `RecoveryArchive` append/verified-receipt adapter. Scope is an opaque
technical UUID/class, established by producer, never policy resolved by platform.
Account owns the `technical` roster step and calls that API; platform never imports
account or acknowledges business progress. Identity supplies old job references
through account's bounded manifest/cancellation inventory before credential purge.
No new SQL-view consumer or graph/content/media reverse call is allowed.

Proposed HTTP (only implement/update OpenAPI after acceptance):

| Route | Exact proposed semantics |
|---|---|
| Existing `DELETE /api/v1/me/account` | Preserve If-Match, recent auth≤5m, Origin/CSRF, no active community ownership; success204/no-store after close commit, cookie cleared. It means request accepted, not erasure complete. No returned public status URL/token. 428/412/409/401 existing meanings; archive/network503 may prevent final commit |
| `GET /api/v1/admin/accounts/{userId}/erasure` | Current authenticated ACTIVE ADMIN, separate protected gateway at launch; audited no-store read of userId/requestId/accountStatus/protocol/policy/version and fixed step status/reason/nextCheckAt/updatedAt. No names/emails/ciphertext/root/other-user IDs, raw checkpoints, storage keys or estimates presented as deadlines. Missing target404, ordinary user403, unauthenticated401, required DB/archive unavailable503 |
| `POST /api/v1/admin/accounts/{userId}/erasure/resumes` | Same current authority+CSRF/Origin; strong If-Match on erasure version. Typed fixed step selector only; body≤16KiB, no arbitrary payload/checkpoint/owner/status override. Missing428/stale412/invalid400, ACTIVE target or live execution conflict409. Audit+new bounded resume execution atomic; never force DONE, clear holds, replay expired mail or reset source ordering |

An ADMIN looking at another account must be attributable. An ordinary user cannot
poll a UUID, and deletion never re-enables their revoked session. A lost204 response
does not justify replaying recent auth with that revoked session: documented UX is
logged out, status via approved support/operator process, not a credential bypass.

The final account transaction must simultaneously prove: status DELETION_PENDING
for this request/lifecycle generation; exact current protocol/policy/roster with
every required step present and DONE; each proof bound to that step's current
version and verification round; no unresolved failure/wait, unexported resource
chunk, new structured subject association or outstanding writer intent; and the
independently verified completion receipt for that exact immutable proof digest.
Take subject exclusive then all step rows before checking/changing state. Persist
DELETED, final timestamp/version, minimal audit and AccountStatusChanged fact
atomically. Safe historical DEAD/outbox/audit metadata and expiring restricted
backups are declared exceptions, not failed private-payload work hidden by a flag.

## Persisted request, roster and bounded steps

Proposed account-owned schema (new migrations only): request row keyed by stable
request UUID/unique user UUID, t0, immutable lifecycle generation (committed account
version at request), protocol/policy/roster digest, overall version, last scan and
independent archive receipts. Before close, a separate prepared intent stores the
expected account version without pretending the request has committed.

`account.erasure_step` keeps unique `(user_id,module,step)`, request FK, status,
version, bounded checkpoint, cumulative counts, due/check time, safe error code,
last execution ID, proof ref/digest and completedAt. No JSON arrays that grow with
account size. A small owner-tagged resource-manifest chunk table has stable UUID
key,≤100 resource UUIDs and immutable digest; paginate it, never store the entire
resource list on the request. Migration has shape/size/status/one-way guards and
indexes for status/due, subject/owner and manifest export backlog.

The version1 roster is fixed, not “whatever consumer happens to be enabled”:

| Step identity `(module,step)` | Required predecessor | Actual work / completion condition |
|---|---|---|
| `account/recovery-intent` | none | Independent conservative PREPARED intent receipt exists before close; committed request/roster and COMMITTED export intent durably exist |
| `identity/access-and-secrets` | recovery-intent | Revoked immediately; capture mail/proof correlation, delete credentials/links/proofs/sessions with bounded FK-safe ordering; stale pre-cut anonymous proofs drained/expired |
| `profile/social` | recovery-intent | Avatar release intent, handle reservation, profile/preferences gone, safe absence attribution/search export |
| `community/participation` | recovery-intent | Zero ACTIVE ownership/effective memberships/pending subject admissions, request messages gone, required archived-owner UUID rows retained |
| `graph/relations` | recovery-intent | Zero live subject edges/requests, terminal pair ordering retained |
| `content/authored-and-references` | recovery-intent | Prompt soft-delete/reference repair and media releases; after30d zero subject text/link/revisions/alt text/reaction type; preserve other authors' descendants/immutable refs |
| `moderation/private-payloads` | recovery-intent | Structural-root inventory includes subject actor/reporter/assignee/target/source IDs; legitimate ADR-016 redaction completed for all applicable private payloads, safe immutable metadata only |
| `account/technical` | identity cancellation inventory captured | Correlated secret mail drained/cancelled, expired unbound ciphertext swept, obsolete writer work fenced; safe outbox/DEAD/audit metadata is an explicit exception |
| `media/objects` | profile and content released their owned bindings | Every state inventoried; stop generations, drain old writers/capabilities, all-version/marker absence proven; zero charge; strip excess object metadata. Not DONE merely because SQL says DELETED |
| `notification/derived` | identity/profile/community/graph/content/media/moderation/technical DONE | Terminal guard installed at t0; final pass deletes inbox/actor contributions and target residues, retains minimal denial ordering; eligible remaining groups unchanged |
| `search/derived` | same source predecessors | Terminal guard installed at t0; final owned-ID scan blanks all subject USER/POST text/vector, live/rebuild stale snapshot commits rejected, final reconciliation fixed point |
| `account/completion-manifest` | every prior step DONE | External immutable completion certificate/resources/policy/roster receipt confirmed, then account final DELETED transaction/audit/outbox |

Notification/search can suppress and clean early; their final acknowledgements wait
for sources. Moderation does not hold up credential/social removal. There is no
feed step because feed owns no persistence. Registration/projection flags cannot
omit owners with existing data; a disabled module still needs its maintenance
handler or an explicit migration proving no owned data and a reviewed roster change.

Each collection pass selects/processes≤100 owned items or payloads, bounded memory/IDs/checkpoint
(recommend≤4KiB JSON, fixed stage+last composite key, counters as integers). Ten
attachments/100 CUSTOM recipients remain their aggregate bounds; do not count one
post as permission to fetch its unbounded revisions/comments. Fixed account/root/
audit/job rows are additional, explicitly measured work. A necessary atomic
audience switch plus deletion of100 CUSTOM recipients can touch101 source rows;
it is one fixed aggregate exception, not100 such aggregates per pass. Other
mention/media/tag stages retain their20/10/10 bounds. Subdivide unbounded
collections and use a stage cursor. Revision DELETE, parent/child content cleanup
and reaction flags are separate bounded stages. Subject scan/pair/community scans
advance by immutable unique keys, never OFFSET. Discoveries after an apparent last
page require a final rescan/fixed-point proof after write fences, not just a counter.

## Transactions, locking, retry and operator recovery

Worker job/delivery fence is acquired first as in the existing dispatcher; then
complete sorted account lifecycle set → account erasure step → identity session
if needed → community → graph pairs → post → comment → media quota → asset.
Moderation uses earlier account/community guards then its root→payload order;
notification uses account/step then sorted recipients→source→groups→contributions;
search uses account/step then rebuild/document rows. No unrelated owner lock chains
are combined. The maintenance command may hold the deleted subject's shared guard
without granting it ACTIVE status. If other actor/source guards are needed, pre-read
the complete≤100-row batch, acquire all accounts in UUID order, then `begin` using
that already-held subject guard and reload/revalidate before later locks.

Acknowledgement never acquires an earlier guard after owner rows: `begin` holds
the subject/step for the entire DB pass. Finalization takes subject exclusive then
step rows in roster order; it calls no owners. User write and closure therefore
serialize. Choose final lock modes initially; no shared→exclusive upgrade. No
storage/provider/SMTP/archive calls under any of these business locks.

For DB-only work, owner effects, minimal audit, checkpoint/step version, next-pass
job and existing worker fenced ack commit atomically. Duplicate request/event/pass
IDs return prior committed result; a stale passVersion does no new work. A missing
prerequisite schedules waiting with fixed next check, never a busy retry loop.

For external effects: short prepare transaction persists immutable scoped delete/
export intent and generation, commits; execute outside transactions; short ack
transaction rechecks job owner/generation/unexpired lease, account request/step
version and asset generation, writes proof/accounting/checkpoint/next job atomically.
Network effects cannot be rolled back. Crash after purge but before ack repeats
list/purge/verify safely; crash after archive append reuses immutable record ID and
checks same digest. A different digest for an existing ID is a hard integrity
failure, not a successful duplicate. Stale worker cannot mark progress/READY or
release quota, even if its external delete already happened.

Ten attempts/execution, exponential backoff+jitter5s→1h, existing60s leases and
DEAD semantics remain. Do not create infinite “new execution” retries automatically
after DEAD. DB waiting passes persist nextCheckAt without failure; external
time-only deferral follows `ExternalJobHandler.Deferred` (prepare has no effects,
same fence, spent attempt restored). Genuine failures/lease loss spend attempts.
Maintain cumulative erasure failures across explicit resumes rather than hiding
them by resetting counters. Fixed periodic reconciliation enqueues missing due
work only if no active execution and no unresolved DEAD requires review.

Operator fixes the recorded cause, inspects current scoped step/version and uses
audited resume with expected version/new execution ID, preserving request,
checkpoint, resource intents, lease monotonicity and DEAD history. New execution
gets its own ten-attempt budget; no arbitrary SQL force-complete/hold removal.
Already purged assets never replay MEDIA_PROCESS; dead authentication mail needs
a fresh proof on a live account, never an erasure resume. Expose queue depth,
oldest pending age, WAITING reasons, retry/DEAD and last proof observation with
fixed labels, no UUIDs/emails/private query/content in metrics/logs.

Technical cancellation must not lock an already-running target job **after** an
account/step lock that its worker needs. Persist an opaque cancellation intent in
the owner transaction first. Its account-owned ExternalJobHandler executes the
platform technical cancellation in separate short job-first transactions while
no business transaction is active, then acknowledges the captured step normally.
The adapter locks exact target jobs in deterministic order, fences their leases,
removes corresponding secret ciphertext and records a cancellation outcome; it
does not undo external effects. Mark cancelled jobs terminal with explicit safe
outcome metadata, preserving failure history. Drain already accepted SMTP/writer
effects before proof. New obsolete work cannot be issued after the producer's
terminal-account guard. This intentional idempotent intent/effect/ack protocol
is not a nested REQUIRES_NEW business command or a late job-lock acquisition.

## Reconciliation and named race contracts

New owner consumers register live first using unchanged AccountDeletionRequested
v1 and inspect current account request/protocol, never a guessed envelope version.
Account schedules first-owner jobs in its own transaction through a fixed roster
of technical job-kind names; it cannot invoke their handlers synchronously.
Consumers seed idempotent owner jobs through account step API. The account-owned
reconciliation job rescans `DELETION_PENDING` in≤100-ID pages, creates any missing
request/roster from authoritative account state, exports conservative historical
intent, and schedules owner work even if the original outbox/delivery/receipt was
pruned or never registered. Historical t0 missing/corrupt is a visible integrity
failure; do not fabricate an earlier timestamp/version. Delayed historical mirror
cannot retrospectively prove old backups safe: quarantine them until complete cut
and source inventory cover them. Deploy-time full pending/deleted scan is mandatory.

Every newly accepted owner/roster revision also reconciles DELETED subjects for
residuals before its new capability is enabled. An unknown protocol/owner cannot
be silently acknowledged. Existing search account repair is useful but does not
replace erasure proof or source backfill of documents absent from its own index.

| Race / replay | Required winning-order behavior |
|---|---|
| Close vs community creation/transfer | Creation/transfer share guard on proposed ACTIVE owner; closure exclusive. Transfer-first causes ownership409 unless transferred away; close-first prevents becoming owner. Unexpected active ownership during historical repair blocks, never automatic transfer |
| Close vs write/comment/reaction/report/link/reset | Complete account guards rechecked after acquisition. Write-first can commit and is included in bounded erase scan; close-first denies new user write. No network proof obtained before close can bypass guarded reread |
| Upload intent response after close, finalize HEAD after close | Already generated PUT may still be used until persisted expiry; finalize final transaction denies. No new signing from pending account. Erasure waits whole persisted capability window+settling/quiescence, not response time |
| Processing/external PUT already running | Persist erasure fence/generation under account→quota→asset; stale complete cannot READY/charge. SQL fence alone cannot stop out-of-TX PUT: drain or prove maximum effect deadline then rescan all prefixes. Unproven drain→WAITING_EXTERNAL |
| Release/bind vs erase/purge | Source owner serializes subject and post/profile before quota/asset; final media pass waits for source ack and no active binding. Never reverse-query source DB from media |
| Search live/rebuild snapshot captured before close | Every materializing upsert acquires sorted relevant owner account guards **before** search rows and rejects terminal subject after lock. Equal/newer stale snapshot cannot insert into a physically empty index or overwrite erasure marker. Rework current rebuild lock ordering if needed; no late account guard after rebuild/document locks |
| Notification route snapshot before close | Projection takes complete actor/recipient account shared guards before recipient lock and rechecks after guards. Terminal actor/recipient cannot create source/contribution/default preference rows. Source refresh stays bounded; V0032 decision semantics remain |
| Old events after receipts/source_state prune | Permanent account tombstone/projection erase fence denies creation. Current source hydration and monotonically retained versions remain; never synthesize notification origin or replay erased media |
| Hold vs redaction / retention ack | Existing common moderation root decides race; winning hold delays step. STARTED denies new preservation claim, may continue100-row passes. New applicable roots require subject account guards before moderation locks; after final exclusive account guard no new subject-private root may be admitted |
| Crash after external delete before ack | Reclaimed worker uses same intent, lists all versions, reconfirms absence and fenced ack exactly once; quota never released by stale worker |
| Crash after final archive certificate before DELETED | Certificate asserts scoped work done, not that account status committed; retry checks exact request/step versions, finalizes once. Restoring either version applies conservative terminal denial |

The moderation guard for **new** private payloads must cover referenced source/
reporter/actor/assignee subject IDs without adding earlier locks under a case root.
No new private payload about a terminal subject through normal account actions;
existing root assignment/hold/history may continue with minimal IDs only. Cases
about other users can mention someone in free text; this policy covers structural
subject association and does not pretend to discover every natural-language mention.
Unexpected new structured subject association invalidates prior proof and triggers
review; account never decrypts another owner's payload to find it.

## Independent recovery ledger and exposure barrier

The ledger is authoritative for **suppression and re-erasure**, not permission,
and is outside the database backup lineage and credentials/failure domain being
restored. A file inside the old backup or only the restored outbox is insufficient.
Use an externally durable append-only/immutable store with provider-enforced
retention and a protected latest-cut catalog. Reuse the existing storage SDK where
compatible; no new workflow engine. The selected production adapter/service/IAM
and operating authority are U04, not invented local configuration.

Minimum properties: idempotent append by record UUID+digest, complete ordered
cut enumeration, immutable object versions, independently authenticated latest cut
and continuity/count/checksum proof, anti-rollback access/control-plane evidence,
encrypted storage and least-privilege append/read roles. Use standard SDK checksum/
managed signing and verification facilities for immutable catalog/cut attestations;
no home-built crypto, key-in-backup trust or “a SHA hash proves freshness” claim.
Failure to validate a trusted current head, schema, digest, record count, lineage
or any referenced chunk fails closed. A valid old signed cut is still insufficient.

Proposed record fields are allowlisted: lineage/recovery epoch, record/request UUID,
subject UUID, protocol/policy/roster versions, operation, owner/resource kind/UUID
(≤100 per chunk), lifecycle generation, recordedAt/effectiveNotBefore, chunk/cut
receipt/digest. No email/handle, text, reasons, credential hashes, ciphertext, raw
storage credentials or arbitrary keys/URLs. Storage scope derives only from
versioned known asset-ID prefix conventions. Metadata remains restricted personal
data. Production ledger retention must cover the oldest extant PG/storage backup,
WAL/snapshot/replica/export plus permitted replay horizon and verified disposal;
recommended backup30d does not permit automatic ledger pruning at30d. Retain minimal
terminal UUID suppression thereafter until a separately reviewed lineage retirement.

To close the DB commit→export loss window, extend closure with a conservative
external **PREPARED suppression intent before final close commit**:

1. Short account transaction validates current actor/freshness/version/ownership
   and creates a prepared close-intent UUID, expected version, and export job.
   Nothing is erased; account still ACTIVE. Return no success yet.
2. Append that intent externally outside all business locks, idempotently; persist
   independently verified receipt in a short account transaction. If archive is
   down, return503 and leave retryable intent, no successful close/no erasure.
   This may be completed by existing external worker; browser can retry close
   while still authenticated, and receives503 until receipt exists.
3. Repeat **all** auth/version/ownership checks under the exclusive account guard.
   With same intent receipt, commit DELETION_PENDING+t0, revoke sessions, seed roster,
   audit and COMMITTED-export intent atomically; only then204. No archive network
   inside this transaction. An unacknowledged/lost204 committed request is also
   protected by its earlier external PREPARED record.
4. A PREPARED record with failed/rolled-back final close is conservative **restore
   quarantine**, not permission to erase an ACTIVE account. COMMITTED confirms
   request; an unmatched intent blocks exposure of that subject/recovery cut until
   reconciled. Proposed initial recovery blocks overall exposure to keep proof
   simple. Aborting a prepared-but-never-committed intent requires explicit current
   operator validation against the authoritative source and an append-only ABORTED
   record; cannot undo a committed close, erase history, or infer abort from absence
   in an old restored backup. No automatic cancellation timer.

Account owns close-intent state; identity orchestrates only its existing close
boundary, including community blocker and session revoke. Archive exporter is an
account-owned ExternalJobHandler using a technical platform adapter; do not add
identity→account→identity callback. Concurrent close retries coalesce by expected
account version and one pending intent, then the final guarded command wins once.
This knowingly adds availability dependence on the archive to a successful close;
an async-only design must explicitly admit an erasure-intent RPO window instead.

Preservation controls newer than an old backup matter too. Moderation exports
minimal root/version/terminal-eligibility/hold-deadline and one-way redaction states
to the same independent recovery boundary through its **own** exporter. A new or
renewed hold first validates current scope/version in a short transaction,
appends conservative PRESERVE intent outside locks, then repeats authority/root
checks and commits control/history/audit with that verified receipt. Thus the
intent precedes **both** the hold commit and acknowledgement, covering a lost
response/source failure as well. Export failure leaves an existing hold intact;
the requested change fails explicitly rather than claiming unrecorded preservation.
A redaction-first race rejects the final hold commit as ADR-016 requires; its
unmatched external intent requires authoritative reconciliation on recovery.
Release/terminal updates cannot shorten a conservatively known preservation term
unless an authoritative committed control record proves it. Recovery retains
unknown/unresolved roots and disables their cleanup until current controls are
proven; an old backup cannot make a newer hold disappear. Existing accepted roots
need a bounded baseline export before recoverability can be claimed. ADR-016 scope,
eligibility/expiry/renewal and mutation guards are unchanged; the new external
recovery contract is an explicit D08 implementation prerequisite.

Restore plan, to be implemented/drilled only in a later authorized package:

1. Establish isolated target with **all** HTTP ingress, normal worker/mail/provider/
   SSE exports and ordinary cleanup disabled; allow only reviewed recovery handlers.
   Source volumes/endpoints remain read-only. Generate a new recovery epoch so old
   job leases/executions cannot complete in target; credentials are target-only.
2. Restore compatible PG snapshot+WAL and compatible object versions+keys. Validate
   migrations/checksums/key references; an unavailable key fails recovery, not a
   claim of erased bytes. Runtime/migration roles remain separate.
3. Fetch and verify an independently current complete ledger cut, including
   post-backup PREPARED/COMMITTED/resource/preservation/completion records; pin cut
   and external source-write freeze evidence. During disaster recovery no old
   instance or exporter may accept new closes/holds outside that cut. If source
   write isolation/latest-cut freshness cannot be proven, keep target offline.
4. Import minimal denial/request state through owner recovery APIs, fence pre-cut
   jobs/sessions, retain conservative holds and reapply committed erasures in
   owner dependency order. Restored completed checkpoints are **not proof** for
   restored older bytes: allocate a recovery verification round and rerun affected
   owners including all objects from external resource chunks and orphan prefixes.
5. Run bounded source/reference/media binding reconciliation, source-state/inbox/
   search rebuild checks and negative privacy probes with worker replay stopped
   and then enabled in isolation. Disable original external SMTP/providers entirely;
   synthetic sink only. No derived data/backup body can serve as fallback.
6. After every committed-erasure subject passes private-data/object absence (or
   matches its explicitly still-pending, access-denied retention state), preserve
   all required controls, and resolve unmatched intents/integrity gaps, record
   cut+resource+key+schema+verification attestations. Recheck latest head under
   source-write isolation; target startup/readiness/ingress requires this epoch's
   release attestation. Source auth policy remains authoritative after opening.

A restored DELETION_PENDING subject with held payload may stay pending under
restricted scope; service may open only if that is the current independent control
state, never because an erased subject's old evidence happened to restore. A
COMPLETED-erasure manifest requires actual re-redaction/purge of restored bytes
before exposure. Ordinary health/readiness currently check DB, not this barrier;
the new startup gate must be implemented/tested before relying on it.

Backups are restricted copies pending expiry, not erased live data. Catalog every
copy, retention deadline and key dependency. Expired copies cannot be restored to
service. Restored production environments require production proof; local synthetic
drills cannot certify managed PITR, replica purge, IAM/KMS or region guarantees.

## Consequences, refinement boundaries and review exit

Explicit proposed refinements needing main acceptance: D03's account-erasure cause
for parent retention, last-recipient audience narrowing, terminal reaction-type/
media metadata shapes and handle reservation; D08's external pre-close intent,
moderation preservation export and exposure gate; D07's early account guards in
projection/rebuild paths. New migrations change only new shapes/guards, never edit
V0001–V0032. No new module dependency/view consumer is proposed.

ADR-016 is **not superseded**. Any demand for faster final DELETED despite held
payload, finite immutable-audit deletion, natural-language third-party scrubbing,
or retired keys while live ciphertext remains is a separate unresolved policy
change. An optional engineering-default acceptance does not resolve U01–U06 or
authorize Phase9B, public launch, real erasure or restore.

Main reviews D01–D10 together against the inventory and plan, records dispositions,
then explicitly activates the first proposed implementation package. Design remains
READY_FOR_REVIEW until that review; development cannot self-accept this ADR.
