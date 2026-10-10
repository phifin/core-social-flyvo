# Events, jobs and notification delivery

Domain events describe completed facts. They are not RPC requests, audit logs, database row dumps or an event-sourced persistence model. Aggregate methods may collect local events; application commits aggregate changes and required durable outbox events/jobs in one PostgreSQL transaction. A local after-commit signal only reduces polling latency. No required behavior depends on `@Async` alone.

## Event envelope and catalog

Envelope: eventId UUID, type, schemaVersion, aggregateType/Id/Version, zero-based eventSequence within that aggregate transition, occurredAt Instant, correlationId, causationId, and minimal semantic payload. Payload carries IDs and meaningful transition data, not credentials, presigned URLs, whole JPA entities or arbitrary private content. Tolerate unknown optional fields; breaking payload changes introduce a new version with consumer compatibility/backfill. Actor IDs are personal data and follow retention/access controls.

| Event | Publisher | Durable consumers / consequences |
|---|---|---|
| `UserRegistered` | account (registration orchestration) | Welcome job if configured; later search/profile indexing. Verification mail is an earlier job, not this event |
| `AccountStatusChanged`, `AccountDeletionRequested` | account | Projection suppression/erasure jobs, security operations; live reads already deny immediately |
| `ProfileUpdated` | profile | Search refresh, cached public summary eviction |
| `CommunityCreated/Updated`, `MemberJoinedCommunity`, `MembershipChanged` | community | Search/community projection, membership notification, future feed backfill |
| `FriendRequestCreated`, `FriendshipEstablished`, `FollowChanged`, `BlockChanged`, `MuteChanged` | graph | Eligible social notifications; feed/cache cleanup. Graph invariant changes remain synchronous |
| `PostCreated/Updated/AudienceChanged/Deleted/Removed` | content | Search, mentions notifications, future feed projection; audience/removal takes effect at source immediately |
| `CommentCreated/Updated/Deleted` | content | Eligible post-author/root-author notification; suppress stale targets |
| `ReactionChanged` | content | Notification contribution update, future engagement projections; includes previous/new type and monotonic target reaction version |
| `MediaUploaded`, `MediaReady`, `MediaRejected` | media | Durable processing is directly scheduled with upload transition; optional UI invalidation |
| `ReportSubmitted`, `ModerationActionApplied` | moderation | Staff inbox/operations, projections; owner state and audit already committed |

Graph events use the stable `graph.pair_revision` ID and increment its sequence for every effective pair transition, even when follow/friend/block rows are deleted. Reactions retain their row with `active=false` and monotonically incrementing version; PUT reactivates the same ID. Uniqueness includes inactive rows. API DELETE still means absent reaction. Do not recreate either event source with version 0 and let an old deletion override a newer state. Tombstones/sequences outlive the permitted event replay horizon.

## Minimal worker implementation

Phase 3 adds graph pair-revision events and FRIEND_REQUEST_EXPIRY jobs through the
existing worker. Event aggregate ID/version belongs to a retained canonical pair;
block's after-state captures removal of both follows/friendship/pending request.
No-op commands emit nothing, and expiry duplicate/reclaimed work is harmless.
Payloads are specified in [graph](graph.md); no future projection/notification consumer
is enabled. Block/mute facts are internal, never user-facing notifications.
Source/event/job writes share the originating transaction.

Use Spring scheduling to poll PostgreSQL outbox deliveries and jobs; it schedules, PostgreSQL provides durability. One application can run worker + HTTP initially; configuration allows worker-only/API-only processes later. No Quartz/JobRunr dependency initially. Adopt a job framework only if actual workflow/dashboard/scheduling needs outweigh the existing small dispatcher; do not grow a home-built workflow language.

Job/delivery states are PENDING -> RUNNING -> COMPLETED, with RUNNING -> RETRY -> RUNNING for transient failures or RUNNING -> DEAD for terminal/exhausted work. Each claim increments lease_generation; acknowledgement requires matching owner/generation and an unexpired lease. Expired RUNNING is reclaimable. Scheduled recurring jobs use a deterministic kind/scheduled-time dedup key. Prune completed records after retention; never silently delete unresolved DEAD deliveries just because their creation date is old. A processed outbox event is eligible for pruning only when every delivery is terminal under the documented operator/retention policy.

