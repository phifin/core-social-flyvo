# Scoped moderation operations — Phases7–8

Contracts: [moderation](../docs/moderation.md), [ADR-016](../docs/adr/016-moderation-private-payload-retention.md),
[OpenAPI](../docs/api/openapi.yaml), [verification/review](../docs/phases/phase-8.md).
Engineering capability only, not public-launch/legal compliance approval.

## Controlled migration/write-stop

V0030/V0031 require **all old HTTP/worker writers stopped**. Keep database/storage/
key backups under the separately approved operator policy, apply migrations with
migration authority, validate checksums/startup/readiness, then resume writes.
Local Compose's app contains both HTTP and worker; stop that service before
replacement. Preserve named volumes, `.local/storage-data` and existing keys.
Old/new binaries cannot safely write both schemas; no rolling mixed-version or
tested rollback protocol is claimed. On migration failure keep writers stopped
and correct forward through review. Never edit applied migrations, disable triggers,
restore old private columns casually, or run an old artifact against the new schema.

V0030 exclusively locks report/action, copies evidence bytes/key IDs and complete
reason/before/after JSON, proves counts/values before dropping private parent
columns, and aborts atomically on mismatch. No SQL access to external crypto keys.
Deferred constraints require one payload/tombstone per parent and a standalone root.
DELETE/reinsert, FK reassignment, encrypted substitution and restoration are denied.
Immutable action metadata/platform audit triggers are unchanged. DB-owner/superuser
authority is outside application guards; no privileged worker role was introduced.

## Authenticated operator/staff routes

All routes below `/api/v1` use current Bearer/session/account validation; unsafe
methods also require normal trusted Origin/CSRF. No token in URL/logs, role editor,
identity bypass or permanent scraper credential. Admin gateway/MFA stays a release gate.

| Capability | Route | Current policy/version |
|---|---|---|
| Queue | `communities/{community}/moderation/reports` or `admin/moderation/reports` | Exact scoped MODERATOR+ / ADMIN global cases only; status/assignedTo/unassigned; size20/default100/max, offset≤10000, no total |
| Assignment | report base `/{report}/assignment`, PUT | Case If-Match; eligible current staff assignee, null unassign; terminal denied; no permission grant |
| History | report base `/{report}/history`, GET | Scoped/audited oldest-first; bounded size/offset; unknown legacy versions stay null |
| Evidence | report base `/{report}`, GET | Scoped/audited; explicit redacted=true/null private fields, no source fallback |
| Case hold | report base `/{id}/hold`, PUT | Case If-Match; expiry `(DB now,now+365days]`, bounded reason code; both null release |
| Action detail | `communities/{community}/moderation/actions/{id}` or `admin/moderation/actions/{id}`, GET | Scoped/audited; returns root version/control and private detail or tombstone |
| Standalone hold | action base `/{id}/hold`, PUT | Root If-Match; linked actions409 CASE_HOLD_REQUIRED, use their case hold |
| Account status | `admin/moderation/actions`, POST | Current ADMIN, account If-Match; SUSPEND_ACCOUNT / REINSTATE_ACCOUNT; deny self/peer ADMIN |
| Account state | `admin/moderation/accounts/{id}`, GET | Audited current ADMIN, minimal ID/status/read-only role/version and ETag for If-Match; no credentials/epoch/directory |
| Maintenance | `admin/moderation/maintenance/ENCRYPT` or `/RETENTION`, GET/POST | Audited current ADMIN progress / new durable resume pass |
| Metrics | `admin/moderation/metrics`, GET | Audited current ADMIN-only existing Prometheus registry; no-store; disabled exporter503 |

Assignment neither grants authority nor releases holds. Staff revocation applies
at every read/action. Archived scopes retain existing staff read/hold authority,
not content/assignment write permission. Global routes never bypass community scope.
SourceVersion/case If-Match and atomic source/case/action/history/audit/outbox remain.
Account commands take complete sorted exclusive actor/target guards before any
moderation row; reinstatement needs fresh login and restores no deleted content,
old sessions, platform role or community ownership/state.

## Encryption progress/recovery

