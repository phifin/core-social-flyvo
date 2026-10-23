# Posts, interactions and current privacy

Implemented routes and DTOs: [OpenAPI](api/openapi.yaml). Completion/review state:
[Phase 4](phases/phase-4.md), [Phase 5](phases/phase-5.md), [mailbox](agent-handoff.md).
Posts, comments/reactions and [reports](moderation.md) are implemented; the complete
Phase 5 gate has passed. Phase 6 home/community/timeline orchestration is implemented
through source-owner APIs; exact verification is in [Phase 6](phases/phase-6.md).

## Commands and invariants

`ContentCommands` owns publish/edit/audience/delete. Actor comes from validated
authentication, never JSON. A STANDARD post has plain text (10,000 Unicode code
points), one optional unfetched HTTP(S) link, max twenty validated UTF-16 mentions,
ten normalized hashtags and ten owned READY POST images. Text/link/media must be
nonempty; no server draft, HTML, arbitrary storage key or external image URL.
CUSTOM has 1–100 fixed active/unblocked other-user IDs; mention IDs do not expand
audience. Community posts exclusively use COMMUNITY. Validate all image ownership,
purpose/state and never-bound status before binding any; post/relations/revision/
binding/event/audit/logical idempotency commit as one transaction.

Guard order is complete sorted account set (actor, recipients/mentions/author),
shared community where scoped, sorted shared graph pair keys, post, media quota,
sorted assets. Graph guards permit at most 121 participants for author +100 CUSTOM
recipients +20 mentions; fact queries stay capped at 100 and are batched. Reload and
revalidate after guards. Membership and bilateral block mutations serialize against
publication in both orders. Storage network effects never occur under these locks.

Create accepts an optional 24-hour Idempotency-Key storing only the logical post
UUID, compares canonical request and reauthorizes replay. Edit/audience/delete use
strong version If-Match (428 missing, 412 stale). Body/attachment edits and explicit
audience edits add immutable snapshots; publishedAt is stable and editedAt advances.
Owner-only CUSTOM recipientIds are empty for other readers. Every audience change
is audited, including widening. Deletion denies reads/URLs immediately; after private
community departure the author can issue blind deletion, not enter the old scope.
Archived communities remain readable but deny all writes, including deletion.

`PostModeration` is a narrow transactional owner API. Moderation orchestrates current
MODERATOR+ authority of the exact active community, expected post version,
remove/restore, append-only typed action, audit and minimal outbox in one transaction.
No body edit, author impersonation or cross-community operation. Clearing removal
cannot clear author deletion; duplicate invalid state is 409. Reasons/before/after
are private, not outbox data. There is no report/evidence workflow in this phase.

## Reads and media

`ContentQueries` direct reads and bounded batch hydrate use current account, graph
and community APIs; attribution display names are fetched only after content
authorization, through the narrow ProfileQueries API, not public profile bypass.
SQL candidates consume the published versioned account/graph/community views only
and reproduce the canonical ContentAccessPolicy. Hydration rechecks current facts;
SQL/object/API parity is a real PostgreSQL regression, not cached permission.
Inactive author/viewer, either-direction block, removal/deletion and current scope
deny. Mute does not alter ordinary authorization. Public-community signed banned/
actively suspended viewers are denied; anonymous PUBLIC reads do not grant writes.
Private-community ownership of a post does not bypass current membership.

`GET /posts` accepts at most one authorId/communityId, size 1–100; unfiltered query
is supported. An author filter includes that author's visible scoped posts; this
is not the future personal-only `/users/{id}/timeline` route. Ordering is immutable
publishedAt DESC/UUID DESC. Encrypted cursor includes version, viewer/filter binding,
asOf, last scanned candidate and 15-minute expiry. Tampering, different actor/filter
or expiry gives 400. Short/empty pages may continue; no totals or cross-page snapshot.
One own-table aggregate statement loads roots, ordered recipients, attachments and
mentions from the same PostgreSQL READ COMMITTED snapshot. Independent relation
aggregations avoid Cartesian multiplication and per-post SQL; bounded owner API
batches then recheck current authorization. It cannot combine an old CUSTOM grant
with a new body or an incompatible audience-kind/mention/attachment version.

History requires current ordinary visibility plus author or effective MODERATOR+
authority. Staff reads are audited. Immutable revision snapshots include body,
link, audience, attachment and mention data; snapshots never grant historical
permission. History SQL is capped at the coherent post revision that was authorized,
so a later committed edit cannot enter an already-authorized old response. A
concurrent revocation can still race that old response, not grant a newer body.
HTTP history pages expose body/link/audience/media, sorted revision
ordinal descending with exclusive `before`, size 1–100. This ordinal is returned
as nextCursor, not the encrypted post-list cursor. Thirty-day retention applies
even if an old revision's audience would once have granted access.

Source-authorized media issuance additionally checks exact active binding, READY
generation and version-pinned variants via MediaQueries. Optional revision requires
retained history authority and exact snapshot membership. Uploader status is
metadata only; there is no arbitrary UUID/key signer or original preview endpoint.
All capability responses are private/no-store, <=60 seconds, and still require
CSRF/Origin for unsafe anonymous requests. [Media](media.md) explains irreversible
expiry/previously downloaded bytes and isolated processing.

## Retention and reconciliation