1. Short transaction claims due records using `FOR UPDATE SKIP LOCKED`, records lease owner/deadline and increments attempts; commit. Do not hold row locks during SMTP/storage/provider calls.
2. Handler executes using bounded pools per workload (mail, media, projection). A DB-only projection uses a transaction containing receipt insert + projection updates; duplicate receipt makes it a no-op. Receipt lives in the same database transaction as effects.
3. Mark delivery/job complete only if the lease/fencing generation is current. Crash between effect and acknowledgment may cause redelivery; effects must be idempotent. External mail can duplicate after ambiguous SMTP acceptance; use deterministic Message-ID/provider idempotency if supported and admit at-least-once delivery.
4. Retry transient failures with exponential backoff + jitter (initial 5 seconds, cap 1 hour, 10 attempts), respect Retry-After, classify terminal validation separately. Exhaustion moves to DEAD and alerts. Operator replay preserves event ID for projections or uses an explicit repair command, with audit.
5. Reclaim expired leases. Track oldest event age, queue depth, retries, DEAD counts and handler duration. Test shutdown mid-job and two concurrent worker instances.

Ordering is not globally guaranteed. Source versions let consumers ignore stale updates; rebuild current source state through module APIs where possible. For grouped notifications, per-target event order and row versions handle update/delete races. If strict order later becomes necessary, partition by aggregate and serialize that consumer deliberately; never assume polling order alone guarantees completion order. Time-sensitive notification commands recheck the current source rather than merely trusting event age.

Source transaction inserts delivery rows for registered consumers. A new consumer uses a versioned registration plus checkpointed backfill, then catches up with concurrent events. Backfill watermark cannot rely solely on event timestamps to solve a commit race: begin consuming live events first, record source versions, backfill, then compare/repair. Receipts are retained at least as long as replayable events; older replay requires an explicit rebuild mode.

Email jobs containing verification/reset links require recoverable secrets for delivery, while authentication tables retain only hashes. Store the outbound secret in a short-lived **encrypted mail payload** using a key external to PostgreSQL; generic job JSON holds only payload ID and template kind. Delete ciphertext after successful send or token expiry. Never log rendered email/token or keep it in the general outbox. This is a narrow deliberate exception to “only hashed authentication secrets at rest.”

## Notification semantics (Phase 7)

Phase 2 schedules each seven-day invitation expiry as a deduplicated DB-only job.
The existing worker handles at most one registered DB job in addition to mail and
delivery per tick. Mail claims only MAIL; registered handlers claim their own kinds.
Claim commits separately; DB handler and fenced acknowledgement share a transaction,
so handler failure/lease loss rolls back source effects. Duplicate/reclaimed expiry
is idempotent, bounded at ten attempts and retains DEAD state. Expiry checks remain
live even without cleanup. Optional resource idempotency stores only logical UUIDs,
not response bodies. See [community operations](../ops/community.md).

CommunityCreated/Updated, MemberJoinedCommunity, MembershipChanged and admission
created/resolved facts use publisher-owned CommunityTransition v1 payloads:
communityId/userId/sourceId/status/role/suspendedUntil/membershipVersion. The envelope
aggregateType is community, aggregateId is communityId, aggregateVersion is the
post-transition community version (monotonic across user transitions), schemaVersion
is 1 and eventSequence is 0. Status comes from the named committed source, never
from the initiating actor's membership:

| Event | sourceId | status |
|---|---|---|
| CommunityCreated / CommunityUpdated | Community UUID | Community ACTIVE / ARCHIVED |
| MemberJoinedCommunity / MembershipChanged | Community UUID; userId identifies membership | Membership ACTIVE / LEFT / REMOVED / BANNED |
| CommunityInvitationCreated / CommunityInvitationResolved | Invitation UUID | Invitation PENDING / ACCEPTED / DECLINED / REVOKED (EXPIRED remains cleanup-only) |
| CommunityJoinRequested / CommunityJoinRequestResolved | Request UUID | Request PENDING / APPROVED / REJECTED / CANCELLED |

userId is the initiating user for community lifecycle, the subject member for
membership/ownership transitions and invitee/requester for admissions. Role,
suspendedUntil and membershipVersion describe that user's post-transition membership
when present (otherwise null); membershipVersion is not the envelope community
version. Archive thus publishes ARCHIVED even with an ACTIVE owner membership.
Archive retry at the current version publishes nothing. Source mutation, event and
mandatory audit remain one transaction; rollback retains neither mutation nor event.
This is a v1 producer correctness fix, with no payload-shape change or consumer
implementation. Existing historical outbox payloads are not rewritten; any future
projection rollout must backfill/reconcile current source as already required above.

ModerationActionApplied contains actionId/communityId/userId/action. Names, private request messages and
sanction reasons are excluded. There are no future projection/notification consumers
enabled; jobs do not require outbox delivery rows. Cleanup expiry itself is not a
new social activity event and authorization cannot rely on delayed consumers.

