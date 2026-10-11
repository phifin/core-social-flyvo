# Phase 4 posts and current privacy

Implemented routes and DTOs: [OpenAPI](api/openapi.yaml). Completion/review state:
[Phase 4](phases/phase-4.md), [mailbox](agent-handoff.md). This is a bounded post
query/publication capability, not Phase 5 interactions or the Phase 6 home feed.

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