New detail is purpose-bound authenticated ciphertext. Legacy plaintext exists only
in the restricted owned detail table until MODERATION_ENCRYPT verifies a complete
decrypt-and-compare and removes it atomically. One root/≤100 rows per transaction;
crypto/key/verification/audit/continuation/fencing failure rolls back all effects.
DB guards prove identity/one-way shape, **not** ciphertext semantics with external
keys. Existing keys are locally loaded; no network key-service calls under locks.

The60s scheduler enqueues ENCRYPT/RETENTION; continuations use the ordinary1s worker
tick, ten-attempt bounded retry/DEAD handling and fenced ack. No unbounded loop or
new framework. Verify legacyPayloads converges to zero, not merely a completed job.
If stalled, inspect restricted kind/status/attempt/generation/error code and metrics,
correct key/config problems without logging private data, then POST ENCRYPT resume
as current ADMIN. It enqueues fresh bounded work; do not erase DEAD history or replay
authentication mail/proofs. Overlapping/repeated resumes are root-serialized/idempotent.
Oversized/unsupported legacy values stay retained and fail; never truncate/fake success.

## Engineering retention/holds

Cases qualify365 days **after terminal resolved_at**, excluding unresolved and holds
with expiresAt>DB now. Linked action detail shares that case root/date/hold; only
standalone actions use created_at+365days and a separate root. Candidate selection
uses DB statement time; lock-time eligibility/payload transitions recheck DB clock.
At expiry a hold stops blocking. Explicit current scoped staff/global ADMIN, root
If-Match and audit govern create/renew/release; no automatic renewal or legal-hold claim.

Order: complete account guards→community→case/standalone root→payload. Worker uses
root→payload only. History copies assignee FK only on ASSIGNED with that account
already guarded; system retention history acquires no late account FK. No network
or source-owner API after worker root lock. Both hold/cleanup winning races are tested.

NONE→STARTED is irreversible under the common root lock. STARTED rejects new/
renewed holds409 REDACTION_STARTED (stale versions412 first), even if bytes remain.
Passes redact≤100 payloads (evidence consumes one slot) and resume until no private
payload remains, then DONE. IDs/metadata/history/one-to-one tombstones persist.
Repeated cleanup cannot restore bytes; a late hold cannot promise preservation.
Failure/restart leaves old committed data or a bounded committed STARTED pass.
Correct the cause and POST RETENTION resume; never force DONE/clear STARTED/holds
or delete parents/payloads in SQL. No cascade account erasure or Phase9 workflow.

Keep the active external key while **any** retained ciphertext references it, including
held/unresolved evidence. Redaction removes that payload's key reference, not
its UUID tombstone. Key IDs are labels, not a multi-key decrypt selector: do not
swap the active key after mail expiry while case evidence/detail still needs it.
No multi-key retirement/rotation service is delivered. Backup/
restore policy and security audit metadata retention remain separate controlled
operator/public-launch gates; never mutate platform audit to pretend otherwise.

## Protected dashboard/alerts

[Static Grafana dashboard](dashboards/phase-7-8.json) is for an already approved
protected data source. Compose starts no dashboard/collector service. Configure
short-lived existing authentication/gateway externally; `/actuator/prometheus`,
env and internals remain denied. Anonymous401, ordinary user403, current ADMIN
and mandatory audit before metrics; no public scrape exception.

Progress gauges start NaN/unknown, then committed snapshots; use
moderation.progress.observed.seconds to detect stale data. moderation.payloads counts
committed rows only (afterCommit), so rollback/fencing cannot inflate it. Handler
failures plus platform queue/retry/DEAD/failures cover recovery. Labels are fixed
operation/root/status/kind/reason/mode, never resource IDs/query text.

Investigate DEAD/retry growth, stale/nonzero legacy backlog and stuck partial roots
before resuming. Correlate SSE active/pending/closed reasons with REST repair and
search lag; >30s is an investigation signal on the recorded workload, not an SLA.
Do not raise caps/pools/timeouts or remove holds to hide resource failures. Prior
feed p95<500ms remains unmet, not newly promised capacity.
