# Phases 7–8 development plan

Baseline: clean master `14c983b`, executable baseline `32c1b1e`, V0001–V0026.
Owner: development. This records implementation choices, not phase acceptance.

## A — durable envelope and semantic inbox

First preserve the worker transaction and expose the existing outbox envelope as
an immutable platform API record (including nullable causation, correlation,
schema/aggregate versions and sequence). Migrate the actual test consumers too;
verify real duplicate, rollback, lease expiry/reclaim and ten-attempt cases before
committing this prerequisite. No historical payload/metadata rewrite.

V0027 will own notification groups, contributions, source_state, per-kind
preferences and recipient watermark. Scalar account FKs, unique semantic source
keys, readVersion ≤ activityVersion, keyset/unread/retention indexes. All mutations
take recipient serialization before source_state → groups → contributions; the
watermark row is already held. Read commands use account shared guard → recipient
serialization → group rows ordered.
Preference edits share the recipient guard. No foreign SQL consumer is added.

Owner APIs inspect bounded current notification sources and authorize batches:
content supplies post mentions, comment post/root/addressed authors and reaction
targets; graph supplies request participants/current request or friendship;
community supplies invitation/inviter/current admission eligibility. Only immutable
IDs/version/status facts cross modules. Profiles provide attribution after source
authorization. Existing comments have no explicit mention relation: mentions are
the existing validated post mentions; no inferred handles or audience expansion.

| Event | Recipient route | Ordering/source |
|---|---|---|
| FriendRequestCreated | current addressee | retained graph pair envelope version; request UUID |
| FriendshipEstablished | original requester, actor is accepting addressee | same pair version/request |
| CommunityInvitationCreated | current invitee, actor is inviter | community envelope version/invitation UUID |
| PostCreated | each current validated mentioned user, max20 | post UUID/version |
| CommentCreated | post author COMMENT; root/addressed author REPLY; dedup precedence | comment UUID/version |
| ReactionChanged | current target author | stable reaction UUID/version, including inactive tombstones |
| Source resolution/update/delete facts | invalidate/deactivate current contributions without new activity | owning monotonic sequence |

Suppress self, disabled preferences, inactive accounts, block/mute and denied
current targets on creation/read. Dedup planner precedence MENTION > REPLY >
COMMENT. Reactions group by target/recipient/UTC hour; type changes keep the group
and unread version, deletion deactivates, reactivation moves to the current hour.
Source_state ordering outlives group expiry; new activity uses monotonic processing
time. Non-reaction keys include event ID. No rendered message/body fallback.

REST: GET /notifications (20 default/100 max, encrypted viewer/size/cutoff cursor),
GET /notifications/unread-count, PUT /notifications/{id}/read with observed
activityVersion, POST /notifications/read with server snapshot observed versions,
GET/PATCH /me/notification-preferences with typed kinds. Filter active actors in
bounded batches, return at most three authorized summaries. Full count scans use
bounded batches/memory and explicit resource failure if their documented ceiling
is exceeded, never an approximate or silently truncated badge. Bulk read snapshots
versions before mutation; new activity remains unread. Notification groups expire
90 days after activity; a 30-day permitted replay horizon rejects older events
(including old DEAD replay) before inbox changes. Retain source_state at least
120 days and never discard an active source's ordering state during group cleanup.

Tests: real registered worker duplicates/reorder/effects-receipt-fence rollback,
delete-before-create, cross-hour type/delete/reactivate, concurrent source updates,
route dedup, read/bulk-read races, actor filters, preference changes, expiry/target
privacy/removal, recipient IDOR, cursors, bounded cleanup and old DEAD replay.

## B — authenticated SSE and Phase 7 gate

ADR-015: identity API exposes trusted actor/actual exp from the existing JWT
validator and current session checks. Align the narrow ArchUnit dependency rule.
Bearer fetch GET /notifications/stream; no raw token retained, no URL credentials.
Only changed invalidation/heartbeat frames; REST refresh on reconnect. Poll durable
recipient watermark, recheck current session/account at ≤10-second heartbeat,
schedule actual exp closure. Bound streams (2/user, 100/global), shared scheduler,
writer executor/queue and slow-client timeout; release on every terminal path.
No connection or transaction spans a stream. No extra broker/service.

Real Tomcat HTTP tests cover JWT gates, actual exp/skew, logout/all/session revoke,
suspension, DB outage, stream caps/rate, disconnect, backpressure, no credentials
in frames and REST repair. Record measured check/closure/resource bounds. Run
targeted suites, Spotless and full verify, migration/OpenAPI/architecture gates;
record Phase 7 evidence and verified commits before starting C.

## C — source-authorized PostgreSQL search

