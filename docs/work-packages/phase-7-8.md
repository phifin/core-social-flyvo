# Phases 7–8 — notifications, search and moderation operations

Status: **ACCEPTED by main on 2026-10-17**, executable `d2a8e3c`, P78-R1 resolved.
See Phase7/8 records and the review mailbox for evidence and retained limitations.
Originally activated2026-10-14 after Phases5–6 acceptance `6dc06d6`, code `32c1b1e`.
The assignment below is preserved as the historical implementation scope.

New development chat: `Develop Phases 7–8 — Notifications, Search & Moderation Ops`.
The user opens it manually. One writer, no agents, no push. Main reviews the whole
package; development may pass internal gates but cannot accept either phase.

## Baseline and plan before code

Read AGENTS, mailbox, thread/git workflows, implementation handoff, architecture,
roadmap Phases 7/8, security, domain/database, events/jobs, search/caching,
moderation, API/testing/operations docs and ADRs 002–005, 009–012, 014–015.
Inspect actual APIs and source event versions, not just the blueprint. Preserve
V0001–V0026 and all accepted privacy/worker/feed regressions. Allocate migrations
from actual HEAD (currently next V0027), one globally ordered sequence.

Set IN_PROGRESS and record one concrete plan: endpoint/DTO matrix, tables/indexes,
producer events and source-version mapping, owner API additions, lock order per
command/consumer/cleanup, replay/retention horizons, SSE limits and test fixtures.
List exact notification routes/recipients for each event; never infer recipient
permission from an event ID. No generic policy engine or parallel worker framework.

Execute A → B → C → D, with verified slice commits and full internal gates.
Do not hand off after every slice. Stop only for a concrete contract/resource
blocker with evidence and a proposed decision, or after the complete package.

## A. Durable envelope and semantic inbox (Phase 7)

Extend the existing platform EventConsumer delivery contract to expose immutable
envelope metadata already owned by the outbox: event ID/type/schema version,
aggregate type/ID/version, event sequence, occurredAt and available correlation
metadata plus minimal payload. Current apply(eventId,type,payload) loses source
versions. Keep receipt + DB effects + fenced acknowledgement atomic; preserve
leases, ten-attempt retry/DEAD handling and real worker tests. Do not read platform
tables from notification/search or invent a second delivery mechanism. Historical
events are not rewritten to fabricate missing metadata.

Notification owns inbox, contributions, source_state and preferences. Content,
graph and community own bounded recipient/current-target queries; profile owns
authorized attribution. Export only needed immutable records through owner APIs,
not repositories, internal objects or new foreign SQL consumers. Internal source
inspection APIs are not HTTP lookup bypasses. Enumerate recipients in bounded
batches; mentions never expand source audience. No social email/push channel.

Implement all documented kinds: friend request/acceptance, community invitation,
post comment, reply, mention and content reaction. Suppress self; per-event
recipient precedence MENTION > REPLY > COMMENT. Preserve stable reaction identity
and monotonic version across deletion/reactivation. Serialize source_state across
UTC-hour groups: type change updates its current contribution without new unread
activity; deletion deactivates; reactivation creates activity in the current hour
and deactivates the prior group. Reject stale/reordered events atomically.

Recipient REST inbox/preferences/read/unread use current account, target, actor,
blocks/mutes and preferences. At most three eligible actor summaries, filtered
counts, no stored rendered message or stale body fallback. Coherent source policy
must accompany returned text, as in accepted content hydration. ReadVersion only
advances through observed activityVersion; concurrent activity remains unread.
Bulk read uses a server snapshot and observed versions, not a blind future update.
Bound scans/memory and keyset cursors; document refresh semantics when activity
reorders groups. Do not return an unfiltered or silently truncated unread count.

Test real worker duplicate/reordered delivery and receipt rollback, create-after-
delete, cross-hour type/delete/reactivate, concurrent source updates, multi-route
dedup, read/new-activity and bulk-read races. Cover preference changes, self,
one/all actors blocked/muted/inactive, removed/private targets, invitation/request
expiry and recipient IDOR. Source state must survive inbox's 90-day cleanup through
the permitted replay horizon; DEAD events cannot resurrect purged activity.

## B. Authenticated SSE and full Phase 7 gate

Use ADR-015's narrow identity API seam for verified actor/expiry and current
session checks; never decode unverified exp, store a token, put it in a URL or
use a cached positive session result. Reuse the existing validation stack.
SSE is Bearer fetch streaming, only invalidation hints, REST authoritative.
Close at actual token expiry (no extension by JWT clock skew), on failed current
session/account checks, and at disconnect/backpressure. Heartbeat revocation target
<=15 seconds includes scheduling/check time; bound active streams per user and
globally, queues/executors and polling. No open DB transaction/connection per
stream, and no per-client unbounded worker/thread/queue. No Redis/broker.

Single-instance delivery may poll a durable recipient watermark. In-memory wakeups
only accelerate it. Reconnect always refreshes REST; Last-Event-ID is best effort,
not a durable replay promise. Use current eligibility and send no actor/content
detail, private snippets or hidden counts in frames. Optional transport outage
cannot lose inbox state or break ordinary REST authentication.