Phase 1 appends `UserRegistered`, `AccountStatusChanged`,
`AccountDeletionRequested` and `ProfileUpdated` to the outbox. There are no
production projection consumers yet: delivery rows are created only for
code-registered consumers. Integration tests register a real DB-effect consumer
to exercise receipts, duplicate delivery and rollback. Mail is a direct job;
its general JSON contains only payload ID/template, not email/token.

The initial scheduler processes one mail job then one DB delivery per tick,
bounding workload concurrency to one per process; multiple workers use
SKIP LOCKED and a 60-second fenced lease. Mail retries begin at five seconds
with jitter, cap at one hour and stop at ten attempts or payload expiry.
SMTP ambiguity can duplicate delivery with the same deterministic Message-ID.
Ciphertext is removed after completion/expiry (and on terminal mail failure).
DEAD mail requires a new authentication request/proof, not resurrection of an
old link. See [worker recovery](../ops/identity.md). Completed jobs/events are
pruned after 30 days only when deliveries are completed; unresolved DEAD state
is retained. Security audit retention needs a controlled owner-role process,
not an application trigger bypass.

Persist recipient inbox independently of realtime transport. Notification kinds include FRIEND_REQUEST, FRIENDSHIP_ACCEPTED, COMMUNITY_INVITATION, COMMENT_ON_POST, REPLY_TO_COMMENT, MENTION and REACTION_ON_CONTENT. Targets reference post/comment/request/community IDs, with template kind and typed data; render/localize on the client from authorized current summaries. Drop self-notification. Coalesce a single event that targets the same recipient via mention/comment paths using precedence MENTION > REPLY > COMMENT. Preferences suppress optional social events, never mandatory security emails.

Initial grouping applies to reactions on the same target per recipient in a fixed UTC one-hour bucket: `(recipient,kind,targetType,targetId,bucketStart)`, encoded as a deterministic grouping_key. Other notification kinds use `kind:eventId` keys so unrelated comments are not merged. Distinct actor contributions support “Alice, Bob and 8 others”; show at most three current eligible actor summaries. Repeated same-type reactions add no activity; changing type updates contribution without counting the actor twice; removal deactivates it. Event receipt + source version prevent retries/out-of-order changes from inflating counts.

The persisted grouping window does not split the actor's semantic state blindly: lock `notification.source_state` for the recipient/kind/source/actor, reject events no newer than its stored source version, and locate its current contribution before changing a group. A type update stays in the current group without new unread activity. A deletion deactivates its contribution; reactivation creates activity in the current hour and deactivates any prior contribution. Update source_state, contribution/group and receipt atomically. Thus an out-of-order create arriving after a delete cannot start a new group. Aggregated counts describe currently active eligible contributions in that group. Old windows expire after 90 days; source state persists through the permitted replay horizon. `last_activity_at` is monotonic processing time, not an older event timestamp that can reorder an inbox backward.

Each row has monotonic activityVersion and readVersion. Mark-read submits the activityVersion the client observed and advances readVersion up to that version, never beyond current activityVersion. New activity racing the request remains unread. Bulk mark-read takes a server snapshot/cutoff and per-row observed versions, never blindly sets all future rows read. Unread badge is computed from the currently eligible recipient inbox; suppressed/deleted/blocked items do not leak via unread totals. Keep notification counts source-backed until a safe projection is justified.

Recheck recipient status, preferences, blocks/mutes, target access and actor eligibility on creation **and read/stream**. For read-time aggregation, counts and names derive from filtered active contributions, not a stale actor_count column. Comment/post removal makes associated notifications invisible; delayed cleanup is fine because authorization is live.

SSE sends `{type:"notifications.changed",eventId:...}` or a notification ID/version hint, not content snippets/actor details. REST supplies authoritative state. Authenticate connection via Bearer fetch streaming, close at token expiry, cap connections/user, send heartbeats, handle disconnect/backpressure and poll current session/account status at heartbeat. Logout revocation closes at the next check (target <=15 seconds); frames contain only invalidations during that window. Reconnect triggers REST refresh; Last-Event-ID is a best-effort hint, not guaranteed durable replay. WebSockets are unnecessary for one-way inbox updates.

For multiple HTTP instances, either local listeners poll a recipient change watermark or introduce Redis pub/sub invalidation (loss is repaired by REST refresh). Redis pub/sub is not the durable inbox/outbox. Introduce a broker only when independent consumers need transport at measured throughput; publisher confirms, consumer receipts, retries and outbox durability remain required.