Every source mutation schedules POST_RETENTION at +30 days and a binding check.
Retention prunes at most 500 old revisions per transaction, retains UUID tombstones
and redacts deleted body/link/relations after 30 days. Released edit/deletion images
purge at the same retention boundary. Removal holds images privately for 30 days;
late restore cannot resurrect purged/released bytes (text can restore, signing
fails closed). This is a bounded default, not an indefinite legal evidence hold.
Missing/wrong binding fails synchronous signing and emits technical audit alerts
when the owner reconciliation job runs. Restore drills requeue checks in bounded
source batches; media never reverse-joins content/profile internals.

## Comments and reactions — Phase 5 content slice

`Interactions` is the content owner API. Root/reply comments use plain text 1–5,000
Unicode code points, immutable parent/author/creation IDs, oldest-first timestamp/UUID
ordering and independent 20 default/100 maximum pages. A reply to a reply resolves
the same root and records addressed author context. Cross-post or third-level roots
are rejected by the command and PostgreSQL composite FK/root trigger. Edits require
ownership/current parent scope/If-Match and set editedAt; no revision history.
Same-body edit is a no-op. Create has optional 24-hour logical idempotency, replay
reauthorization and changed-body conflict. Comment create/edit JSON is capped at
64 KiB to accommodate Unicode/JSON escaping; other preexisting limits remain.

Every read/mutation checks the containing post and current account/block/community
facts. Community writes require effective membership even for PUBLIC communities;
archive and loss of membership deny writes. Comment-specific actor filtering is
additional to parent policy. A blocked/inactive/deleted/removed root appears only
when visible replies need context, with author/name/body/version/edit/reply context
all null. Replies from visible actors remain readable; their replyToUserId is null
when that addressed user is blocked/inactive. No reaction/reply can target a hidden
root. Post deletion/removal denies all descendants.

Comment list text and its parent/actor eligibility are selected in one READ COMMITTED
statement snapshot using the existing canonical content SQL predicate. Subsequent
attribution batches never reload newer comment text. An in-flight old response may
race a new revocation; it cannot combine that old grant with a new private body.
Current reads/counts consume only the published view allowlist. Mute is not an
ordinary read denial. Cursor authenticated encryption conceals hidden candidate IDs,
binds viewer/post/root or viewer/reaction target, expires in 30 minutes and advances
by the last examined candidate. Short/empty continued pages and no totals are explicit.

Typed post/comment reactions are LIKE/LOVE/CELEBRATE/SUPPORT/INSIGHTFUL. PUT replaces
or reactivates a single actor/target row; same active type emits nothing. DELETE sets
active=false; absent/inactive is a no-op. Row ID survives and versions increase across
replacement/deletion/reactivation. Exactly-one typed FK and uniqueness include
inactive rows; identity columns are immutable. All effects/outbox are transactional.
Actor lists and grouped counts filter active/unblocked accounts and current source
eligibility. `engagement` batches at most 100 parent IDs in one grouped SQL statement;
counts describe visible comments/reactions, are optional display data and grant no access.

Write order: sorted shared actor/post/comment/root author account guards, shared
community write guard, sorted shared graph pairs, post, then comments. Comment create
locks addressed/root comments shared in UUID order; edit/delete locks target exclusive.
Post reaction requests exclusive post mode initially; comment reaction requests shared
post then exclusive comment. No mode upgrade, network under locks or command retry is
introduced. `CommentModeration` is a transaction-required owner seam with scoped staff
authority and deletion precedence; [report/action orchestration](moderation.md)
uses it within the originating case transaction.

Own deletion schedules durable COMMENT_RETENTION for +30 days. Its idempotent owner
maintenance locks account → post → comment and redacts deleted text while retaining
the lifecycle UUID/version. Post retention redacts at most 500 descendants per job
after parent deletion reaches 30 days, emits monotonic CommentDeleted tombstones/audit
atomically and durably requeues the next bounded batch. Removal is still private,
not author deletion; restoration cannot undo the author's deletion.

## Phase 6 source queries

ContentQueries.authorizeFeed checks current scope even for empty results;
feedCutoff reads PostgreSQL time. scanCandidates returns at most 100 lightweight
keys, reusing canonical SQL facts/eligibility and the published view allowlist.
Home union is personal own/follow/friend/CUSTOM plus effective joined communities,
deduplicated before pagination and home-only mute filtering. Timeline is personal
only. No full graph list is passed through Java.

hydrateAuthorized bounds IDs to 100 and validates current source membership/
relations/mute and ordinary policy in the same statement as body/relations.
Subsequent owner-policy batches may remove already selected old bodies, never
reload newer text. Attribution and engagement are batched after eligibility.
Feed owns rank/cursor/refill/metrics, not content authorization; [feed](feed.md)
records chronology, continuation and query-budget semantics.


## Phase9A-II authored erasure

The isolated owner repairs current subject mentions/CUSTOM recipients immediately;
the last-recipient change narrows to ONLY_ME atomically. It soft-deletes authored
roots/comments, releases owned media bindings and nulls the subject's reaction
choice with a one-way tombstone. At DB t0+30d, nested100-item stages remove all
owned revisions, attachment/alt-text mappings after verified asset-ID export,
recipients, mentions, hashtags and authored body/link/comment text. A complete
post with arbitrary descendants is never one collection item. Ordinary queued
parent retention skips terminal authors/erasure-origin posts, preserving other
authors' replies, immutable history/reply IDs and shared community content.
Post/comment/reaction terminal shapes cannot be restored by old writes.