Real HTTP tests must cover missing/invalid/expired JWT, wrong signature/issuer/
audience, logout/logout-all/session revoke/account suspension during a stream,
DB outage fail-closed, expiry timer, cap/rate denial, disconnect cleanup, slow
reader/backpressure and REST repair after lost hints. Test no token exposure and
no retained connection. Run targeted + formatting + complete verify, OpenAPI and
architecture gates. Record Phase 7 evidence and verified code before C; this
internal gate authorizes continuing this package, not phase self-acceptance.

## C. Source-authorized PostgreSQL search (Phase 8)

Search owns document/version tombstones and its justified SearchIndex port.
Use simple tsvector/GIN for users, communities, posts; exact/prefix handles and
normalized names. Query length 2..200, bounded terms, default20/max50, parameterized
builders, existing timeout and search rate limits. No unmeasured pg_trgm/unaccent,
external engine or unrestricted wildcard scan. Add Vietnamese/accent fixtures
and document actual relevance limitations rather than claiming linguistic support.

Return candidate IDs/rank internally, then batch owner authorization/current
hydration before any name/snippet/highlight. No exact hidden totals, projection
body fallback or authorization cache. Use viewer/query/filter-bound opaque
rank/id/cutoff cursors, bounded refill, last examined progress; explain mutable
projection weak consistency. Measure representative EXPLAIN/query count.

Register live consumers before checkpointed current-source export/backfill.
Owner APIs return bounded versioned snapshots/tombstones; upsert only newer state.
Concurrent live writes/deletes must beat older backfill, including source absence,
account status and legacy producers. Tombstones retain ordering through replay.
Provide bounded resumable reconciliation/rebuild and audited operator recovery;
do not expose exports through ordinary public HTTP or copy credentials/evidence.

Tests: stopped-worker privacy/deletion/block/member/account changes, coherent
authorization/text races, missing source, stale create after delete, live writes
during restartable rebuild, cursor tamper/ties/rejection, injection/expensive query
limits, multilingual relevance and no N+1. Measure projection lag against initial
<30s target on recorded workload/hardware; failures remain evidence, not skipped
gates. Reuse platform delivery/recovery from A.

## D. Scoped moderation operations and final package gate (Phase 8)

Extend existing reports/actions, not a parallel case system: assignment/filtering,
versioned resolution history, scoped attributable reads/evidence access and bounded
retention jobs. Assignees must be currently eligible staff in the case scope;
assignment never grants authority. Preserve global versus community separation,
immutable target/scope, current staff revocation, sourceVersion/If-Match and
atomic source + case + action + audit + outbox. Retain ADR-014's narrow profile proof.

Expose separate platform ADMIN account suspension operations through account
owner APIs (suspend already exists); add guarded/versioned reinstatement as needed
by roadmap tests. Deny self/peer ADMIN privilege abuse; do not mutate platform
roles. Acquire the full sorted account guard set in final lock modes before
community/source/case locks; avoid upgrading shared locks inside owner suspend.
Suspension must immediately fail current auth/search/notification/SSE checks.
Reinstatement cannot restore author-deleted content, revive revoked sessions or
silently transfer/archive an owned community. Preserve owner handling boundaries.

Use [ADR-016](../adr/016-moderation-private-payload-retention.md), accepted by main
on 2026-10-16 to resolve P78-D1: owned redactable private payloads, immutable
metadata/audit and explicit scoped expiring holds. Implement its full conditions
and tests; this decision does not accept implementation or waive any package gate.
Retain documented 365-day engineering defaults, 90-day notification windows
and explicit replay horizons; no legal-compliance claim. Specify start timestamp,
terminal/unresolved eligibility, evidence holds, key lifetime and redacted audit
tombstones before cleanup. Do not silently delete unresolved/held evidence or
weaken append-only audit triggers. If a new legal/hold policy is needed, stop for
a concrete decision. No blanket cascade deletion or completing Phase 9 erasure.

Tests: cross-scope assignment/IDOR/revoked staff, conflicting assignment/resolution,
global ADMIN versus community hierarchy, suspend/reinstate races with login/write,
audit/outbox failure rollback, retained/redacted/held evidence and retryable bounded
cleanup. Operations adds low-cardinality lag/retry/DEAD/SSE/search/case metrics,
simple dashboard/runbook artifacts and replay/rebuild procedures; do not expose
unauthenticated metrics or add dashboard services by default.

Run full targeted security/race/worker suites, formatting and ./mvnw verify,
fresh/upgrade migrations with old checksums/data preserved, OpenAPI/architecture,
app/media Compose, pinned redacted full-history secret scan and positive control.
Preserve Phases 1–6, including feed transaction/source-work regressions. If changes
touch feed owners/query/transactions or pool/worker contention, rerun the committed
pool10/50 feed benchmark and report all outcomes; never weaken its all-success gate.
Record separate phase-7/phase-8 evidence, lag/resource measurements, denied cases,
schema/API/subsystem/operations/README updates and material launch limitations.
Commit verified slices, then separate real-hash mailbox READY_FOR_REVIEW to main.

## Non-goals

No Phase 9 release/scale, deployment, production credentials/provider activation,
multi-instance public rollout, Redis, broker, WebSocket/chat, mobile/push/social
email, feed fan-out, general identity/admin bypass, rewritten migrations or push.
Main alone resolves findings, accepts phases and performs reviewed remote delivery.