V0028 owns versioned documents/tombstones, GIN simple tsvector, normalized name/
handle prefix indexes and checkpointed rebuild state. SearchIndex is a real owned
port, PostgreSQL adapter. Live consumer registration precedes export/backfill.
Owner APIs supply ≤100 versioned source snapshots and tombstones, including missing
source/account status. Upsert compares owner source versions; account lifecycle
is separate ordering from profile/post, never substituted for those versions.
Checkpointed jobs reconcile current sources and indexed missing IDs; live newer
state wins older export. Audited operator recovery reuses durable jobs.

GET /search/users, /search/communities, /search/posts: q 2..200, bounded terms,
20 default/50 max, parameterized simple tsquery and escaped prefix matching,
existing timeout and 30/min limiter. Encrypted viewer/query/type/size/rank/ID/cutoff
cursor with last-examined bounded refill. Batch source policy/current hydration
before text; no projection snippet/name/count fallback or exact hidden totals.
Private community discovery retains its existing card contract. Vietnamese/
accent fixtures document actual simple-token behavior, no unaccent/trgm claim.

Tests: stopped-worker privacy/deletion/block/membership/account, coherent current
text races, missing source/stale create, restart/live-write rebuild ordering,
cursor ties/tamper/scope, parser/injection/expensive inputs and query counts.
Measure EXPLAIN and initial <30s projection lag with workload/hardware recorded.

## D — scoped moderation operations and package gate

V0029 extends existing reports with assignment, terminal timestamp, bounded
evidence hold/redaction markers and append-only versioned resolution history;
index scope/status/assignee/retention. Current eligible assignee proof uses account
and community APIs, not the narrow ADR-014 profile proof. Assignment is no grant.
Separate global ADMIN and community staff routes for filtering, assignment/history,
evidence and versioned resolution. Scope/target remain immutable.

Separate /admin/moderation/actions for versioned suspend/reinstate through account
owner commands. Pre-read immutable IDs; sorted complete account set with target
exclusive final mode → community/source → case; no shared lock upgrade. Deny
self/peer ADMIN, retain ownership boundary, revoke existing sessions durably via
the account/identity lifecycle contract without a reverse dependency. Reinstatement
restores neither deleted content nor previously revoked sessions or community state.
Action/source/case/history/audit/outbox remain one transaction.

Retention: engineering 365 days after case terminal resolution, unresolved/held
cases excluded, evidence keys retained until all referenced ciphertext is redacted.
Bounded idempotent cleanup redacts private evidence/action detail, retaining IDs,
versions and attributable audit tombstones through an explicit controlled DB
retention mechanism; append-only protection is preserved for ordinary writes.
No legal policy, cascade erasure or Phase 9 workflow.

Tests: scope/IDOR/revoked staff, assignment/resolution conflicts, hierarchy,
suspend/reinstate vs login/write/SSE, audit/outbox rollback, held/unresolved/terminal
retention and bounded retry. Add low-cardinality operational metrics, simple
dashboard/runbooks, replay/rebuild/retention instructions without metric exposure.

Final gates: targeted security/race/worker checks; Spotless + complete verify;
fresh/upgrade schema preserving V0001–V0026 data/checksums; OpenAPI/architecture;
app/media Compose; pinned redacted full-history scan + positive control. Added
owner/worker/resource paths require rerunning the committed pool10/50 feed
benchmark, recording all outcomes with its all-success gate unchanged. Update
README/schema/domain/security/subsystems/operations and separate Phase 7/8 evidence.
Commit owned verified slices, then real-hash mailbox READY_FOR_REVIEW in a separate
documentation commit. No push, agents, phase acceptance or Phase 9 implementation.

## Actual slice evidence

- A prerequisite commit `beebb0a`: immutable stored envelope and lazy consumer
  discovery; Spotless + targeted verify, 10 fast/architecture +33 PostgreSQL/auth
  tests, zero failures/errors/skips. No schema/producer rewrite.
- A inbox precommit: targeted verify on 2026-10-14, 11 fast/OpenAPI/architecture
  +31 PostgreSQL tests (NotificationsIT11, EventEnvelopeIT3, FoundationIT17), all
  passing with no skips. Named regressions cover reactivation-before-delete,
  receipt/effect/fence rollback, cross-hour lifecycle, current actor filtering,
  recipient IDOR and bulk-read/new-activity interleaving. Full Phase 7 gate has
  not run and B/C/D have not started. V0027 preserves all applied migrations;
  older FoundationIT checksum/data/constraint assertions remain intact, expected
  latest migration counts advance to27.
- Refinement: recipient serialization comes before all notification source/group
  writes, avoiding a reverse watermark/group lock order with read commands.
  Internal background proofs accept recipient UUID explicitly; no fabricated
  session or HTTP/system bypass is introduced.
