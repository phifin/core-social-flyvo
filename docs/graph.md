# Social graph — Phase 3

Local implementation accepted after independent main review on 2026-10-10. Read with [domain](domain-model.md),
[security](security.md), [schema](database-design.md), [ADR-006](adr/006-social-graph-and-interactions.md)
and [OpenAPI](api/openapi.yaml). Content/feed/notification consumers remain later phases.

## Relationships and authorization

Follow, friend request, friendship, block and mute are distinct owned tables.
All user mutations require a current ACTIVE actor; admission/follow/mute creation
also requires an ACTIVE target. UUID knowledge is not read permission. Self relations
are 400, unknown targets 404, bilateral block denies new follow/mute/friend admission
with 404. Removing one's own edge is idempotent and remains possible for inactive
targets. Blocking an existing inactive account is allowed. Private profile scope
does not turn follow into an approval workflow. Friendship never creates follows.

Requests last 30 days; only the addressee accepts/declines and requester cancels.
Same-direction pending creation returns the existing request; opposite pending
direction is 409, never implicit acceptance. Friendship already present is 409.
Acceptance resolves request and creates canonical friendship in one transaction.
Matching terminal response retries return the historical result after actor/scope/
block checks, without restoring a friendship removed since acceptance. Request
creation uses this domain pending-pair uniqueness, not generic Idempotency-Key.

At `now >= expiresAt`, a pending request grants no access. Replacement first marks
the previous pending row EXPIRED under the pair guard. The existing worker also
expires requests using a job keyed by request UUID; duplicate/old jobs cannot affect
a replacement. A stale accept returns 409 without requiring cleanup to run.

Block atomically inserts the actor's block, deletes both follow directions and
friendship, and cancels a live pending request (or expires a stale one). Reverse
block remains independent. Unblock removes only the actor's block and restores
nothing. Mute is independent, does not deny visiting profiles and survives block;
its listing hides currently blocked/inactive targets until eligible again.

Profile FRIENDS accepts owner/current friends only, with ACTIVE account checks.
Bilateral block overrides all signed-in profile visibility, including PUBLIC.
Anonymous public visibility remains possible; mute has no profile denial effect.

## Lists and internal APIs

`GET /me/graph/{kind}` lists following/followers/friends/blocks/mutes for the actor.
Own block management is the explicit block-filter exception; inactive targets are
still hidden. `GET /users/{id}/graph/{kind}` exposes following/followers/friends
only after the owning profile policy authorizes its profile on every request.
Graph never calls back into profile. The internal visibleEdges API is a narrow
profile consumer contract, not an independent public authorization entry point.
No profile names, email, tokens or hidden-result totals appear in graph responses.

Pages scan at most size (default 20, maximum 100) UUID-ascending candidates using
keyset queries; graph blocks filter before the limit and account ACTIVE filtering
uses one batched owner API call afterward. Empty/short filtered pages may still
have nextCursor: continue until it is null. There is no cross-request snapshot.
Request lists include only live PENDING incoming/outgoing requests and filter
blocks/inactive counterparts. History is returned only to an authorized participant
through command retries; no global request directory exists.

Pass nextCursor as `after`. Cursors expire after 15 minutes and bind viewer,
owner and list kind/direction. They reuse platform Secrets AES-GCM encryption with
AAD `graph-page:v1:<viewer-or-null>:<owner>:<kind>`; plaintext contains last scanned
UUID and expiry. Authenticated encryption both prevents scope/tamper changes and
hides filtered candidate IDs. Input length is capped at 256; invalid/expired/scope
mismatch is 400. Encryption key rotation invalidates outstanding cursors; restart
with the same configured key preserves them. No new key/service/crypto implementation.

RelationshipQueries.facts returns at most 100 current raw relationship facts in
one owned SQL query; callers compose account/resource policy, never authorize from
a cached projection. AccountQueries.activeIds uses one bounded query. Profile uses
current facts for FRIENDS/block. Published SQL views expose only IDs and are
reserved for the documented future content query adapter; graph reads account
facts exclusively through account APIs.

## Transactions, guards and durable facts

User commands take shared account guards in PostgreSQL-compatible UUID order,
then the exclusive transaction advisory pair guard and re-read source state.
Canonical UUID order uses normalized UUID text (unsigned byte order), never Java's
signed UUID.compareTo. Pair lock key is the signed big-endian first eight bytes of
SHA-256 of ASCII `social-core:graph-pair:v1:<low UUID>:<high UUID>`. Collisions only
serialize unrelated work. Every pair mutation, including expiry, uses this namespace.

guardInteractions requires an existing transaction and prior sorted account guards
from the caller; it obtains shared pair guards in signed key order, then checks
current bilateral blocks. There is no shared-to-exclusive upgrade. Callers must
check relevant target accounts as part of their account policy. Writes already
holding a shared guard serialize against block; subsequent guarded writes see it.
Reads retain the documented weak consistency around concurrent committed changes.

Every effective pair transition atomically bumps retained pair_revision ID/version
and persists a semantic outbox event; no-op PUT/DELETE or terminal retries emit none.
Deletion/recreation never resets the source ID/version. Types include FollowChanged,
MuteChanged, BlockChanged, FriendRequestCreated/Resolved/Expired,
FriendshipEstablished/Changed. RelationshipChanged v1 contains pair IDs, actor
(null for expiry), request ID/status when relevant and both directional follow/
block/mute flags plus friendship after that transition. BlockChanged therefore
captures all removed relationship facts atomically. These internal durable facts
are not public responses; future consumers must authorize and suppress private
block/mute information. Envelope aggregateType is graph, aggregateId/version is
pair_revision, eventSequence 0. No future consumer is enabled.

Request creation persists FRIEND_REQUEST_EXPIRY job in the same transaction.
The DB handler and fenced acknowledgement use the existing worker contract;
ten attempts, lease/reclaim/DEAD handling and bounded cleanup are inherited.
Block/unblock has attributable security audit; mandatory effect failure rolls back
the source. No network call happens under business locks.
