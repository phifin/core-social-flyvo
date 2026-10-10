# Communities and scoped membership — Phase 2

This is the implemented subsystem contract. Read with [domain model](domain-model.md),
[security](security.md), [schema](database-design.md), [OpenAPI](api/openapi.yaml)
and [Phase 2 evidence](phases/phase-2.md). Local delivery is accepted after round 2
main review; production readiness remains separately gated.

## Community and discovery

Creation defaults to PRIVATE + INVITE_ONLY. Slugs are stable, unique lowercase
ASCII strings, 3–80 characters (`[a-z0-9][a-z0-9-]{2,79}`); names are 1–120
characters, descriptions at most 4,000. Settings PATCH preserves omitted/null
fields; it cannot change slug/owner/status. ADMIN/OWNER edits require community
If-Match. Community version increments for each effective command transition,
including membership/admission changes; this deliberately conservative ETag
can become stale when another member changes state.

Anonymous discovery exposes PUBLIC cards only. Signed-in discovery exposes
PUBLIC/PRIVATE cards with ID, slug, name, description, visibility, status and
version, never owner/membership facts. A private outsider's single GET returns
that same minimal card, ownerId/joinMode null. Effective members read private
settings; PUBLIC settings are readable anonymously. Signed-in banned/suspended
members receive only a discovery card, including for PUBLIC groups. This card
is discovery, not private/community-content permission. Member history and
request messages have separate current authorization gates.

Small lists use stable offset pagination: size 1–100, offset 0–10,000, no total,
nextCursor null. Advance offset by items.length; a short/empty page ends traversal.
Discovery/invitation/request order is createdAt/id descending; members order by
userId. Lists are not snapshots across calls. Membership responses contain scalar
IDs, effective roles and lifecycle/version, no profile/identity data.

## Membership and admission

OPEN permits immediate verified ACTIVE-account join, including PRIVATE+OPEN.
REQUEST creates a request; INVITE_ONLY requires an existing-user invitation.
Existing effective membership is an idempotent join without role reset/event.
LEFT/REMOVED rejoin reuses the row, increments version and resets role to MEMBER.
BANNED cannot join/accept/receive a new invitation/request. Suspension is a live
timestamp check on ACTIVE membership; expiry needs no worker or status update.

Invitations grant MEMBER only and expire in seven days at an exclusive boundary
(`now < expiresAt`). A new invite cancels the older pending request; a new request
revokes a live older invite (or marks an expired one EXPIRED). Both operations
hold the exclusive community guard. Expired PENDING rows are resolved before
replacement, preserving history and the partial unique indexes. Changing join
mode does not resolve or auto-approve pending requests; staff can still review
them and a live explicit invitation stays usable. Join resolves pending admission
paths to avoid granting duplicate membership. Same terminal approve/accept/etc.
retries return the original logical result after current actor/scope checks.

Create community/invitation/request supports an optional printable ASCII
Idempotency-Key (1–128 characters). PostgreSQL stores only actor/route/key hash,
canonical request hash and logical UUID for 24 hours, in the mutation transaction.
Same key with changed body is 409. Replay checks current access/staff authority;
it never caches private bodies or credentials. Expired keys can be reused;
domain uniqueness still decides the result. Member/role DELETE/PATCH uses the
target membership's If-Match; retries with an obsolete version yield 412.

Claim is one PostgreSQL UPSERT/RETURNING that inserts or locks the key and renews
only when `expires_at <= claim time`. Live replay preserves the original hash,
reference, creation time and expiry; it does not extend retention. Mutation and
reference completion retain that lock through commit. Cleanup locks at most 500
expired rows with SKIP LOCKED and rechecks expiry on deletion, never waiting on a
claim or removing a renewed key from a stale candidate snapshot. A concurrent
cleanup deletion is handled by the atomic UPSERT, not a fallible INSERT/SELECT gap.

## Authority, archive and account closure

OWNER derives exclusively from community.owner_user_id. Persisted membership
roles are ADMIN/MODERATOR/MEMBER. All staff need current effective membership;
no platform-role bypass exists on ordinary community endpoints. Staff cannot
promote themselves, grant their own/higher role, or sanction self/equal/higher
role/owner. ADMIN manages MEMBER/MODERATOR roles, OWNER manages ADMIN too.
MODERATOR sanctions MEMBER, ADMIN sanctions MODERATOR, OWNER sanctions ADMIN.
Invitation/settings/request review requires ADMIN; transfer/archive requires OWNER.

Transfer checks successor ACTIVE account and effective membership, updates the
owner pointer and former owner's ADMIN membership atomically. It does not create
a second OWNER membership value. The deferred owner FK and narrow commit trigger
reject an ACTIVE community without an ACTIVE, unsuspended owner membership.

Archive preserves authorized history and blocks settings, join, admission,
roles, sanctions and transfer. The detailed domain rule permits self-leave after
archive, including the retained owner; this is the sole user mutation exception.
It follows implementation-handoff's subsystem-detail precedence, not a new product
decision. Leaving does not remove historical rows or grant access. Active owners
must transfer/archive before leaving or requesting account deletion. Identity
checks community ownership under its exclusive account guard before deletion
and session revocation. Archive has no unarchive/delete API in this phase.

## Guards, sanctions and durability

Commands acquire shared account guards in UUID string order, then an exclusive
community row guard and reload current community/member facts. Creation/transfer
guards the new owner's account, serializing with exclusive account closure.
MembershipQueries.guardWrite requires an existing transaction and the caller's
earlier account guards; it acquires FOR SHARE and rechecks active community/current
membership. It never upgrades shared to exclusive. Source reads do not lock.
Batched facts are bounded at 100 community IDs and use two SQL queries, no N+1.

Moderation's purpose-specific HTTP command records an append-only attributable
action (private reason <=2,000 characters and before/after membership snapshots),
owner-module transition, scoped audit and semantic outbox in one transaction.
Suspension must end in the future, at most 365 days away. BAN is indefinite;
UNBAN changes BANNED to REMOVED/MEMBER, never automatically joins. Mandatory
audit/action failure rolls back membership and events. There is no generic report,
content moderation, evidence-read or platform-admin HTTP surface yet.

Each invitation atomically schedules COMMUNITY_INVITE_EXPIRY through the existing
platform worker. Its payload is invitation/community UUIDs, dedup key invitation
UUID, due at expiresAt. DB handler + fenced acknowledgement share one transaction.
Duplicate/reclaimed delivery is harmless and cannot expire a replacement ID or
accepted invitation. Expiry only resolves PENDING status; it grants no authority
and does not depend on account authentication or an ACTIVE community. Retry is
bounded at ten attempts, lease 60 seconds, backoff 5 seconds to 1 hour with jitter;
DEAD work remains observable/retained. Permission checks enforce expiry while the
worker is stopped. See [community operations](../ops/community.md).

Published APIs: CommunityCommands, MembershipCommands, AdmissionCommands and
MembershipQueries. Sanction is an internal owner-module command called by the
moderation transaction, not a separately routable unaudited HTTP endpoint.
CommunityTransition and ModerationActionApplied are publisher-owned event records.
Outbox community versions order effective transitions; payloads include source
IDs/status/member role/suspension/version, without names, request messages or reasons.
CommunityCreated/Updated status is the post-transition **community** status,
including ARCHIVED while the owner membership remains ACTIVE. Membership events
use the subject membership status; admission events use invitation/request status.
The [event contract](events-and-jobs.md) specifies each source and version explicitly.
No future notification/search consumer is enabled. Source read-only views are
community_api.communities_v1/memberships_v1, allowlisted solely for the future
content query adapter; membership view role is stored role, OWNER derives by join.

No graph blocks, posts, feeds, notifications or search projection are implemented
here; their later consumers must compose current account and community facts.
